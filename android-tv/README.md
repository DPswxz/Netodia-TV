# Netodia TV (Android preview)

Native Android TV frontend reusing `netodia-net` and `netodia-core` through a Rust JNI bridge.
The desktop Slint frontend and desktop audio engine are unchanged. Android MediaPlayer,
AudioFocus, MediaSession and a foreground service provide playback on ARMv7 TVs.

## Current TV controls (0.5.33)

- Search has an icon search control, three-column history and a compact best-result card.
- Show All Songs uses icon actions and up to eight songs per page when space permits; playlist pages retain six.

- Playlist detail actions appear only when the title or description is clipped after layout.
- Information and confirmation dialogs share a padded panel, aligned help rows and compact footer buttons.
  Long explanations scroll with Up/Down without losing the close button.

- Opening playlists restores focus after loading; page arrows retain focus and fixed height at page boundaries.
- Daily recommendations share the playlist detail artwork header, toolbar and six-song table.
- Library folder selection uses a three-column grid, scrolling independently above fixed navigation and add actions.

- Single-choice menus use text-only lists with selected text highlighting; multi-choice columns retain checkboxes.

- Progress focus and seek editing use a compact circular thumb instead of a filled focus box.
- Playback failures show once per song/error change; song-row playback markers update in place.
- Adding a library folder requests storage permission and continues into the picker after approval.
  TV storage and USB volumes appear as direct roots. No all-files access permission is requested.

- Playlist, Favorite and Recent headers share artwork-colored cards and a right-aligned toolbar.
  Favorite and Recent retain their tabs. Playlist descriptions open in a complete detail view.
- Narrow pager gutters and reduced panel margins leave more width for the song table.
  Header cards retain their padding; inset table dividers align to the cover left edges.
  Playlist details use a compact, separately focusable action beside the description.
- An anchored checkbox menu controls song columns; single-page lists hide pager buttons.
- Compact tabs share a continuous artwork background with Favorite and Recent headers.
  Tabs have a focus-safe inset; the filter matches the toolbar width and statistics align
  with the toolbar bottom edge in Favorite, Recent and playlist detail pages.
  The combined header aligns to playlist card edges and uses translucent focus highlights.
- Settings cards inset focus borders to keep corners visible. Home Back opens a dedicated
  exit action panel, defaulting to Continue Listening.
  Song tables show six rows per full page; the Most Played tab has its own title and filter label.
- Side pagers keep a fixed height and center on the reserved list viewport, including short final pages.
  Pager positions are resolved before drawing. Horizontal navigation crosses one song between
  buttons, and Left from the previous-page button returns to the sidebar.
- Playback, Appearance and Services use shared section cards with inset dividers, matching About.
- Settings focus reveals the complete option group, including headings and descriptions.
- Empty results are centered in the complete content panel.
- Fixed square navigation tiles with an icon above the label; 最近 / 音乐库 use short names.
- Settings option values stay on one line, with controls expanding to fit their text.
  Applying a choice keeps focus on the originating setting. Volume and translation settings
  update their existing controls without rebuilding the page; translation uses a text-only dropdown.
- Entering lyrics focuses the current cue and follows playback. Manual browsing suspends
  following for five seconds after the last input, even while lyrics retain focus.
  Automatic following never takes focus from another control or dialog.
- Lyrics settings keep the main panel visible while a child panel opens beside it.
- Queue viewport height fits whole rows, with a protected bottom inset and full-row focus reveal.
- Opening the queue and navigating vertically focuses the selection checkbox by default.
  Toggling it updates selection in place, retaining the same focus view and scroll position.
- Login toolbar buttons fit the tab row with room for their focus outlines.
- Loading uses a neutral placeholder; grids and restored scroll positions are resolved
  before painting to avoid an incorrect first frame.
- Queue actions become visible before horizontal focus moves; explicit focus ownership
  prevents stale buttons. Sequence/selection markers are drawn without switching icon fonts.
- The player volume popup uses a compact vertical slider with Up/Down and mouse dragging,
  a small thumb and no oversized focus frame.
- Infinite roaming offers Personal FM, Heart mode and Similar songs, fetching a new batch
  when the current batch ends. Heart mode uses the account liked playlist and intelligence API.
- Choice menus open beside their controls with shared dropdown styling and selected text highlighting.
  Column visibility retains independent checkboxes for multiple selections.
  Theme, appearance variant and playback animation retain their inline choice buttons.
- Now Playing has a two-line Up Next list with duration, remote navigation and automatic batches.
- Local history/favorite clearing controls are omitted from Settings.
- Theme changes preserve the selected light/dark appearance, including cross-theme variant names.
- Cloud song likes are synchronized for the active account and shared by lists, queue and player.
- All tabs show text. Focus stays on the user's current control across asynchronous loads.
- Playlist grids, song tables and folders use Previous/Next controls at the left and right sides.
  API batches are fetched as needed for the next page; no pager is placed below the final row.
  Down from the final list row or sidebar Settings moves to the left edge of the playback bar.
  The floating queue and Now Playing lyrics/Up Next retain their scroll behavior.
  Returning from a detail restores the prior item.
- Recommended playlists include daily recommendations and the returned private-radar playlist.
- The local mirror in `tools/tv-remote/` supports clickable video, wheel scrolling and D-pad buttons.

## Build

Requires Rust 1.97.0, JDK 21, Android SDK platform 35, build tools 36.0.0 and NDK r28.

```sh
rustup target add armv7-linux-androideabi
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_ROOT="$ANDROID_HOME/ndk/28.2.13676358"
export JAVA_HOME=/path/to/jdk
python3 android-tv/build.py
adb install -r android-tv/build/Netodia-TV-armv7-debug.apk
adb shell am start -n com.netodia.tv/.MainActivity
```

The local development key is generated automatically and ignored by Git. This APK is a
preview build, not a store release. Minimum Android 8 (API 26); target API 29 with legacy read-only storage access for TVs without an all-files permission settings page.

## Controls and features

- D-pad navigates buttons and lists; OK selects; Back returns to discovery or leaves the app.
- On the progress bar, left/right moves focus. OK enters seek mode; OK/Back leaves seek mode.
- Lyrics use a subtle focus marker, enter at the active cue, and resume following five seconds
  after the last manual navigation or scroll input.
- About uses grouped author/license cards and local log viewing/report export.
- Discover, charts, song search (first 30 results), playlist playback (first page for account playlists).
- QR login, app-private session persistence, logout; no credentials are logged or backed up.
- Previous/next, pause/resume, seek bar and media keys; seek mode adjusts by 10 seconds, accelerating on held keys.
- Timestamped original lyrics; account/track restrictions and trial playback follow the upstream client.
- Playback remains active when leaving the activity. Settings can stop playback and exit.

## Scope and known gaps

This is a TV preview, and UI parity work is still in progress. Local folder scanning,
artwork, timestamped lyrics with translation/romanization, EQ, playback speed, sleep timer,
and side pagination are implemented. QR login requires authorization in the mobile app.

Gapless playback, crossfade, audio visualization, exclusive output, ReplayGain, downloads,
voice search and production credential encryption are not implemented. Unavailable desktop
settings are hidden. Some service settings still differ from the desktop application.
The Rust bridge includes the upstream lyric lookup/romanization helpers; the online fallback
and its separate settings controls are not yet connected in the TV frontend.

Network operations run on a background worker pool, while the native client serializes its
own requests. A slow upstream response can still delay subsequent native requests.
Playback permissions, membership restrictions and trials follow the upstream client.

License: AGPL-3.0-or-later, inherited from Netodia. Keep modified source available with distributions.

## TCL installation through 多屏互动

If ADB reports `INSTALL_FAILED_VERIFICATION_FAILURE`, use the TV's native multi-screen
app-push protocol. This does not disable the system installer or its verifier.
The helper is vendored from [aerial-tv](https://github.com/pengchujin/aerial-tv/blob/cec12af5cf40b0dae21e9c336325fac47d832b0d/tools/tcast-push.py),
with its MIT license retained in tools/LICENSE-aerial-tv.txt.

Run from the repository root, with ADB connected to the TV:

```sh
adb reverse tcp:8765 tcp:8765
python3 -m http.server 8765 --bind 127.0.0.1 --directory android-tv/build
```

Keep that server running; in another terminal:

```sh
python3 android-tv/tools/tcast-push.py TV_IP http://127.0.0.1:8765/Netodia-TV-armv7-debug.apk com.netodia.tv 'Netodia TV' 1 180
```

When 多屏互动 asks, press Right then OK to select 同意. Wait for `Installed.`,
then stop the HTTP server and run `adb reverse --remove tcp:8765`.
Launch with `adb shell am start -W -n com.netodia.tv/.MainActivity`.

Replace `TV_IP` with your television's LAN address. Use `adb -s TV_IP:5555` when
multiple devices are connected. Device addresses, credentials, screenshots and local
validation journals are not distributed in this repository.
