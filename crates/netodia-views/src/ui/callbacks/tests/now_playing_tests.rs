use super::favorite_target;
use netodia_core::entities::track::TrackSummary;

fn cloud_track(id: i64) -> TrackSummary {
    TrackSummary {
        id,
        file_path: String::new(),
        file_name: String::new(),
        title: "Song".into(),
        artist: None,
        album: None,
        duration_ms: 180_000,
        artwork_path: None,
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

#[test]
fn local_file_with_a_historical_favorite_flag_cannot_be_toggled() {
    let mut local = cloud_track(4_000_000_000);
    local.file_path = "local-song.flac".into();
    local.is_favorite = true;
    assert_eq!(favorite_target(&local), None);
}

#[test]
fn cloud_song_uses_its_exact_id_in_both_favorite_directions() {
    let mut cloud = cloud_track(4_000_000_000);
    assert_eq!(favorite_target(&cloud), Some((4_000_000_000, true)));
    cloud.is_favorite = true;
    assert_eq!(favorite_target(&cloud), Some((4_000_000_000, false)));
}

#[test]
fn missing_or_synthetic_id_is_not_a_cloud_favorite_target() {
    assert_eq!(favorite_target(&cloud_track(0)), None);
    assert_eq!(favorite_target(&cloud_track(-1)), None);
}
