//! `NetEase` identities and display data. Temporary CDN URLs never belong in persisted queues.

use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Artist {
    pub id: i64,
    pub name: String,
    #[serde(default, rename = "picUrl")]
    pub cover_url: Option<String>,
    #[serde(default, rename = "img1v1Url")]
    pub alternate_cover_url: Option<String>,
}

impl Artist {
    pub fn image_url(&self) -> Option<&str> {
        self.cover_url.as_deref().or(self.alternate_cover_url.as_deref())
    }
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Album {
    pub id: i64,
    pub name: String,
    #[serde(default, rename = "picUrl")]
    pub cover_url: Option<String>,
    #[serde(default)]
    pub artists: Vec<Artist>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Song {
    pub id: i64,
    pub name: String,
    #[serde(default, rename = "ar", alias = "artists")]
    pub artists: Vec<Artist>,
    #[serde(default, rename = "al", alias = "album")]
    pub album: Album,
    #[serde(default, rename = "dt", alias = "duration")]
    pub duration_ms: u64,
    #[serde(default)]
    pub fee: i32,
    #[serde(default)]
    pub privilege: Option<Privilege>,
}

impl Song {
    pub fn artist_names(&self) -> String {
        self.artists.iter().map(|a| a.name.as_str()).collect::<Vec<_>>().join(" / ")
    }
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Privilege {
    pub id: i64,
    #[serde(default)]
    pub st: i32,
    #[serde(default)]
    pub pl: i64,
    #[serde(default)]
    pub fee: i32,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Playlist {
    pub id: i64,
    pub name: String,
    #[serde(default, rename = "coverImgUrl", alias = "picUrl")]
    pub cover_url: Option<String>,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default, rename = "trackCount")]
    pub track_count: u64,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Account {
    #[serde(rename = "userId")]
    pub id: i64,
    pub nickname: String,
    #[serde(default, rename = "avatarUrl")]
    pub avatar_url: Option<String>,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Quality {
    Standard,
    #[default]
    Exhigh,
    Lossless,
    Hires,
}

impl Quality {
    pub const fn level(self) -> &'static str {
        match self {
            Self::Standard => "standard",
            Self::Exhigh => "exhigh",
            Self::Lossless => "lossless",
            Self::Hires => "hires",
        }
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum SearchKind {
    #[default]
    Songs,
    Albums,
    Artists,
    Playlists,
}

impl SearchKind {
    pub const fn code(self) -> u32 {
        match self {
            Self::Songs => 1,
            Self::Albums => 10,
            Self::Artists => 100,
            Self::Playlists => 1000,
        }
    }
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct SearchResult {
    #[serde(default)]
    pub songs: Vec<Song>,
    #[serde(default)]
    pub albums: Vec<Album>,
    #[serde(default)]
    pub artists: Vec<Artist>,
    #[serde(default)]
    pub playlists: Vec<Playlist>,
    #[serde(default, rename = "songCount")]
    pub song_count: u64,
    #[serde(default, rename = "albumCount")]
    pub album_count: u64,
    #[serde(default, rename = "artistCount")]
    pub artist_count: u64,
    #[serde(default, rename = "playlistCount")]
    pub playlist_count: u64,
}

impl SearchResult {
    pub const fn total(&self, kind: SearchKind) -> u64 {
        match kind {
            SearchKind::Songs => self.song_count,
            SearchKind::Albums => self.album_count,
            SearchKind::Artists => self.artist_count,
            SearchKind::Playlists => self.playlist_count,
        }
    }
}

/// Transient media URL plus the server's permission verdict. Never serialized.
#[derive(Clone)]
pub struct StreamTicket {
    pub song_id: i64,
    pub url: String,
    pub format: String,
    pub expires_after_seconds: u64,
    pub trial: bool,
    pub size: Option<u64>,
}

#[derive(Clone, Debug, Default)]
pub struct Lyrics {
    pub original: String,
    pub translation: String,
    pub romanization: String,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn search_and_detail_song_shapes_share_identity() -> Result<(), serde_json::Error> {
        let song: Song = serde_json::from_str(
            r#"{"id":123,"name":"Song","artists":[{"id":1,"name":"Artist"}],"album":{"id":2,"name":"Album"},"duration":1000}"#,
        )?;
        assert_eq!(song.id, 123);
        assert_eq!(song.artist_names(), "Artist");
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
}
