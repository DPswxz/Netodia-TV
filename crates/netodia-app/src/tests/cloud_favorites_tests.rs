use super::*;
use netodia_core::entities::cloud::Song;

fn songs() -> Vec<LikedSong> {
    vec![LikedSong {
        song: Song { id: 3_000_000_001, name: "Song".to_owned(), ..Song::default() },
        favorite_order: 1,
        added_at_ms: None,
    }]
}

fn service(path: std::path::PathBuf) -> CloudService {
    let mut service = super::tests::service_for_test();
    service.favorites_cache = Arc::new(Mutex::new(FavoritesCache::new(path)));
    service.status.send_modify(|status| {
        status.account = Some(Account { id: 42, nickname: "Account".to_owned(), avatar_url: None });
        status.generation = 7;
    });
    service
}

#[tokio::test]
async fn a_new_service_restores_only_the_signed_in_accounts_rows() -> AppResult<()> {
    let dir = tempfile::tempdir()?;
    let path = dir.path().join("favorites.json");
    service(path.clone()).persist_liked_songs(42, 7, 0, &songs()).await;
    let restarted = service(path);

    assert_eq!(restarted.cached_liked_songs().await, Some(songs()));
    restarted.status.send_modify(|status| status.account = None);
    assert_eq!(restarted.cached_liked_songs().await, None);
    Ok(())
}

#[tokio::test]
async fn an_old_account_fetch_cannot_overwrite_a_new_snapshot() -> AppResult<()> {
    let dir = tempfile::tempdir()?;
    let service = service(dir.path().join("favorites.json"));
    service.persist_liked_songs(42, 7, 0, &songs()).await;
    service.status.send_modify(|status| status.generation = 8);

    service.persist_liked_songs(42, 7, 0, &[]).await;

    assert_eq!(service.cached_liked_songs().await, Some(songs()));
    Ok(())
}

#[tokio::test]
async fn a_fetch_from_before_a_like_cannot_resurrect_invalidated_rows() -> AppResult<()> {
    let dir = tempfile::tempdir()?;
    let service = service(dir.path().join("favorites.json"));
    service.persist_liked_songs(42, 7, 0, &songs()).await;
    service.liked_ids_revision.fetch_add(1, Ordering::AcqRel);
    service.clear_liked_songs_cache(7).await;

    service.persist_liked_songs(42, 7, 0, &songs()).await;

    assert_eq!(service.cached_liked_songs().await, None);
    Ok(())
}

#[tokio::test]
async fn delayed_old_account_cleanup_does_not_remove_new_account_data() -> AppResult<()> {
    let dir = tempfile::tempdir()?;
    let service = service(dir.path().join("favorites.json"));
    service.persist_liked_songs(42, 7, 0, &songs()).await;
    service.status.send_modify(|status| status.generation = 8);

    service.clear_liked_songs_cache(7).await;

    assert_eq!(service.cached_liked_songs().await, Some(songs()));
    Ok(())
}
