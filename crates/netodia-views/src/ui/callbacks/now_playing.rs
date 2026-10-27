//! The player's favorite action belongs to cloud songs, including the keyboard shortcut.

use std::sync::Arc;

use slint::ComponentHandle;

use netodia_app::state::AppState;
use netodia_core::entities::track::TrackSummary;
use netodia_core::error::describe;
use netodia_engine::player::engine::state::{lock_state, with_state_emit};
use netodia_ui::{AppWindow, Player};

fn favorite_target(track: &TrackSummary) -> Option<(i64, bool)> {
    (track.file_path.is_empty() && track.id > 0).then_some((track.id, !track.is_favorite))
}

/// Wire `Player.toggle-favorite` to the `NetEase` liked-song list. Local files are rejected
/// here as well as in the UI, so shortcuts cannot write their historical database flags.
pub fn wire_now_playing_favorite(ui: &AppWindow, state: &AppState) {
    let s = state.clone();
    ui.global::<Player>().on_toggle_favorite(move || {
        let s = s.clone();
        let Some(current) = lock_state(&s.player_state).source.clone() else {
            return;
        };
        let Some((id, liked)) = favorite_target(&current) else { return };
        s.runtime.clone().spawn(async move {
            let generation = s.cloud.status().generation;
            if let Err(error) = s.cloud.like(id, liked).await {
                log::warn!("netease_like({id}, {liked}): {}", describe(&error));
                return;
            }
            if s.cloud.status().generation != generation {
                return;
            }
            with_state_emit(&s.player_state, &s.sinks, |player| {
                if let Some(track) = player.source.as_mut()
                    && track.id == id
                    && track.file_path.is_empty()
                {
                    Arc::make_mut(track).is_favorite = liked;
                }
                for track in &mut player.queue.tracks {
                    if track.id == id && track.file_path.is_empty() {
                        Arc::make_mut(track).is_favorite = liked;
                    }
                }
            });
            s.cloud_liked_changed.bump();
        });
    });
}

#[cfg(test)]
#[path = "tests/now_playing_tests.rs"]
mod tests;
