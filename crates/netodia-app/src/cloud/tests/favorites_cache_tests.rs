use super::*;
use netodia_core::entities::cloud::{Album, Song};

fn songs() -> Vec<LikedSong> {
    vec![
        LikedSong {
            song: Song {
                id: 3_000_000_001,
                name: "收藏歌曲".to_owned(),
                album: Album { name: "专辑".to_owned(), ..Album::default() },
                duration_ms: 123_456,
                ..Song::default()
            },
            favorite_order: 7,
            added_at_ms: Some(123_456_789),
        },
        LikedSong {
            song: Song { id: 2, name: "Second".to_owned(), ..Song::default() },
            favorite_order: 8,
            added_at_ms: None,
        },
    ]
}

#[test]
fn a_new_cache_instance_restores_metadata_and_server_order() -> AppResult<()> {
    let dir = tempfile::tempdir()?;
    let path = dir.path().join("favorites.json");
    FavoritesCache::new(path.clone()).store(42, &songs())?;

    assert_eq!(FavoritesCache::new(path).load(42)?, Some(songs()));
    Ok(())
}

#[test]
fn another_account_cannot_read_the_snapshot() -> AppResult<()> {
    let dir = tempfile::tempdir()?;
    let cache = FavoritesCache::new(dir.path().join("favorites.json"));
    cache.store(42, &songs())?;

    assert_eq!(cache.load(43)?, None);
    Ok(())
}

#[test]
fn empty_favorites_replace_the_previous_snapshot() -> AppResult<()> {
    let dir = tempfile::tempdir()?;
    let cache = FavoritesCache::new(dir.path().join("favorites.json"));
    cache.store(42, &songs())?;
    cache.store(42, &[])?;

    assert_eq!(cache.load(42)?, Some(Vec::new()));
    Ok(())
}

#[test]
fn clearing_is_idempotent_and_removes_private_rows() -> AppResult<()> {
    let dir = tempfile::tempdir()?;
    let cache = FavoritesCache::new(dir.path().join("favorites.json"));
    cache.store(42, &songs())?;
    cache.clear()?;
    cache.clear()?;

    assert_eq!(cache.load(42)?, None);
    Ok(())
}

#[test]
fn unknown_versions_and_invalid_identities_are_ignored() -> AppResult<()> {
    let dir = tempfile::tempdir()?;
    let path = dir.path().join("favorites.json");
    let cache = FavoritesCache::new(path.clone());
    let cases = [
        r#"{"version":2,"account_id":42,"songs":[]}"#,
        r#"{"version":1,"account_id":42,"songs":[{"song":{"id":0,"name":"bad"},"favorite_order":1,"added_at_ms":null}]}"#,
        r#"{"version":1,"account_id":42,"songs":[{"song":{"id":1,"name":"a"},"favorite_order":1,"added_at_ms":null},{"song":{"id":1,"name":"b"},"favorite_order":2,"added_at_ms":null}]}"#,
    ];
    for json in cases {
        std::fs::write(&path, json)?;
        assert_eq!(cache.load(42)?, None);
    }
    Ok(())
}

#[test]
fn corrupt_files_can_be_replaced_by_a_successful_refresh() -> AppResult<()> {
    let dir = tempfile::tempdir()?;
    let path = dir.path().join("favorites.json");
    std::fs::write(&path, b"{truncated")?;
    let cache = FavoritesCache::new(path);
    assert!(cache.load(42).is_err());

    cache.store(42, &songs())?;

    assert_eq!(cache.load(42)?, Some(songs()));
    Ok(())
}

#[test]
fn oversized_files_are_ignored_before_parsing() -> AppResult<()> {
    let dir = tempfile::tempdir()?;
    let path = dir.path().join("favorites.json");
    File::create(&path)?.set_len(MAX_BYTES + 1)?;

    assert_eq!(FavoritesCache::new(path).load(42)?, None);
    Ok(())
}

#[test]
fn a_failed_oversized_write_preserves_the_previous_snapshot() -> AppResult<()> {
    let dir = tempfile::tempdir()?;
    let cache = FavoritesCache::new(dir.path().join("favorites.json"));
    cache.store(42, &songs())?;
    let mut oversized = songs();
    oversized[0].song.name =
        "x".repeat(usize::try_from(MAX_BYTES).map_err(netodia_core::error::AppError::io_source)?);

    assert!(cache.store(42, &oversized).is_err());
    assert_eq!(cache.load(42)?, Some(songs()));
    Ok(())
}
