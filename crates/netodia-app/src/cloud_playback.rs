//! Joins typed `NetEase` stream tickets to the native decoder and output engine.

use std::sync::Arc;
use std::time::Duration;

use netodia_audio::player::source::audio::AudioSource;
use netodia_audio::player::source::buffered::BufferedAudioSource;
use netodia_audio::player::source::file_decode::FileDecoder;
use netodia_core::entities::cloud::{Account, Quality};
use netodia_core::error::{AppError, AppResult};
use netodia_engine::player::engine::backend::PlaybackEngine;
use netodia_net::services::net::range_reader::RangeReader;
use netodia_playback::player::playback::replaygain::TrackReplayGain;

use crate::cloud::{CloudService, QrStatus, SessionStatus};
use tokio::sync::watch;

/// Result of resolving and handing a cloud track to the engine.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CloudPlaybackResult {
    /// False when a newer play, pause, stop, or account transition superseded this request.
    pub started: bool,
    /// The server marked the track as a limited trial.
    pub trial: bool,
}

/// Application-level orchestration for direct, buffered `NetEase` playback.
#[derive(Clone)]
pub struct CloudPlayback {
    cloud: CloudService,
    engine: Arc<PlaybackEngine>,
}

impl CloudPlayback {
    pub fn new(cloud: CloudService, engine: Arc<PlaybackEngine>) -> Self {
        Self { cloud, engine }
    }

    pub fn account_status(&self) -> SessionStatus {
        self.cloud.status()
    }

    pub fn subscribe_account(&self) -> watch::Receiver<SessionStatus> {
        self.cloud.subscribe()
    }

    /// Account changes stop the previous account's audio before the service invalidates its
    /// account-scoped requests and session.
    pub async fn login_phone(
        &self,
        phone: &str,
        country_code: &str,
        password: &str,
    ) -> AppResult<Account> {
        self.engine.stop();
        self.cloud.login_phone(phone, country_code, password).await
    }

    pub async fn login_email(&self, email: &str, password: &str) -> AppResult<Account> {
        self.engine.stop();
        self.cloud.login_email(email, password).await
    }

    pub async fn begin_qr_login(&self) -> AppResult<String> {
        self.engine.stop();
        self.cloud.begin_qr_login().await
    }

    pub async fn check_qr_login(&self, key: &str) -> AppResult<QrStatus> {
        let status = self.cloud.check_qr_login(key).await?;
        if status == QrStatus::Authorized {
            self.engine.stop();
        }
        Ok(status)
    }

    pub async fn logout(&self) -> AppResult<()> {
        self.engine.stop();
        self.cloud.logout().await
    }

    /// Resolve a short-lived ticket, open and seek the media source off the async executor, then
    /// hand the buffered decoder to the engine if the request is still current.
    pub async fn play(
        &self,
        song_id: i64,
        quality: Quality,
        volume: f64,
        speed: f64,
        start_position: Option<Duration>,
    ) -> AppResult<CloudPlaybackResult> {
        let generation = self.engine.reserve_playback_request();
        let cancellation = self.cloud.account_tasks_token();
        let ticket = self.cloud.stream_ticket(song_id, quality).await?;
        if cancellation.is_cancelled() {
            return Err(AppError::network_msg("网易云播放请求已取消"));
        }

        let trial = ticket.trial;
        let url = ticket.url;
        let extension = decoder_extension(&ticket.format).to_owned();
        let reported_size = ticket.size;
        let start = start_position.unwrap_or(Duration::ZERO);
        let source_task = tokio::task::spawn_blocking(move || {
            open_buffered_source(&url, &extension, reported_size, start)
        });

        let source = tokio::select! {
            () = cancellation.cancelled() => {
                return Err(AppError::network_msg("网易云播放请求已取消"));
            }
            result = source_task => {
                result.map_err(|error| AppError::io("在线播放解码任务失败", error))??
            }
        };
        if cancellation.is_cancelled() {
            return Err(AppError::network_msg("网易云播放请求已取消"));
        }

        let started = self.engine.play_prepared_source(
            generation,
            source,
            volume,
            speed,
            start_position,
            TrackReplayGain::default(),
        );
        Ok(CloudPlaybackResult { started, trial })
    }
}

fn open_buffered_source(
    url: &str,
    extension: &str,
    reported_size: Option<u64>,
    start: Duration,
) -> AppResult<BufferedAudioSource> {
    let reader = RangeReader::open(url)
        .map_err(|error| AppError::network_msg(format!("在线播放源加载失败：{error}")))?;
    let byte_len = reader.byte_len().or(reported_size);
    let mut decoder = FileDecoder::open_reader(reader, extension, byte_len)?;
    if !start.is_zero() {
        decoder
            .try_seek(start)
            .map_err(|error| AppError::Player(format!("在线播放定位失败：{error}")))?;
    }
    BufferedAudioSource::open(move || Ok(decoder))
}

fn decoder_extension(format: &str) -> &'static str {
    match format.to_ascii_lowercase().as_str() {
        "flac" => "flac",
        "aac" => "aac",
        "m4a" => "m4a",
        _ => "mp3",
    }
}

#[cfg(test)]
mod tests {
    use super::{decoder_extension, open_buffered_source};
    use netodia_audio::player::source::audio::AudioSource;
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::thread;
    use std::time::Duration;

    fn serve_media(
        listener: TcpListener,
        bytes: Vec<u8>,
    ) -> thread::JoinHandle<std::io::Result<()>> {
        thread::spawn(move || {
            let (mut stream, _) = listener.accept()?;
            stream.set_read_timeout(Some(Duration::from_secs(2)))?;
            let mut request = Vec::new();
            let mut chunk = [0; 512];
            while !request.windows(4).any(|window| window == b"\r\n\r\n") {
                let read = stream.read(&mut chunk)?;
                if read == 0 {
                    break;
                }
                request.extend_from_slice(&chunk[..read]);
            }
            write!(
                stream,
                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                bytes.len()
            )?;
            stream.write_all(&bytes)
        })
    }

    #[test]
    fn decoder_hint_accepts_known_formats_and_defaults_unknown_values() {
        for (input, expected) in
            [("mp3", "mp3"), ("FLAC", "flac"), ("aac", "aac"), ("m4a", "m4a"), ("unknown", "mp3")]
        {
            assert_eq!(decoder_extension(input), expected);
        }
    }

    #[test]
    fn a_non_range_cdn_response_reaches_the_buffered_decoder()
    -> Result<(), Box<dyn std::error::Error>> {
        let bytes =
            std::fs::read(std::path::Path::new(netodia_testkit::ASSETS_DIR).join("silence.wav"))?;
        let length = u64::try_from(bytes.len())?;
        let listener = TcpListener::bind("127.0.0.1:0")?;
        let url = format!("http://{}/track.wav", listener.local_addr()?);
        let server = serve_media(listener, bytes);

        let mut source = open_buffered_source(&url, "wav", Some(length), Duration::ZERO)?;

        assert_eq!(source.sample_rate().get(), 44_100);
        assert_eq!(source.channels().get(), 1);
        assert_eq!(source.total_duration(), Some(Duration::from_secs(1)));
        assert!(source.next().is_some());
        server.join().map_err(|_| std::io::Error::other("media server panicked"))??;
        Ok(())
    }
}
