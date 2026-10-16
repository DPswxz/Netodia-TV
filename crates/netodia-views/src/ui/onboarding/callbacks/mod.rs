//! The card's wiring. One way out, shared by the X, the backdrop, Escape and Skip.

use std::time::Duration;

use netodia_app::library;
use netodia_app::state::AppState;
use netodia_ui::{AppWindow, Onboarding, Settings, Theme};
use slint::ComponentHandle;

pub(super) fn wire(ui: &AppWindow, state: &AppState) {
    wire_dismiss(ui, state);
    wire_run_again(ui, state);
}

/// The Settings ▸ About row, which is the entry point for opening the optional guide.
fn wire_run_again(ui: &AppWindow, state: &AppState) {
    let weak = ui.as_weak();
    let state = state.clone();
    ui.global::<Settings>().on_run_onboarding(move || {
        if let Some(ui) = weak.upgrade() {
            super::open(&ui, &state);
        }
    });
}

/// Mark the card seen and close it.
///
/// Every dismissal path lands here, including Skip on the first panel. Settings ▸ About remains
/// available when someone wants to open the guide again.
fn wire_dismiss(ui: &AppWindow, state: &AppState) {
    let weak = ui.as_weak();
    let state = state.clone();

    ui.global::<Onboarding>().on_dismiss(move || {
        let Some(ui) = weak.upgrade() else { return };
        let onboarding = ui.global::<Onboarding>();
        // Esc pressed twice inside the fade would otherwise queue a second persist and a second
        // unmount timer.
        if !onboarding.get_open() {
            return;
        }
        onboarding.set_open(false);

        state.persist_blocking("onboarding_version", library::settings::set_onboarding_seen);

        // Held until the fade has run, so the card isn't cut off mid-animation. Taken from the
        // token the overlay animates on rather than restated here.
        let fade_ms = u64::try_from(ui.global::<Theme>().get_dur_fast()).unwrap_or(0);
        let fade = Duration::from_millis(fade_ms);
        let weak = ui.as_weak();
        slint::Timer::single_shot(fade, move || {
            if let Some(ui) = weak.upgrade() {
                ui.global::<Onboarding>().set_mounted(false);
            }
        });
    });
}
