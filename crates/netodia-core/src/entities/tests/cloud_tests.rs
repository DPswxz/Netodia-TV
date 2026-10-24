use super::{Album, Artist, Song};
use serde_json::json;

#[test]
fn search_and_detail_song_shapes_share_identity() -> Result<(), serde_json::Error> {
    let song: Song = serde_json::from_str(
        r#"{"id":123,"name":"Song","artists":[{"id":1,"name":"Artist"}],"album":{"id":2,"name":"Album"},"duration":1000}"#,
    )?;
    assert_eq!(song.id, 123);
    assert_eq!(song.artist_names(), "Artist");
    assert_eq!(song.album.name, "Album");
    assert_eq!(song.duration_ms, 1000);
    Ok(())
}

#[test]
fn queue_metadata_contains_no_media_url() -> Result<(), serde_json::Error> {
    let song = Song { id: 9, name: "Track".into(), ..Song::default() };
    let json = serde_json::to_string(&song)?;
    assert!(!json.contains("file_path"));
    assert!(!json.contains("https:"));
    Ok(())
}

#[test]
fn null_artist_and_album_names_do_not_reject_a_song_batch() -> Result<(), serde_json::Error> {
    let songs: Vec<Song> = serde_json::from_value(json!([
        {"id": 41, "name": "Before"},
        {
            "id": 42, "name": "Unavailable metadata",
            "ar": [{"id": 0, "name": null}],
            "al": {"id": 0, "name": null, "picUrl": null}
        },
        {"id": 43, "name": "After"}
    ]))?;

    assert_eq!(songs.iter().map(|song| song.id).collect::<Vec<_>>(), [41, 42, 43]);
    assert_eq!(songs[1].artists[0].id, 0);
    assert_eq!(songs[1].artist_names(), "");
    assert_eq!(songs[1].album.id, 0);
    assert_eq!(songs[1].album.name, "");
    assert_eq!(songs[1].album.cover_url, None);
    Ok(())
}

#[test]
fn non_string_artist_names_remain_invalid() {
    assert!(serde_json::from_value::<Artist>(json!({"id": 1, "name": 42})).is_err());
}

#[test]
fn non_string_album_names_remain_invalid() {
    assert!(serde_json::from_value::<Album>(json!({"id": 1, "name": []})).is_err());
}

#[test]
fn missing_artist_identity_remains_invalid() {
    assert!(serde_json::from_value::<Artist>(json!({"name": null})).is_err());
}
