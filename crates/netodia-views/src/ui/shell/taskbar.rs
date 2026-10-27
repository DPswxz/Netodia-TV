//! Keep the Windows thumbnail toolbar attached to the current Slint window.
use std::sync::Arc;
use std::time::Duration;

use netodia_app::state::AppState;
use netodia_engine::player::engine::event_sink::{EventSink, PlayerEvent};
use netodia_platform::services::platform::taskbar::{self, Action, Snapshot};
use netodia_ui::AppWindow;
use slint::ComponentHandle;

use super::event_sink::SlintEventSink;

pub fn install(ui: &AppWindow, state: &AppState) {
    let sink = Arc::new(SlintEventSink { state: state.clone() });
    let callback: Arc<dyn Fn(Action) + Send + Sync> = Arc::new(move |action| {
        sink.handle(match action {
            Action::Previous => PlayerEvent::Previous,
            Action::PlayPause => PlayerEvent::PlayPause,
            Action::Next => PlayerEvent::Next,
        });
    });
    tick(ui.as_weak(), state.sinks.clone(), callback);
}

fn tick(
    weak: slint::Weak<AppWindow>,
    sinks: Arc<netodia_engine::player::engine::event_sink::PlayerSinks>,
    callback: Arc<dyn Fn(Action) + Send + Sync>,
) {
    slint::Timer::single_shot(Duration::from_millis(500), move || {
        let Some(ui) = weak.upgrade() else { return };
        if let Some(hwnd) = crate::ui::window_chrome::win32_hwnd(&ui) {
            let snapshot =
                sinks.view_model.borrow().as_ref().map_or_else(Snapshot::default, |vm| Snapshot {
                    playing: vm.status == "playing",
                    has_previous: vm.has_previous,
                    has_next: vm.has_next,
                });
            taskbar::update(hwnd, snapshot, callback.clone());
        }
        tick(weak, sinks, callback);
    });
}
