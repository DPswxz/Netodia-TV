# Netodia direct NetEase API

Protocol reference: [api-enhanced](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/tree/2db6684453297ee86dec0492842f2320b08182d9), commit `2db6684453297ee86dec0492842f2320b08182d9`.
The endpoint registry is `netodia-net::netease::endpoints`. YesPlayMusic's wrapper routes are not wire URLs.

| Capability | Wire path | Protocol | Result |
| --- | --- | --- | --- |
| QR login | `/api/login/qrcode/{unikey,client/login}` | EAPI | key, QR state |
| Phone / email | `/api/w/login{,/cellphone}` | WEAPI / EAPI | session, Account |
| Account | `/api/nuser/account/get` | WEAPI | Account |
| Discovery / charts | `/api/personalized/playlist`, `/api/toplist` | WEAPI / EAPI | Playlist list |
| Search | `/api/cloudsearch/pc` | EAPI | SearchResult |
| Playlist | `/api/v6/playlist/detail` | EAPI | Playlist, ordered songs |
| Song detail | `/api/v3/song/detail` | WEAPI | songs, privileges |
| Album / artist | `/api/v1/{album,artist}/{id}` | WEAPI | detail, songs |
| Playback | `/api/song/enhance/player/url/v1` | XEAPI | transient StreamTicket |
| Lyrics | `/api/song/lyric` | EAPI | original, translation, romanization |
| Daily / FM | `/api/v3/discovery/recommend/songs`, `/api/v1/radio/get` | WEAPI | songs |
| Favorites | `/api/song/like/get`, `/api/radio/like` | WEAPI | IDs, acknowledgement |
| Saved content | `/api/{user/playlist,album/sublist,artist/sublist}` | WEAPI | entity lists |

XEAPI bootstraps its public key from `/api/bsr/sk/get`, verifies the response HMAC,
then wraps a random AES key with ephemeral X25519 and AES-GCM. Guest sessions
use `/api/register/anonimous`. Credentials are attached only to NetEase API calls,
never to a returned CDN address. Safe reads receive at most two attempts; writes
are never automatically retried. Session generations reject stale responses.

Run unit tests with `cargo test --locked -p netodia-net --lib`.
Run the public protocol probe with `cargo run --locked -p netodia-net --example netease_probe`.
The probe prints counts and permission status, never cookies or signed URLs.
Password/QR account validation remains a manual release check with a user's own account.
