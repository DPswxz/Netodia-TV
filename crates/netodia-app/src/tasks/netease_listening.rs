//! Read-only listening tap. Sampling never waits for HTTP and never mutates the player.

use std::time::{Duration, Instant};

use netodia_core::error::describe;
use netodia_engine::player::engine::state::lock_state;
use netodia_engine::player::engine::types::PlaybackStatus;
use tokio::sync::mpsc;

use crate::cloud::CloudService;
use crate::state::AppState;
use crate::tasks::TaskSpawner;

mod tracker;
use tracker::{ListenKey, ListenReport, ListenSample, ListenTracker};

pub fn spawn(spawner: &TaskSpawner, state: &AppState) {
    // Bounded memory when offline. Reports are not retried because the service provides no
    // idempotency key; a timeout may mean the listen was already accepted.
    let (reports, mut pending) = mpsc::channel::<ListenReport>(32);
    let cloud = state.cloud.clone();
    spawner.spawn_cancellable(move |shutdown| async move {
        let worker = async {
            while let Some(report) = pending.recv().await {
                submit(&cloud, report).await;
            }
        };
        tokio::pin!(worker);
        tokio::select! {
            () = &mut worker => {},
            () = shutdown.cancelled() => {
                // The sampler flushes and drops its sender on the same shutdown signal.
                // Stay within the application's three-second task-drain budget.
                if tokio::time::timeout(Duration::from_secs(2), &mut worker).await.is_err() {
                    log::warn!("NetEase listening report did not finish before shutdown");
                }
            },
        }
    });

    let state = state.clone();
    spawner.spawn_cancellable(move |shutdown| async move {
        let mut tracker = ListenTracker::default();
        let mut last = Instant::now();
        let mut interval = tokio::time::interval(Duration::from_millis(500));
        interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        loop {
            let exiting = tokio::select! {
                biased;
                () = shutdown.cancelled() => true,
                _ = interval.tick() => false,
            };
            let now = Instant::now();
            if let Some(report) = tracker.observe(sample(&state), now.duration_since(last)) {
                enqueue(&reports, report);
            }
            last = now;
            if exiting {
                if let Some(report) = tracker.finish() {
                    enqueue(&reports, report);
                }
                break;
            }
        }
    });
}

fn sample(state: &AppState) -> ListenSample {
    let account = state.cloud.status();
    let player = lock_state(&state.player_state);
    let key = account.account.and_then(|_| {
        player
            .source
            .as_ref()
            .filter(|track| {
                track.id > 0
                    && track.file_path.is_empty()
                    && player.status != PlaybackStatus::Stopped
            })
            .map(|track| ListenKey {
                song_id: track.id,
                playback_generation: state.cloud_playback.listening_generation(),
                account_generation: account.generation,
            })
    });
    ListenSample {
        key,
        playing: player.status == PlaybackStatus::Playing,
        position_ms: player.position_ms,
        speed: player.playback_speed,
        decoder_generation: state.cloud_playback.decoder_generation(),
    }
}

fn enqueue(reports: &mpsc::Sender<ListenReport>, report: ListenReport) {
    if reports.try_send(report).is_err() {
        log::warn!("NetEase listening report queue is full or closed; dropping one report");
    }
}

async fn submit(cloud: &CloudService, report: ListenReport) {
    let status = cloud.status();
    if status.account.is_none() || status.generation != report.key.account_generation {
        return;
    }
    if let Err(error) =
        cloud.report_listen(report.key.song_id, report.seconds, report.key.account_generation).await
    {
        log::warn!("NetEase listening report: {}", describe(&error));
    }
}
