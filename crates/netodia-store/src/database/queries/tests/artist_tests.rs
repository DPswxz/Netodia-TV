use crate::database::queries;
#[allow(clippy::wildcard_imports)]
use crate::database::queries::fixtures::*;
use netodia_core::entities::artist::FavoriteArtist;
use netodia_core::error::AppError;

#[tokio::test]
async fn get_all_artists_excludes_empty() -> Result<(), AppError> {
    let db = setup_seeded_db().await?;
    let artists = queries::artist::get_all_artists(&db).await?;
    // artist_stats filters out artists with track_count = 0,
    // so "Unknown Artist" (sentinel, no tracks) is excluded.
    // Should include "Artist A" + "Artist B" from seeded data.
    assert_eq!(artists.len(), 2);
    assert!(artists.iter().any(|a| a.name == "Artist A"));
    assert!(artists.iter().any(|a| a.name == "Artist B"));
    assert!(!artists.iter().any(|a| a.name == "Unknown Artist"));
    Ok(())
}

#[tokio::test]
async fn get_all_artists_sorted_by_name() -> Result<(), AppError> {
    let db = setup_seeded_db().await?;
    let artists = queries::artist::get_all_artists(&db).await?;
    let names: Vec<&str> = artists.iter().map(|a| a.name.as_str()).collect();
    let mut sorted = names.clone();
    sorted.sort_unstable();
    assert_eq!(names, sorted);
    Ok(())
}

#[tokio::test]
async fn get_artist_by_id_happy_path() -> Result<(), AppError> {
    let db = setup_seeded_db().await?;
    let artists = queries::artist::get_all_artists(&db).await?;
    let artist = queries::artist::get_artist_by_id(&db, artists[0].id).await?;
    assert_eq!(artist.name, artists[0].name);
    Ok(())
}

#[tokio::test]
async fn get_artist_by_id_not_found() -> Result<(), AppError> {
    let db = setup_seeded_db().await?;
    let result = queries::artist::get_artist_by_id(&db, 99999).await;
    assert!(result.is_err());
    Ok(())
}

#[tokio::test]
async fn get_artists_without_images() -> Result<(), AppError> {
    let db = setup_seeded_db().await?;
    let artists = queries::artist::get_artists_without_images(&db).await?;
    // The seed's two, and not the schema's Unknown Artist sentinel, which has no image either:
    // a directory search for its name answers with a stranger's photo.
    assert_eq!(artists.len(), 2);
    assert!(
        artists.iter().all(|artist| artist.id != queries::artist::UNKNOWN_ARTIST_ID),
        "the placeholder must never be looked up"
    );
    Ok(())
}

#[tokio::test]
async fn update_artist_image_path() -> Result<(), AppError> {
    let db = setup_seeded_db().await?;
    let artists = queries::artist::get_all_artists(&db).await?;
    let artist = artists
        .iter()
        .find(|a| a.name == "Artist A")
        .ok_or_else(|| AppError::Validation("Artist A missing".into()))?;

    queries::artist::update_artist_image_path(&db, artist.id, "/images/artist_a.jpg").await?;

    let updated = queries::artist::get_artist_by_id(&db, artist.id).await?;
    assert_eq!(updated.image_path.as_deref(), Some("/images/artist_a.jpg"));

    // Should no longer appear in "without images"
    let without = queries::artist::get_artists_without_images(&db).await?;
    assert!(!without.iter().any(|a| a.id == artist.id));
    Ok(())
}

/// The count arrives through a `COUNT(t.id) AS favorite_count` alias, and that is the half of
/// this projection only a real fetch can check: `FavoriteArtist` matches columns by name, so a
/// renamed alias fails at fetch time and nowhere earlier.
///
/// One artist favorited and one not, because the `JOIN … AND t.is_favorite = TRUE` is what
/// keeps the untouched artist out; a fixture where everything is favorited passes with the
/// condition deleted.
#[tokio::test]
async fn a_favorited_artist_carries_the_count_of_its_favorited_tracks() -> Result<(), AppError> {
    let db = setup_seeded_db().await?;
    let artists = queries::artist::get_all_artists(&db).await?;
    let artist_a = artists
        .iter()
        .find(|a| a.name == "Artist A")
        .ok_or_else(|| AppError::Validation("Artist A missing".into()))?;

    // Both of Artist A's tracks, and neither of Artist B's.
    let ids: Vec<i64> =
        sqlx::query_scalar("SELECT id FROM tracks WHERE title IN ('Alpha Song', 'Gamma Song')")
            .fetch_all(db.read())
            .await?;
    queries::track::set_favorite(&db, &ids, true).await?;

    let favorites = queries::artist::get_favorite_artists(&db).await?;

    assert_eq!(
        favorites,
        vec![FavoriteArtist {
            id: artist_a.id,
            name: "Artist A".to_owned(),
            image_path: None,
            favorite_count: 2,
        }]
    );
    Ok(())
}

#[tokio::test]
async fn a_library_with_no_favorites_has_no_favorite_artists() -> Result<(), AppError> {
    let db = setup_seeded_db().await?;
    assert!(queries::artist::get_favorite_artists(&db).await?.is_empty());
    Ok(())
}

#[tokio::test]
async fn track_artist_choices_follow_credit_order_instead_of_name_order() -> Result<(), AppError> {
    use netodia_core::entities::artist::ArtistCredit;

    let db = crate::database::DbPool::test_pool().await?;
    let mut meta = make_test_metadata("Duet");
    meta.artist = ArtistCredit::from_tags("Zed feat. Amy", &["Zed".to_owned(), "Amy".to_owned()]);
    meta.album_artist = ArtistCredit::from_name("Album artist");
    let path = std::path::Path::new("music").join("duet.mp3");
    let id = insert_tagged_track(&db, &path.to_string_lossy(), &meta).await?;

    let artists = queries::artist::get_track_artists(&db, id).await?;

    assert_eq!(
        artists.iter().map(|artist| artist.name.as_str()).collect::<Vec<_>>(),
        ["Zed", "Amy"]
    );
    Ok(())
}

#[tokio::test]
async fn untagged_tracks_keep_their_primary_artist_target() -> Result<(), AppError> {
    use netodia_core::entities::artist::ArtistCredit;

    let db = crate::database::DbPool::test_pool().await?;
    let mut meta = make_test_metadata("Untagged");
    meta.artist = ArtistCredit::default();
    let path = std::path::Path::new("music").join("untagged.mp3");
    let id = insert_tagged_track(&db, &path.to_string_lossy(), &meta).await?;

    let artists = queries::artist::get_track_artists(&db, id).await?;

    assert_eq!(
        artists.iter().map(|artist| artist.id).collect::<Vec<_>>(),
        [queries::artist::UNKNOWN_ARTIST_ID]
    );
    Ok(())
}

#[tokio::test]
async fn a_missing_track_has_no_artist_choices() -> Result<(), AppError> {
    let db = setup_seeded_db().await?;

    assert!(queries::artist::get_track_artists(&db, i64::MAX).await?.is_empty());
    Ok(())
}
