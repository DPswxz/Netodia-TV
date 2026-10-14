//! Both directions of `queue.json`: the snapshot a save writes, and the restore that seats it back
//! at boot.

use std::sync::Arc;

use super::{PlayerAction, PlayerState};
use crate::player::engine::queue::current_index_from_i32;
use crate::player::engine::types::{PersistableQueue, PersistedPlayback};
use melodia_core::entities::track::TrackSummary;

impl PlayerState {
    /// The snapshot `queue.json` holds, for the two save sites to write.
    ///
    pub fn to_persisted(&self) -> PersistedPlayback {
        PersistedPlayback { queue: self.queue.to_persistable() }
    }
}

/// Restore queue from persisted data. Called at startup via
/// `library::queue::restore_persisted_playback`.
///
/// `shuffle_enabled` and `repeat_mode` are user preferences and live in
/// `settings.json`, not `queue.json` — the caller is responsible for
/// hydrating them. Note that `original_order` is not persisted, so a
/// caller restoring a non-empty queue should also force shuffle off.
pub fn restore_queue(
    state: &mut PlayerState,
    tracks: Vec<Arc<TrackSummary>>,
    persistable: &PersistableQueue,
) {
    let len = tracks.len();
    state.queue.tracks = tracks;
    state.queue.play_order = (0..len).collect();
    state.queue.original_order = (0..len).collect();
    state.queue.current_index = current_index_from_i32(persistable.current_index);

    if let Some(track) = state.queue.get_current().cloned() {
        state.duration_ms = u64::try_from(track.duration_ms.max(0)).unwrap_or(0);
        state.position_ms = u64::try_from(track.last_position.max(0)).unwrap_or(0);
        state.source = Some(track);
    }
}
