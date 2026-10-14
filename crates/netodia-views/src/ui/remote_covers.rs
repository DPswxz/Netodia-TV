//! Bounded, in-memory thumbnails for cloud artwork.
//!
//! Cloud track rows carry their HTTP/S artwork URL. A mounted row asks for its thumbnail here;
//! the request and decode run off the UI thread, duplicate URLs share one request, and decoded RGB
//! pixels live only in a small LRU. No remote cover is written to the artwork store or filesystem.

use std::collections::HashSet;
use std::num::NonZeroUsize;
use std::sync::Arc;
use std::time::{Duration, Instant};

use futures_util::StreamExt;
use lru::LruCache;
use parking_lot::Mutex;
use slint::{Image, Rgb8Pixel, SharedPixelBuffer};
use tokio::runtime::Handle;
use tokio::sync::Semaphore;

const CACHE_CAP: NonZeroUsize = match NonZeroUsize::new(256) {
    Some(cap) => cap,
    None => panic!("remote cover cache capacity must be non-zero"),
};
const MAX_PENDING: usize = 128;
const MAX_IN_FLIGHT: usize = 6;
const MAX_SOURCE_BYTES: usize = 1_048_576;
const MAX_SOURCE_DIM: u32 = 8192;
const NEGATIVE_CACHE_TTL: Duration = Duration::from_secs(30);

struct CachedCover {
    pixels: Option<SharedPixelBuffer<Rgb8Pixel>>,
    failed_at: Option<Instant>,
}

/// HTTP/S cover thumbnails for virtualized cloud rows. The cache stores pixel buffers rather than
/// `slint::Image`, because images are UI-thread-only while the network and decode are not.
pub struct RemoteCoverThumbs {
    client: reqwest::Client,
    runtime: Handle,
    cache: Mutex<LruCache<String, CachedCover>>,
    pending: Mutex<HashSet<String>>,
    permits: Arc<Semaphore>,
    notify: Arc<dyn Fn() + Send + Sync>,
}

impl RemoteCoverThumbs {
    pub fn new(
        client: reqwest::Client,
        runtime: Handle,
        notify: impl Fn() + Send + Sync + 'static,
    ) -> Self {
        Self {
            client,
            runtime,
            cache: Mutex::new(LruCache::new(CACHE_CAP)),
            pending: Mutex::new(HashSet::new()),
            permits: Arc::new(Semaphore::new(MAX_IN_FLIGHT)),
            notify: Arc::new(notify),
        }
    }

    /// Return the in-memory thumbnail, scheduling one bounded request on a miss.
    pub fn get_or_schedule(self: &Arc<Self>, url: &str) -> Image {
        if !is_remote_artwork_url(url) {
            return Image::default();
        }

        {
            let mut cache = self.cache.lock();
            if let Some(entry) = cache.get(url) {
                if let Some(pixels) = &entry.pixels {
                    return Image::from_rgb8(pixels.clone());
                }
                if entry.failed_at.is_some_and(|at| at.elapsed() < NEGATIVE_CACHE_TTL) {
                    return Image::default();
                }
            }
            cache.pop(url);
        }

        {
            let mut pending = self.pending.lock();
            if pending.contains(url) || pending.len() >= MAX_PENDING {
                return Image::default();
            }
            pending.insert(url.to_owned());
        }

        let this = Arc::clone(self);
        let url = url.to_owned();
        self.runtime.spawn(async move {
            this.fetch_and_cache(url).await;
        });
        Image::default()
    }

    async fn fetch_and_cache(self: Arc<Self>, url: String) {
        let permit = self.permits.clone().acquire_owned().await;
        let pixels = match permit {
            Ok(_permit) => match fetch_image_bytes(&self.client, &url).await {
                Some(bytes) => self
                    .runtime
                    .spawn_blocking(move || decode_thumbnail(&bytes))
                    .await
                    .ok()
                    .flatten(),
                None => None,
            },
            Err(_) => None,
        };

        let failed = pixels.is_none();
        self.cache.lock().put(
            url.clone(),
            CachedCover { failed_at: pixels.is_none().then(Instant::now), pixels },
        );
        self.pending.lock().remove(&url);
        (self.notify)();

        // A short negative cache prevents immediate retry loops while still recovering from a
        // transient network failure without requiring the user to refresh the page.
        if failed {
            let this = Arc::clone(&self);
            self.runtime.spawn(async move {
                tokio::time::sleep(NEGATIVE_CACHE_TTL).await;
                let expired = {
                    let mut cache = this.cache.lock();
                    cache.peek(&url).is_some_and(|entry| entry.pixels.is_none())
                        && cache.pop(&url).is_some()
                };
                if expired {
                    (this.notify)();
                }
            });
        }
    }
}

/// Whether an artwork source should be fetched from a remote HTTP/S server.
pub fn is_remote_artwork_url(source: &str) -> bool {
    parsed_http_url(source).is_some()
}

/// Fetch a bounded image body for an in-memory consumer such as the curated hero collage.
pub(crate) async fn fetch_image_bytes(client: &reqwest::Client, url: &str) -> Option<Vec<u8>> {
    let url = parsed_http_url(url)?;
    let response = client.get(url).send().await.ok()?.error_for_status().ok()?;
    let max_bytes = u64::try_from(MAX_SOURCE_BYTES).ok()?;
    if response.content_length().is_some_and(|length| length > max_bytes) {
        return None;
    }

    let mut stream = response.bytes_stream();
    let mut bytes = Vec::new();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.ok()?;
        if bytes.len().saturating_add(chunk.len()) > MAX_SOURCE_BYTES {
            return None;
        }
        bytes.extend_from_slice(&chunk);
    }
    (!bytes.is_empty()).then_some(bytes)
}

fn parsed_http_url(source: &str) -> Option<reqwest::Url> {
    let url = reqwest::Url::parse(source.trim()).ok()?;
    (matches!(url.scheme(), "http" | "https") && url.host_str().is_some()).then_some(url)
}

fn decode_thumbnail(bytes: &[u8]) -> Option<SharedPixelBuffer<Rgb8Pixel>> {
    let _oversized =
        netodia_artwork::media::image::image_decode::memory_dimensions(bytes, MAX_SOURCE_DIM)
            .map(|(width, height)| u64::from(width) * u64::from(height))
            .and_then(netodia_artwork::media::image::image_decode::large_decode_guard);
    let decoded =
        netodia_artwork::media::image::image_decode::decode_memory_capped(bytes, MAX_SOURCE_DIM)?;
    let side = 72.min(decoded.width().max(decoded.height()));
    let thumbnail = netodia_artwork::media::image::image_decode::resize_rgb8(
        &decoded,
        side,
        side,
        netodia_artwork::media::image::image_decode::FilterType::Box,
    )?;
    let (width, height) = thumbnail.dimensions();
    let mut buffer = SharedPixelBuffer::<Rgb8Pixel>::new(width, height);
    buffer.make_mut_bytes().copy_from_slice(thumbnail.as_raw());
    Some(buffer)
}
