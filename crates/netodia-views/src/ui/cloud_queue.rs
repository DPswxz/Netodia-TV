//! Queue projections for cloud rows whose metadata is already loaded.

use netodia_core::entities::track::{TrackListRow, TrackSummary};

pub(super) fn track_summary(row: &TrackListRow) -> TrackSummary {
    TrackSummary {
        id: row.id,
        file_path: String::new(),
        file_name: row.file_name.clone(),
        title: row.title.clone(),
        artist: row.artist.clone(),
        album: row.album.clone(),
        duration_ms: row.duration_ms,
        artwork_path: row.artwork_path.clone(),
        track_number: row.track_number,
        disc_number: row.disc_number,
        last_position: 0,
        is_favorite: row.is_favorite,
        rating: row.rating,
        replaygain_track_gain: None,
        replaygain_track_peak: None,
        replaygain_album_gain: None,
        replaygain_album_peak: None,
    }
}
