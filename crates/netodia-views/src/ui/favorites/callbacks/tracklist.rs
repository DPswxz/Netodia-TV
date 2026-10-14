//! Actions for the `NetEase` liked-songs list.

use std::sync::Arc;

use slint::ComponentHandle;

use crate::ui::callbacks::collect_track_ids;
use crate::ui::favorites::{self as favorites_ui_mod, FavoritesUi};
use netodia_app::state::AppState;
use netodia_core::entities::cloud::Quality;
use netodia_core::error::describe;
use netodia_engine::player::engine::state::lock_state;
use netodia_ui::{AppWindow, Favorites};

pub(super) fn wire(ui: &AppWindow, state: &AppState, fav_ui: &Arc<FavoritesUi>) {
    let g = ui.global::<Favorites>();
    let weak = ui.as_weak();

    {
        let state = state.clone();
        g.on_play_row(move |track_id, _index| {
            let song_id = i64::from(track_id);
            if song_id <= 0 {
                return;
            }
            let state = state.clone();
            state.runtime.spawn(async move {
                let (volume, speed) = {
                    let player = lock_state(&state.player_state);
                    (player.effective_volume(), player.playback_speed)
                };
                if let Err(error) =
                    state.cloud_playback.play(song_id, Quality::Exhigh, volume, speed, None).await
                {
                    log::warn!("favorites::play_cloud_song({song_id}): {}", describe(&error));
                }
            });
        });
    }

    {
        let state = state.clone();
        let runtime = state.runtime.clone();
        let cloud = state.cloud.clone();
        let fav_ui = fav_ui.clone();
        let weak = weak.clone();
        g.on_toggle_row_favorite(move |ids, liked| {
            let ids = collect_track_ids(&ids);
            if ids.is_empty() {
                return;
            }
            let state = state.clone();
            let cloud = cloud.clone();
            let fav_ui = fav_ui.clone();
            let weak = weak.clone();
            runtime.spawn(async move {
                // A failed write stops the batch. Likes are writes and must not be retried
                // automatically because the server may already have accepted one.
                for id in ids {
                    if let Err(error) = cloud.like(id, liked).await {
                        log::warn!("favorites::netease_like({id}): {}", describe(&error));
                        return;
                    }
                }
                if fav_ui.section_active() {
                    if let Err(error) =
                        favorites_ui_mod::refresh_tracks(&state, &fav_ui, &weak).await
                    {
                        log::warn!("favorites::refresh_after_like: {}", describe(&error));
                    }
                } else {
                    fav_ui.mark_songs_dirty();
                }
            });
        });
    }

    // The cloud list has no local rating or local playback queue to mutate.
    g.on_set_row_rating(|_, _| {});
    g.on_play_next(|_| {});
    g.on_add_to_queue(|_| {});

    {
        let fav_ui = fav_ui.clone();
        let weak = weak.clone();
        g.on_filter_changed(move |text| {
            let Some(ui) = weak.upgrade() else { return };
            favorites_ui_mod::set_filter(&fav_ui, &text);
            favorites_ui_mod::apply_filtered_tracks(&fav_ui, &weak);
            favorites_ui_mod::apply_filtered_grids_now(&ui, &fav_ui);
        });
    }

    // Sorting controls aren't mounted on this cloud-backed list.
    g.on_request_sort(|_| {});
}
