//! Seekable HTTP media reads for the decoder worker.
//!
//! Requests use byte ranges and keep a bounded cache shared by reopened decoders. A host that
//! ignores the initial range is copied incrementally to an anonymous temporary file, then served
//! by seeking that file. This reader never receives account cookies; its URL is the short-lived
//! CDN ticket.

use std::collections::VecDeque;
use std::fs::File;
use std::io::{self, Read, Seek, SeekFrom};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

use reqwest::StatusCode;
use reqwest::blocking::{Client, Response};
use reqwest::header::{CONTENT_RANGE, RANGE};

const CHUNK_BYTES: u64 = 64 * 1024;
// 2 MiB per active stream, including its pinned header. Keeping the header avoids re-fetching it
// after a demuxer probes trailing tags or a replacement decoder opens at a new playback position.
const CACHED_CHUNKS: usize = 32;
const MAX_SPOOL_BYTES: u64 = 1024 * 1024 * 1024;
const REQUEST_TIMEOUT: Duration = Duration::from_mins(1);

/// A sanitized failure from an HTTP media source. It deliberately omits the URL, which may contain
/// a signed CDN token.
#[derive(Debug, thiserror::Error)]
pub enum MediaError {
    #[error("media URL is invalid")]
    InvalidUrl,
    #[error("media request timed out")]
    Timeout,
    #[error("media request failed")]
    Transport,
    #[error("media server returned HTTP {0}")]
    HttpStatus(u16),
    #[error("media server returned an invalid byte range")]
    InvalidRange,
    #[error("media response could not be stored temporarily")]
    TemporaryFile,
    #[error("media without byte-range support exceeds the 1 GiB buffering limit")]
    SpoolTooLarge,
}

#[derive(Clone)]
struct RangeChunk {
    start: u64,
    bytes: Arc<[u8]>,
}

enum Storage {
    Range { head: Vec<u8>, recent: VecDeque<RangeChunk> },
    Spool(File),
}

struct CachedMedia {
    storage: Storage,
    byte_len: Option<u64>,
}

/// A blocking, seekable media source. Call it from a decoder worker, never from an audio callback.
pub struct RangeReader {
    client: Client,
    url: reqwest::Url,
    media: Arc<Mutex<CachedMedia>>,
    position: u64,
}

impl RangeReader {
    /// Fetches the first range, or spools the response when the host does not support ranges.
    pub fn open(url: &str) -> Result<Self, MediaError> {
        let url = super::http_url(url).ok_or(MediaError::InvalidUrl)?;
        let client = Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .timeout(REQUEST_TIMEOUT)
            .user_agent(concat!("Netodia/", env!("CARGO_PKG_VERSION")))
            .build()
            .map_err(|_| MediaError::Transport)?;
        let response = send_range(&client, &url, 0, CHUNK_BYTES - 1)?;

        if response.status() == StatusCode::PARTIAL_CONTENT {
            let (start, end, byte_len) = content_range(&response)?;
            if start != 0 || end >= CHUNK_BYTES {
                return Err(MediaError::InvalidRange);
            }
            let bytes = bounded_chunk(response)?;
            if bytes.len() as u64 != end - start + 1 {
                return Err(MediaError::InvalidRange);
            }
            Ok(Self {
                client,
                url,
                media: Arc::new(Mutex::new(CachedMedia {
                    storage: Storage::Range { head: bytes, recent: VecDeque::new() },
                    byte_len,
                })),
                position: 0,
            })
        } else if response.status().is_success() {
            let (file, byte_len) = spool(response)?;
            Ok(Self {
                client,
                url,
                media: Arc::new(Mutex::new(CachedMedia {
                    storage: Storage::Spool(file),
                    byte_len: Some(byte_len),
                })),
                position: 0,
            })
        } else {
            Err(MediaError::HttpStatus(response.status().as_u16()))
        }
    }

    #[must_use]
    pub fn byte_len(&self) -> Option<u64> {
        self.lock_media().byte_len
    }

    /// An independent cursor over the same URL, connection pool and downloaded bytes. This does
    /// no I/O, so seeking a cloud track can rebuild its decoder without downloading its header
    /// again, or the entire file again when the server ignores Range.
    #[must_use]
    pub fn reopen(&self) -> Self {
        Self {
            client: self.client.clone(),
            url: self.url.clone(),
            media: Arc::clone(&self.media),
            position: 0,
        }
    }

    fn lock_media(&self) -> MutexGuard<'_, CachedMedia> {
        self.media.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    fn fill_range(&self) -> Result<Option<RangeChunk>, MediaError> {
        let start = self.position / CHUNK_BYTES * CHUNK_BYTES;
        let mut end = start.saturating_add(CHUNK_BYTES - 1);
        if let Some(byte_len) = self.byte_len() {
            if start >= byte_len {
                return Ok(None);
            }
            end = end.min(byte_len - 1);
        }

        let response = send_range(&self.client, &self.url, start, end)?;
        if response.status() == StatusCode::PARTIAL_CONTENT {
            let (actual_start, actual_end, byte_len) = content_range(&response)?;
            if actual_start != start || actual_end > end {
                return Err(MediaError::InvalidRange);
            }
            if self.byte_len().is_some_and(|known| byte_len.is_some_and(|actual| actual != known)) {
                return Err(MediaError::InvalidRange);
            }
            let bytes = bounded_chunk(response)?;
            if bytes.len() as u64 != actual_end - actual_start + 1 {
                return Err(MediaError::InvalidRange);
            }
            let chunk = RangeChunk { start, bytes: bytes.into() };
            let mut media = self.lock_media();
            if media.byte_len.is_none() {
                media.byte_len = byte_len;
            }
            if let Storage::Range { recent, .. } = &mut media.storage {
                // Concurrent decoders may finish the same range. Keep only one cached copy.
                recent.retain(|chunk| chunk.start != start);
                if recent.len() == CACHED_CHUNKS - 1 {
                    recent.pop_back();
                }
                recent.push_front(chunk.clone());
            }
            // Keep the just-fetched chunk through this read even if another decoder immediately
            // evicts it from the shared cache.
            Ok(Some(chunk))
        } else if response.status().is_success() {
            let (file, byte_len) = spool(response)?;
            let mut media = self.lock_media();
            media.byte_len = Some(byte_len);
            media.storage = Storage::Spool(file);
            Ok(None)
        } else {
            Err(MediaError::HttpStatus(response.status().as_u16()))
        }
    }

    fn read_cached(&self, buffer: &mut [u8]) -> io::Result<Option<usize>> {
        let mut media = self.lock_media();
        match &mut media.storage {
            Storage::Spool(file) => {
                // File::try_clone shares its OS cursor. Seek and read under the same lock so an
                // outgoing decoder cannot move the replacement decoder's cursor between them.
                file.seek(SeekFrom::Start(self.position))?;
                file.read(buffer).map(Some)
            }
            Storage::Range { head, recent } => {
                if self.position < head.len() as u64 {
                    return copy_chunk(head, self.position, buffer).map(Some);
                }
                let Some(index) = recent.iter().position(|chunk| {
                    self.position >= chunk.start
                        && self.position < chunk.start + chunk.bytes.len() as u64
                }) else {
                    return Ok(None);
                };
                recent.swap(0, index);
                let chunk = &recent[0];
                copy_chunk(&chunk.bytes, self.position - chunk.start, buffer).map(Some)
            }
        }
    }
}

impl Read for RangeReader {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        if buffer.is_empty() || self.byte_len().is_some_and(|len| self.position >= len) {
            return Ok(0);
        }

        let read = if let Some(read) = self.read_cached(buffer)? {
            read
        } else {
            // No cache lock spans network I/O: the outgoing decoder can keep playing while a
            // replacement fetches the new target's first range.
            match self.fill_range().map_err(io_error)? {
                Some(chunk) => copy_chunk(&chunk.bytes, self.position - chunk.start, buffer)?,
                None => {
                    self.read_cached(buffer)?.ok_or_else(|| io_error(MediaError::InvalidRange))?
                }
            }
        };
        self.position = self.position.saturating_add(read as u64);
        Ok(read)
    }
}

impl Seek for RangeReader {
    fn seek(&mut self, from: SeekFrom) -> io::Result<u64> {
        let position = match from {
            SeekFrom::Start(position) => position,
            SeekFrom::Current(delta) => signed_seek(self.position, delta)?,
            SeekFrom::End(delta) => {
                let len = self.byte_len().ok_or_else(|| {
                    io::Error::new(io::ErrorKind::Unsupported, "media length is unknown")
                })?;
                signed_seek(len, delta)?
            }
        };
        self.position = position;
        Ok(position)
    }
}

fn copy_chunk(bytes: &[u8], offset: u64, buffer: &mut [u8]) -> io::Result<usize> {
    let offset = usize::try_from(offset)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "media range offset overflow"))?;
    let count = bytes.len().saturating_sub(offset).min(buffer.len());
    if count == 0 {
        return Ok(0);
    }
    buffer[..count].copy_from_slice(&bytes[offset..offset + count]);
    Ok(count)
}

fn send_range(
    client: &Client,
    url: &reqwest::Url,
    start: u64,
    end: u64,
) -> Result<Response, MediaError> {
    client.get(url.clone()).header(RANGE, format!("bytes={start}-{end}")).send().map_err(|error| {
        if error.is_timeout() { MediaError::Timeout } else { MediaError::Transport }
    })
}

fn content_range(response: &Response) -> Result<(u64, u64, Option<u64>), MediaError> {
    let value = response
        .headers()
        .get(CONTENT_RANGE)
        .and_then(|value| value.to_str().ok())
        .ok_or(MediaError::InvalidRange)?;
    let value = value.strip_prefix("bytes ").ok_or(MediaError::InvalidRange)?;
    let (span, total) = value.split_once('/').ok_or(MediaError::InvalidRange)?;
    let (start, end) = span.split_once('-').ok_or(MediaError::InvalidRange)?;
    let start = start.parse().map_err(|_| MediaError::InvalidRange)?;
    let end = end.parse().map_err(|_| MediaError::InvalidRange)?;
    let byte_len = if total == "*" {
        None
    } else {
        Some(total.parse().map_err(|_| MediaError::InvalidRange)?)
    };
    if end < start || byte_len.is_some_and(|len| len <= end) {
        return Err(MediaError::InvalidRange);
    }
    Ok((start, end, byte_len))
}

fn bounded_chunk(response: Response) -> Result<Vec<u8>, MediaError> {
    if response.content_length().is_some_and(|len| len > CHUNK_BYTES) {
        return Err(MediaError::InvalidRange);
    }
    let mut bytes = Vec::with_capacity(
        response.content_length().and_then(|len| usize::try_from(len).ok()).unwrap_or(0),
    );
    response.take(CHUNK_BYTES + 1).read_to_end(&mut bytes).map_err(|_| MediaError::Transport)?;
    if bytes.len() as u64 > CHUNK_BYTES {
        return Err(MediaError::InvalidRange);
    }
    Ok(bytes)
}

fn spool(response: Response) -> Result<(File, u64), MediaError> {
    if response.content_length().is_some_and(|len| len > MAX_SPOOL_BYTES) {
        return Err(MediaError::SpoolTooLarge);
    }
    let mut file = tempfile::tempfile().map_err(|_| MediaError::TemporaryFile)?;
    let mut response = response.take(MAX_SPOOL_BYTES + 1);
    let byte_len = io::copy(&mut response, &mut file).map_err(|_| MediaError::Transport)?;
    if byte_len > MAX_SPOOL_BYTES {
        return Err(MediaError::SpoolTooLarge);
    }
    file.seek(SeekFrom::Start(0)).map_err(|_| MediaError::TemporaryFile)?;
    Ok((file, byte_len))
}

fn signed_seek(base: u64, delta: i64) -> io::Result<u64> {
    if delta >= 0 {
        base.checked_add(delta.unsigned_abs())
    } else {
        base.checked_sub(delta.unsigned_abs())
    }
    .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "seek position overflow"))
}

fn io_error(error: MediaError) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, error)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use std::net::{TcpListener, TcpStream};
    use std::sync::{Arc, Mutex};
    use std::thread;

    fn request(stream: &mut TcpStream) -> io::Result<String> {
        stream.set_read_timeout(Some(Duration::from_secs(2)))?;
        let mut bytes = Vec::new();
        let mut chunk = [0; 1024];
        while !bytes.windows(4).any(|window| window == b"\r\n\r\n") {
            let read = stream.read(&mut chunk)?;
            if read == 0 {
                break;
            }
            bytes.extend_from_slice(&chunk[..read]);
        }
        String::from_utf8(bytes).map_err(io::Error::other)
    }

    fn listener_url(listener: &TcpListener) -> io::Result<String> {
        Ok(format!("http://{}/track.mp3", listener.local_addr()?))
    }

    #[test]
    fn reads_and_seeks_through_bounded_range_chunks() -> Result<(), Box<dyn std::error::Error>> {
        let listener = TcpListener::bind("127.0.0.1:0")?;
        let url = listener_url(&listener)?;
        let requests = Arc::new(Mutex::new(Vec::new()));
        let seen = requests.clone();
        let body: Vec<u8> =
            (0..180_000).map(|index| u8::try_from(index % 251).unwrap_or_default()).collect();
        let server_body = body.clone();
        let server = thread::spawn(move || {
            for _ in 0..2 {
                let (mut stream, _) = listener.accept()?;
                let request = request(&mut stream)?;
                let range = request
                    .lines()
                    .find(|line| line.to_ascii_lowercase().starts_with("range:"))
                    .ok_or_else(|| io::Error::other("Range header missing"))?
                    .split_once('=')
                    .ok_or_else(|| io::Error::other("range value missing"))?
                    .1
                    .trim();
                seen.lock()
                    .map_err(|_| io::Error::other("requests lock poisoned"))?
                    .push(format!("bytes={range}"));
                let (start, end) = range
                    .trim_start_matches("bytes=")
                    .split_once('-')
                    .ok_or_else(|| io::Error::other("range span missing"))?;
                let start: usize = start.parse().map_err(io::Error::other)?;
                let end: usize =
                    end.parse::<usize>().map_err(io::Error::other)?.min(server_body.len() - 1);
                let bytes = &server_body[start..=end];
                write!(
                    stream,
                    "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes {start}-{end}/{}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                    server_body.len(),
                    bytes.len()
                )?;
                stream.write_all(bytes)?;
            }
            Ok::<(), io::Error>(())
        });

        let mut reader = RangeReader::open(&url)?;
        assert_eq!(reader.byte_len(), Some(u64::try_from(body.len())?));
        let mut first = [0; 8];
        assert_eq!(reader.read(&mut first)?, first.len());
        assert_eq!(&first, &body[..first.len()]);
        reader.seek(SeekFrom::Start(100_000))?;
        let mut later = [0; 8];
        assert_eq!(reader.read(&mut later)?, later.len());
        assert_eq!(&later, &body[100_000..100_008]);
        server.join().map_err(|_| io::Error::other("mock range server panicked"))??;

        // The server is gone: a decoder reopening for a seek must use the downloaded header and
        // target range, while the outgoing decoder retains its independent cursor.
        let mut reopened = reader.reopen();
        reopened.read_exact(&mut first)?;
        assert_eq!(&first, &body[..first.len()]);
        reopened.seek(SeekFrom::Start(100_004))?;
        reopened.read_exact(&mut later)?;
        assert_eq!(&later, &body[100_004..100_012]);
        reader.read_exact(&mut later)?;
        assert_eq!(&later, &body[100_008..100_016]);
        reader.seek(SeekFrom::Start(0))?;
        reader.read_exact(&mut first)?;
        assert_eq!(&first, &body[..first.len()]);
        assert_eq!(
            *requests.lock().map_err(|_| io::Error::other("requests lock poisoned"))?,
            ["bytes=0-65535", "bytes=65536-131071"]
        );
        Ok(())
    }

    #[test]
    fn spools_servers_that_ignore_the_range_header() -> Result<(), Box<dyn std::error::Error>> {
        let listener = TcpListener::bind("127.0.0.1:0")?;
        let url = listener_url(&listener)?;
        let body: Vec<u8> =
            (0..8192).map(|index| u8::try_from(index % 239).unwrap_or_default()).collect();
        let server_body = body.clone();
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept()?;
            let _ = request(&mut stream)?;
            write!(
                stream,
                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                server_body.len()
            )?;
            stream.write_all(&server_body)?;
            Ok::<(), io::Error>(())
        });

        let mut reader = RangeReader::open(&url)?;
        reader.seek(SeekFrom::Start(4000))?;
        let mut bytes = [0; 12];
        assert_eq!(reader.read(&mut bytes)?, bytes.len());
        assert_eq!(&bytes, &body[4000..4012]);
        server.join().map_err(|_| io::Error::other("mock sequential server panicked"))??;

        let mut reopened = reader.reopen();
        reopened.seek(SeekFrom::End(-12))?;
        reopened.read_exact(&mut bytes)?;
        assert_eq!(&bytes, &body[8180..]);
        reader.read_exact(&mut bytes)?;
        assert_eq!(&bytes, &body[4012..4024]);
        reopened.seek(SeekFrom::Start(0))?;
        reopened.read_exact(&mut bytes)?;
        assert_eq!(&bytes, &body[..12]);
        Ok(())
    }
}
