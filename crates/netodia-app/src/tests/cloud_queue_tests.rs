use std::sync::Arc;

use super::{queue_cloud_tracks, track_summary};
use netodia_core::entities::cloud::Song;
use netodia_core::entities::track::TrackSummary;
use netodia_engine::player::engine::queue::QueueState;

fn track(id: i64) -> Arc<TrackSummary> {
    Arc::new(track_summary(Song { id, name: format!("Song {id}"), ..Song::default() }, None, false))
}

fn ids(queue: &QueueState) -> Vec<i64> {
    queue.tracks_in_play_order().iter().map(|track| track.id).collect()
}

#[test]
fn play_next_preserves_large_ids_and_the_batch_order() {
    let mut queue = QueueState::default();
    queue.add_tracks(vec![track(19), track(20)]);
    queue.current_index = Some(0);

    queue_cloud_tracks(&mut queue, vec![track(2_147_483_648), track(3_000_000_000)], true);

    assert_eq!(ids(&queue), [19, 2_147_483_648, 3_000_000_000, 20]);
    assert_eq!(queue.current_index, Some(0));
}

#[test]
fn add_to_queue_appends_without_replacing_the_current_track() {
    let mut queue = QueueState::default();
    queue.add_tracks(vec![track(19), track(20)]);
    queue.current_index = Some(0);

    queue_cloud_tracks(&mut queue, vec![track(2_147_483_648), track(3_000_000_000)], false);

    assert_eq!(ids(&queue), [19, 20, 2_147_483_648, 3_000_000_000]);
    assert_eq!(queue.current_index, Some(0));
}

#[test]
fn play_next_on_an_empty_queue_keeps_the_exact_ids() {
    let mut queue = QueueState::default();

    queue_cloud_tracks(&mut queue, vec![track(2_147_483_648), track(3_000_000_000)], true);

    assert_eq!(ids(&queue), [2_147_483_648, 3_000_000_000]);
    assert_eq!(queue.current_index, None);
}
