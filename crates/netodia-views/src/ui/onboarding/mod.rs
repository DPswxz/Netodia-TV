//! The optional welcome guide.
//!
//! In `ui/` at the root rather than under `settings/`: the card is owned by no page, the way
//! `sleep_timer` and `equalizer` are. It takes `(&AppWindow, &AppState)` for the same reason —
//! `ViewCtx` carries the cover tier and `views.json` that the ten nav sections need and this
//! doesn't.
//!
//! **Rust owns the mount and unmount timing.** The overlay is `if`-mounted on
//! `Onboarding.mounted`, so a `changed` handler inside it watching either of these globals would
//! stay registered against a property that outlives its own branch and panic the next time it
//! moves — which re-running the card does to both. So the two edges are `slint::Timer::single_shot`
//! here instead: one frame after `mounted` to raise `open` and give `animate` an edge to run on,
//! and one fade later to drop the branch.

use std::time::Duration;

use netodia_app::state::AppState;
use netodia_ui::{AppWindow, Onboarding};
use slint::ComponentHandle;

mod callbacks;
mod music_folder;

/// One frame, so the branch exists before `open` moves and the fade has an edge to animate from.
/// `init` would be too early: it runs after bindings resolve, making `true` the initial value.
const MOUNT_SETTLE: Duration = Duration::from_millis(1);

/// Wire the optional guide without covering the launch page. Fresh installs open directly on the
/// Services tab, where `NetEase` QR sign-in starts during settings hydration; the guide remains
/// available from Settings → About.
pub fn install(ui: &AppWindow, state: &AppState) {
    callbacks::wire(ui, state);
    music_folder::wire(ui, state);
}

/// Mount the card, then raise it a frame later.
pub fn open(ui: &AppWindow, state: &AppState) {
    music_folder::refresh(ui, state);
    let onboarding = ui.global::<Onboarding>();
    onboarding.set_step(0);
    onboarding.set_mounted(true);

    let weak = ui.as_weak();
    slint::Timer::single_shot(MOUNT_SETTLE, move || {
        if let Some(ui) = weak.upgrade() {
            ui.global::<Onboarding>().set_open(true);
        }
    });
}

#[cfg(test)]
#[path = "tests/onboarding_tests.rs"]
mod tests;
