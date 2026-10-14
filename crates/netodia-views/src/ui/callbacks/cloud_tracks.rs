//! Shared exact-ID cloud row actions for Favorites, history and playlist details.

use slint::{ComponentHandle, Model, ModelRc};
use std::sync::Arc;

use netodia_app::state::AppState;
use netodia_core::error::describe;
use netodia_engine::player::engine::state::with_state_emit;
use netodia_ui::{
    AppWindow, CloudTrackActions, Favorites, Player, PlaylistDetail, Queue, RecentlyPlayed,
};

pub(super) fn wire(ui: &AppWindow, state: &AppState) {
    let actions = ui.global::<CloudTrackActions>();
    let s = state.clone();
    actions.on_enqueue(move |remote_id, play_next| {
        let Some(id) = crate::ui::util::parse_cloud_id(remote_id.as_str()) else { return };
        let s = s.clone();
        s.runtime.clone().spawn(async move {
            if let Err(error) = s.cloud_playback.enqueue(&[id], play_next).await {
                log::warn!("cloud_tracks::enqueue({id}): {}", describe(&error));
            }
        });
    });

    let s = state.clone();
    let weak = ui.as_weak();
    actions.on_toggle_favorite(move |remote_id, liked| {
        let Some(id) = crate::ui::util::parse_cloud_id(remote_id.as_str()) else { return };
        let s = s.clone();
        let weak = weak.clone();
        s.runtime.clone().spawn(async move {
            let generation = s.cloud.status().generation;
            if let Err(error) = s.cloud.like(id, liked).await {
                log::warn!("cloud_tracks::like({id}): {}", describe(&error));
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
            let cloud = s.cloud.clone();
            let _ = weak.upgrade_in_event_loop(move |ui| {
                if cloud.status().generation != generation {
                    return;
                }
                patch_like(&ui.global::<Favorites>().get_tracks(), id, liked);
                patch_like(&ui.global::<RecentlyPlayed>().get_tracks(), id, liked);
                patch_like(&ui.global::<PlaylistDetail>().get_tracks(), id, liked);
                let queue_rows = ui.global::<Queue>().get_rows();
                let key = id.to_string();
                for index in 0..queue_rows.row_count() {
                    if let Some(mut row) = queue_rows.row_data(index)
                        && row.is_cloud
                        && row.remote_id.as_str() == key
                    {
                        row.is_favorite = liked;
                        queue_rows.set_row_data(index, row);
                    }
                }
                let player = ui.global::<Player>();
                let mut vm = player.get_vm();
                if vm.track.file_path.is_empty() && vm.track.remote_id.as_str() == key {
                    vm.track.is_favorite = liked;
                    player.set_vm(vm);
                }
            });
            s.cloud_liked_changed.bump();
        });
    });
}

fn patch_like(rows: &ModelRc<netodia_ui::TrackListRow>, id: i64, liked: bool) {
    let key = id.to_string();
    for index in 0..rows.row_count() {
        if let Some(mut row) = rows.row_data(index)
            && row.remote_id.as_str() == key
        {
            row.is_favorite = liked;
            rows.set_row_data(index, row);
        }
    }
}

#[cfg(test)]
#[path = "tests/cloud_tracks_tests.rs"]
mod tests;
