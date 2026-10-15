use super::{decode_created_playlists, decode_intelligence_songs, decode_saved_playlists};
use crate::netease::Result;
use serde_json::json;

#[test]
fn created_and_collected_pages_partition_account_playlists() -> Result<()> {
    let body = json!({"more": true, "playlist": [
        {"id": 3_136_952_023_i64, "name": "Created", "creator": {"userId": 55}},
        {"id": 4_000_000_000_i64, "name": "Collected", "creator": {"userId": 77}},
        {"id": 12, "name": "Unknown owner"}
    ]});
    let (created, created_more) = decode_created_playlists(&body, 55)?;
    let (collected, collected_more) = decode_saved_playlists(&body, 55)?;

    assert_eq!(created.iter().map(|playlist| playlist.id).collect::<Vec<_>>(), [3_136_952_023]);
    assert_eq!(
        collected.iter().map(|playlist| playlist.id).collect::<Vec<_>>(),
        [4_000_000_000, 12]
    );
    assert!(created_more);
    assert!(collected_more);
    Ok(())
}

#[test]
fn an_empty_filtered_page_does_not_hide_later_created_playlists() -> Result<()> {
    let body = json!({"more": true, "playlist": [{"id": 11, "name": "Collected", "creator": {"userId": 77}}]});
    let (created, more) = decode_created_playlists(&body, 55)?;
    assert!(created.is_empty());
    assert!(more);
    Ok(())
}

#[test]
fn intelligence_decodes_nested_songs_in_server_order() -> Result<()> {
    let songs = decode_intelligence_songs(&json!({"data": [
        {"id": 3_000_000_000_i64, "songInfo": {"id": 3_000_000_000_i64, "name": "First", "artists": [{"id": 8, "name": "Artist"}]}},
        {"id": 7, "songInfo": {"id": 7, "name": "Second"}}
    ]}))?;
    assert_eq!(songs.iter().map(|song| song.id).collect::<Vec<_>>(), [3_000_000_000, 7]);
    assert_eq!(songs[0].artist_names(), "Artist");
    Ok(())
}

#[test]
fn intelligence_rejects_an_entry_without_song_info() {
    assert!(decode_intelligence_songs(&json!({"data": [{"id": 7}]})).is_err());
}
