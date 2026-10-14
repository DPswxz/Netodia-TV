//! Joins typed `NetEase` stream tickets to the native decoder and output engine.

use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use netodia_audio::player::source::audio::AudioSource;
use netodia_audio::player::source::buffered::BufferedAudioSource;
use netodia_audio::player::source::file_decode::FileDecoder;
use netodia_core::entities::cloud::{Account, Quality, Song};
use netodia_core::entities::track::TrackSummary;
use netodia_core::error::{AppError, AppResult};
use netodia_engine::player::engine::backend::PlaybackEngine;
use netodia_engine::player::engine::event_sink::PlayerSinks;
use netodia_engine::player::engine::state::{PlayerStateHandle, lock_state, with_state_emit};
use netodia_engine::player::engine::types::PlaybackStatus;
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
    http: reqwest::Client,
    artwork_dir: PathBuf,
    engine: Arc<PlaybackEngine>,
    player_state: Arc<PlayerStateHandle>,
    sinks: Arc<PlayerSinks>,
}

impl CloudPlayback {
    /// Build the shared API and playback handles from the one HTTP client and artwork cache root.
    pub async fn initialize(
        http: reqwest::Client,
        artwork_dir: PathBuf,
        engine: Arc<PlaybackEngine>,
        player_state: Arc<PlayerStateHandle>,
        sinks: Arc<PlayerSinks>,
    ) -> (CloudService, Self) {
        let cloud = CloudService::new(http.clone()).await;
        let playback = Self::new(cloud.clone(), http, artwork_dir, engine, player_state, sinks);
        (cloud, playback)
    }

    pub fn new(
        cloud: CloudService,
        http: reqwest::Client,
        artwork_dir: PathBuf,
        engine: Arc<PlaybackEngine>,
        player_state: Arc<PlayerStateHandle>,
        sinks: Arc<PlayerSinks>,
    ) -> Self {
        Self { cloud, http, artwork_dir, engine, player_state, sinks }
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
        self.play_reserved(song_id, quality, volume, speed, start_position, generation, false).await
    }

    /// Re-open a cloud stream at a new position. Cloud sources cannot use the local-file seek
    /// action, so seeking is a new range-backed decoder generation. The current-track check and
    /// generation reservation happen before the first await, preventing a delayed slider task
    /// from taking over a song selected after the user dragged it.
    pub async fn seek(
        &self,
        song_id: i64,
        quality: Quality,
        position: Duration,
    ) -> AppResult<CloudPlaybackResult> {
        let (generation, volume, speed, was_paused, position) = {
            let _exec = self.player_state.lock_exec();
            let state = lock_state(&self.player_state);
            let Some(track) = state.source.as_ref() else {
                return Ok(CloudPlaybackResult { started: false, trial: false });
            };
            if track.id != song_id || !track.file_path.is_empty() {
                return Ok(CloudPlaybackResult { started: false, trial: false });
            }
            if !matches!(state.status, PlaybackStatus::Playing | PlaybackStatus::Paused) {
                return Ok(CloudPlaybackResult { started: false, trial: false });
            }
            let volume = state.effective_volume();
            let speed = state.playback_speed;
            let was_paused = state.status == PlaybackStatus::Paused;
            let requested_ms = u64::try_from(position.as_millis()).unwrap_or(u64::MAX);
            let position =
                Duration::from_millis(requested_ms.min(state.duration_ms.saturating_sub(500)));
            drop(state);
            let generation = self.engine.reserve_playback_request();
            (generation, volume, speed, was_paused, position)
        };
        self.play_reserved(song_id, quality, volume, speed, Some(position), generation, was_paused)
            .await
    }

    async fn play_reserved(
        &self,
        song_id: i64,
        quality: Quality,
        volume: f64,
        speed: f64,
        start_position: Option<Duration>,
        generation: u64,
        keep_paused: bool,
    ) -> AppResult<CloudPlaybackResult> {
        let cancellation = self.cloud.account_tasks_token();
        let song_ids = [song_id];
        let (ticket, songs) = tokio::try_join!(
            self.cloud.stream_ticket(song_id, quality),
            self.cloud.songs(&song_ids),
        )?;
        if cancellation.is_cancelled() {
            return Err(AppError::network_msg("网易云播放请求已取消"));
        }
        let cached_artwork = lock_state(&self.player_state)
            .source
            .as_ref()
            .filter(|current| current.id == song_id && current.file_path.is_empty())
            .and_then(|current| current.artwork_path.clone());
        let song = songs
            .into_iter()
            .find(|song| song.id == song_id)
            .ok_or_else(|| AppError::network_msg("网易云未返回歌曲信息"))?;
        let cover_url = song.album.cover_url.clone();
        let summary = Arc::new(track_summary(song, cached_artwork));
        let should_fetch_cover = summary.artwork_path.is_none() && cover_url.is_some();

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

        // Serialize the deck handoff and the player-state publication together with local
        // transport actions. Otherwise the audio can start while the player bar still has no
        // current track, or an older network request can publish after a newer local selection.
        let _exec = self.player_state.lock_exec();
        let started = self.engine.play_prepared_source(
            generation,
            source,
            volume,
            speed,
            start_position,
            TrackReplayGain::default(),
        );
        if started {
            if keep_paused {
                self.engine.pause();
            }
            let duration_ms = u64::try_from(summary.duration_ms.max(0)).unwrap_or(0);
            let position_ms = u64::try_from(start.as_millis()).unwrap_or(u64::MAX);
            with_state_emit(&self.player_state, &self.sinks, |state| {
                // Cloud queue entries have no durable local path. Keep the currently playing
                // identity in `source` for the player bar and OS media metadata, and clear a
                // previous local queue so Next cannot try to open a local file for this song.
                state.queue.clear();
                state.source = Some(summary);
                state.status =
                    if keep_paused { PlaybackStatus::Paused } else { PlaybackStatus::Playing };
                state.duration_ms = duration_ms;
                state.position_ms = position_ms.min(duration_ms.saturating_sub(500));
            });
        }
        if started
            && should_fetch_cover
            && let Some(url) = cover_url
        {
            self.cache_cover_after_start(song_id, url);
        }
        Ok(CloudPlaybackResult { started, trial })
    }

    /// Cover traffic is detached from the audio handoff, so a slow image CDN cannot delay the
    /// first PCM samples. The updated summary is emitted after caching and decoded by the normal
    /// Now Playing artwork pipeline.
    fn cache_cover_after_start(&self, song_id: i64, url: String) {
        let http = self.http.clone();
        let artwork_dir = self.artwork_dir.clone();
        let player_state = self.player_state.clone();
        let sinks = self.sinks.clone();
        tokio::spawn(async move {
            let Some(path) = download_cover(&http, &url, &artwork_dir).await else { return };
            let _exec = player_state.lock_exec();
            with_state_emit(&player_state, &sinks, |state| {
                let Some(current) = state.source.as_ref() else { return };
                if current.id != song_id
                    || !current.file_path.is_empty()
                    || current.artwork_path.is_some()
                {
                    return;
                }
                let mut updated = (**current).clone();
                updated.artwork_path = Some(path);
                state.source = Some(Arc::new(updated));
            });
        });
    }
}

async fn download_cover(
    http: &reqwest::Client,
    url: &str,
    dir: &std::path::Path,
) -> Option<String> {
    const MAX_BYTES: u64 = 1_048_576;
    if !matches!(reqwest::Url::parse(url).ok()?.scheme(), "https" | "http") {
        return None;
    }
    let response = http.get(url).send().await.ok()?.error_for_status().ok()?;
    if response.content_length().is_some_and(|length| length > MAX_BYTES) {
        return None;
    }
    let bytes = response.bytes().await.ok()?;
    if u64::try_from(bytes.len()).ok()? > MAX_BYTES {
        return None;
    }
    let bytes = bytes.to_vec();
    let dir = dir.to_path_buf();
    tokio::task::spawn_blocking(move || {
        netodia_artwork::media::image::artwork::store_image(&bytes, "jpg", &dir)
    })
    .await
    .ok()
    .flatten()
}

fn track_summary(song: Song, artwork_path: Option<String>) -> TrackSummary {
    let artist = song.artist_names();
    TrackSummary {
        id: song.id,
        file_path: String::new(),
        file_name: song.name.clone(),
        title: song.name,
        artist: (!artist.is_empty()).then_some(artist),
        album: (!song.album.name.is_empty()).then_some(song.album.name),
        duration_ms: i64::try_from(song.duration_ms).unwrap_or(i64::MAX),
        artwork_path,
        track_number: None,
        disc_number: None,
        last_position: 0,
        is_favorite: false,
        rating: 0,
        replaygain_track_gain: None,
        replaygain_track_peak: None,
        replaygain_album_gain: None,
        replaygain_album_peak: None,
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
    use super::{decoder_extension, open_buffered_source, track_summary};
    use netodia_audio::player::source::audio::AudioSource;
    use netodia_core::entities::cloud::{Album, Artist, Song};
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
    fn cloud_track_summary_keeps_the_metadata_the_player_bar_renders() {
        let summary = track_summary(
            Song {
                id: 812,
                name: "Cloud song".to_owned(),
                artists: vec![Artist {
                    id: 91,
                    name: "Cloud artist".to_owned(),
                    ..Artist::default()
                }],
                album: Album { id: 77, name: "Cloud album".to_owned(), ..Album::default() },
                duration_ms: 181_000,
                ..Song::default()
            },
            Some("/cache/cover.jpg".to_owned()),
        );

        assert_eq!(summary.id, 812);
        assert_eq!(summary.title, "Cloud song");
        assert_eq!(summary.artist.as_deref(), Some("Cloud artist"));
        assert_eq!(summary.album.as_deref(), Some("Cloud album"));
        assert_eq!(summary.duration_ms, 181_000);
        assert_eq!(summary.artwork_path.as_deref(), Some("/cache/cover.jpg"));
        assert!(summary.file_path.is_empty());
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
