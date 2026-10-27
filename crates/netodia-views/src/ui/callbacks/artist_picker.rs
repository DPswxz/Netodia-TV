//! Resolves a song's structured artist credits before choosing a navigation target.

use std::collections::HashSet;
use std::sync::Arc;

use slint::{ComponentHandle, Model, ModelRc, VecModel};

use super::DialogClaim;
use super::cross_tab_nav::{Origin, open_artist_cross_tab};
use crate::ui::artists::ArtistsUi;
use netodia_app::library;
use netodia_app::state::AppState;
use netodia_core::error::{AppError, describe};
use netodia_ui::{AppWindow, ArtistChoice, ArtistPicker, CloudTrackActions, Dialog, DialogKind};

#[derive(Clone, Copy)]
enum SongSource {
    Local,
    Cloud,
}

pub(super) fn wire(ui: &AppWindow, state: &AppState, artists_ui: &Arc<ArtistsUi>) {
    let weak = ui.as_weak();
    let state_local = state.clone();
    let artists_local = artists_ui.clone();
    ui.global::<ArtistPicker>().on_request_local(move |id| {
        let Some(ui) = weak.upgrade() else { return };
        request(&ui, &state_local, &artists_local, i64::from(id), SongSource::Local);
    });

    let weak = ui.as_weak();
    let state_cloud = state.clone();
    let artists_cloud = artists_ui.clone();
    ui.global::<ArtistPicker>().on_request_cloud(move |id| {
        let Some(id) = crate::ui::util::parse_cloud_id(id.as_str()) else { return };
        let Some(ui) = weak.upgrade() else { return };
        request(&ui, &state_cloud, &artists_cloud, id, SongSource::Cloud);
    });

    let weak = ui.as_weak();
    let state = state.clone();
    let artists_ui = artists_ui.clone();
    ui.global::<ArtistPicker>().on_choose(move |index| {
        let Some(ui) = weak.upgrade() else { return };
        if ui.global::<Dialog>().get_kind() != DialogKind::SelectArtist {
            return;
        }
        let picker = ui.global::<ArtistPicker>();
        let Some(row) = usize::try_from(index).ok().and_then(|i| picker.get_rows().row_data(i))
        else {
            return;
        };
        let Some(id) = crate::ui::util::parse_cloud_id(row.id.as_str()) else { return };
        let source = if picker.get_cloud() { SongSource::Cloud } else { SongSource::Local };
        open(&ui, &state, &artists_ui, id, source);
    });
}

fn request(
    ui: &AppWindow,
    state: &AppState,
    artists_ui: &Arc<ArtistsUi>,
    id: i64,
    source: SongSource,
) {
    if id <= 0 || ui.global::<Dialog>().get_open() {
        return;
    }
    ui.global::<Dialog>().invoke_prepare_artist_picker();
    let claim = DialogClaim::take(ui);
    let origin = Origin::read(ui);
    let weak = ui.as_weak();
    let state = state.clone();
    let artists_ui = artists_ui.clone();
    let generation = state.cloud.status().generation;
    state.runtime.clone().spawn(async move {
        let result = load_choices(&state, id, source).await;
        let _ = weak.upgrade_in_event_loop(move |ui| {
            if !claim.holds(&ui) || !origin.still_current(&ui) {
                return;
            }
            if matches!(source, SongSource::Cloud) && state.cloud.status().generation != generation
            {
                return;
            }
            let dialog = ui.global::<Dialog>();
            match result {
                Ok(choices) if choices.len() == 1 => {
                    open(&ui, &state, &artists_ui, choices[0].0, source);
                }
                Ok(choices) if !choices.is_empty() => {
                    let rows: Vec<ArtistChoice> = choices
                        .into_iter()
                        .map(|(id, name)| ArtistChoice {
                            id: id.to_string().into(),
                            name: name.into(),
                        })
                        .collect();
                    let picker = ui.global::<ArtistPicker>();
                    picker.set_rows(ModelRc::new(VecModel::from(rows)));
                    picker.set_cloud(matches!(source, SongSource::Cloud));
                    dialog.set_open(true);
                }
                Ok(_) => {}
                Err(error) => {
                    log::warn!("artist_picker({id}): {}", describe(&error));
                    dialog.set_message(describe(&error).into());
                    dialog.set_open(true);
                }
            }
        });
    });
}

async fn load_choices(
    state: &AppState,
    id: i64,
    source: SongSource,
) -> Result<Vec<(i64, String)>, AppError> {
    let choices = match source {
        SongSource::Local => library::artists::get_track_artists(&state.db, id)
            .await?
            .into_iter()
            .map(|artist| (artist.id, artist.name))
            .collect(),
        SongSource::Cloud => state
            .cloud
            .songs(&[id])
            .await?
            .into_iter()
            .find(|song| song.id == id)
            .map(|song| song.artists.into_iter().map(|artist| (artist.id, artist.name)).collect())
            .unwrap_or_default(),
    };
    Ok(unique_choices(choices))
}

fn unique_choices(choices: Vec<(i64, String)>) -> Vec<(i64, String)> {
    let mut seen = HashSet::with_capacity(choices.len());
    choices.into_iter().filter(|(id, _)| *id > 0 && seen.insert(*id)).collect()
}

fn open(
    ui: &AppWindow,
    state: &AppState,
    artists_ui: &Arc<ArtistsUi>,
    id: i64,
    source: SongSource,
) {
    match source {
        SongSource::Cloud => {
            ui.global::<CloudTrackActions>().invoke_open_artist(id.to_string().into());
        }
        SongSource::Local => open_artist_cross_tab(
            state,
            artists_ui,
            &ui.as_weak(),
            id,
            Origin::read(ui),
            "artist_picker::open",
        ),
    }
}

#[cfg(test)]
#[path = "tests/artist_picker_tests.rs"]
mod tests;
