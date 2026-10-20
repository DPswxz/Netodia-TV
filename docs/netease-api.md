# Netodia direct NetEase API

Protocol reference: [api-enhanced](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/tree/2db6684453297ee86dec0492842f2320b08182d9), commit `2db6684453297ee86dec0492842f2320b08182d9`.
The endpoint registry is `netodia-net::netease::endpoints`. YesPlayMusic's wrapper routes are not wire URLs.

| Capability | Wire path | Protocol | Result |
| --- | --- | --- | --- |
| QR login | `/api/login/qrcode/{unikey,client/login}` | EAPI | key, QR state; direct desktop EAPI context, without anonymous-device registration |
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
| Listening history | `https://clientlog.music.163.com/eapi/feedback/weblog` | EAPI | sequential `startplay`, `play` acknowledgements |
| Saved content | `/api/{user/playlist,album/sublist,artist/sublist}` | WEAPI | entity lists |

XEAPI bootstraps its public key from `/api/bsr/sk/get`, verifies the response HMAC,
then wraps a random AES key with ephemeral X25519 and AES-GCM. Guest sessions
use `/api/register/anonimous`. Credentials are attached only to NetEase API calls,
never to a returned CDN address. Safe reads receive at most two attempts; writes
are never automatically retried. Session generations reject stale responses.

Listening reporting follows API Enhanced's [scrobble module](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/main/module/scrobble.js)
and the local YesPlayMusic `src/electron/scrobble.js`: use the clientlog host, sign
`/api/feedback/weblog` with EAPI, and use its macOS desktop reporting profile.
The ordinary music/WEAPI feedback endpoint is not used for listening history.

`netodia-app::tasks::netease_listening` samples the existing player state every 500 ms.
Only signed-in cloud songs with real position advancement count; local database IDs,
pause/buffering time, seek jumps and system suspension do not. A successful cloud-track
start carries a listening generation so repeating the same song creates a new listen,
while seeking or changing quality keeps the current listen. Time is measured in actual
seconds at the selected playback speed, rather than the absolute track position.
On track change, stop, or clean exit the tap queues one `startplay` / `play` pair,
with unknown source ID `0`. Pausing retains the session until it resumes or ends.
The API facade rejects queued records from a previous account generation and clears its
recent-song cache after successful reporting. Failures only produce a sanitized log warning.

Reporting is best effort: sampling can miss sub-second boundaries, listens shorter than
one second are ignored, and the in-memory queue holds at most 32 completed listens.
Writes are not retried to avoid duplicate counts after ambiguous timeouts. Exit allows
two seconds for the queue to drain; crashes, offline exits and queue overflow can lose
pending reports. A successful acknowledgement confirms receipt, not immediate inclusion
in every NetEase statistic; account totals/footprints require verification in the official
client after real playback and remain subject to the service's counting rules.

QR login follows the YesPlayMusic/API Enhanced request defaults: it requests a QR key
directly over EAPI with the desktop cookie profile and iPhone user agent. It must not
register an anonymous XEAPI session before creating the QR key; that changes the device
context and can trigger the phone's environment-risk rejection.

`netodia-app::cloud::CloudService` owns login and account transitions. Successful
sessions store only the NetEase cookie and device identifier in the OS credential
store; passwords are never persisted. When the system store is unavailable, login
works for the current process and the UI receives a notice. Startup restoration
retries transient network failures and removes credentials only after the server
rejects the session. Account transitions cancel the prior account task token.

Run unit tests with `cargo test --locked -p netodia-net --lib`.
Run session state checks with `cargo test --locked -p netodia-app --lib cloud::tests`.
Run the public protocol probe with `cargo run --locked -p netodia-net --example netease_probe`.
The probe prints counts and permission status, never cookies or signed URLs.
Password/QR account validation remains a manual release check with a user's own account.
