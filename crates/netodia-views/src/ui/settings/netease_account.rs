//! `NetEase` account form and session status binding.

use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use netodia_app::cloud::QrStatus;
use netodia_app::state::AppState;
use netodia_core::error::describe;
use netodia_ui::{AppWindow, CloudAccount, Nav, SettingsPage};
use parking_lot::Mutex;
use qrcode::QrCode;
use qrcode::render::svg;
use slint::{ComponentHandle, Image, Weak};
use tokio_util::sync::CancellationToken;

#[derive(Clone, Default)]
struct QrLoginController {
    generation: Arc<AtomicU64>,
    active: Arc<Mutex<Option<(u64, CancellationToken)>>>,
}

/// Seed the account card and wire password login and logout actions.
pub fn install(ui: &AppWindow, state: &AppState) {
    let weak = ui.as_weak();
    paint_status(&weak, state.cloud_playback.account_status());
    subscribe_status(weak, state);

    let account = ui.global::<CloudAccount>();
    let qr_login = QrLoginController::default();
    {
        let state = state.clone();
        let weak = ui.as_weak();
        account.on_login_phone(move |phone, country_code, password| {
            let state = state.clone();
            let weak = weak.clone();
            set_form_state(&weak, true, None);
            state.runtime.spawn(async move {
                if let Err(error) = state
                    .cloud_playback
                    .login_phone(phone.as_str(), country_code.as_str(), password.as_str())
                    .await
                {
                    log::warn!("netease account phone login: {}", describe(&error));
                    set_form_state(&weak, false, Some(describe(&error)));
                } else {
                    set_form_state(&weak, false, None);
                }
            });
        });
    }

    {
        let state = state.clone();
        let weak = ui.as_weak();
        account.on_login_email(move |email, password| {
            let state = state.clone();
            let weak = weak.clone();
            set_form_state(&weak, true, None);
            state.runtime.spawn(async move {
                if let Err(error) =
                    state.cloud_playback.login_email(email.as_str(), password.as_str()).await
                {
                    log::warn!("netease account email login: {}", describe(&error));
                    set_form_state(&weak, false, Some(describe(&error)));
                } else {
                    set_form_state(&weak, false, None);
                }
            });
        });
    }

    {
        let state = state.clone();
        let weak = ui.as_weak();
        let qr_login = qr_login.clone();
        account.on_start_qr_login(move || {
            start_qr_login(weak.clone(), state.clone(), qr_login.clone(), false);
        });
    }

    {
        let weak = ui.as_weak();
        let qr_login = qr_login.clone();
        account.on_cancel_qr_login(move || cancel_qr_login(&weak, &qr_login));
    }

    {
        let weak = ui.as_weak();
        let qr_login = qr_login.clone();
        let state = state.clone();
        account.on_qr_page_active_changed(move |active| {
            if active {
                start_qr_login(weak.clone(), state.clone(), qr_login.clone(), true);
            } else {
                cancel_qr_login(&weak, &qr_login);
            }
        });
    }

    {
        let state = state.clone();
        let weak = ui.as_weak();
        account.on_logout(move || {
            let state = state.clone();
            let weak = weak.clone();
            let qr_login = qr_login.clone();
            cancel_qr_login(&weak, &qr_login);
            set_form_state(&weak, true, None);
            state.runtime.spawn(async move {
                if let Err(error) = state.cloud_playback.logout().await {
                    log::warn!("netease account logout: {}", describe(&error));
                    set_form_state(&weak, false, Some(describe(&error)));
                } else {
                    set_form_state(&weak, false, None);
                }
            });
        });
    }
}

/// Start the first QR code after persisted tabs have been hydrated. Slint initializes change
/// trackers silently, so a launch already on Services may not emit the page-active callback.
pub fn start_initial_qr_login_if_active(ui: &AppWindow) {
    if qr_page_is_active(ui) {
        ui.global::<CloudAccount>().invoke_qr_page_active_changed(true);
    }
}

/// Start QR sign-in when the optional welcome guide is opened, even if the underlying Settings tab
/// is not Services. The automatic path waits for session restoration before doing any login work.
pub fn start_onboarding_qr_login_if_needed(ui: &AppWindow) {
    let account = ui.global::<CloudAccount>();
    if !qr_page_is_active(ui) && !account.get_logged_in() && account.get_qr_status() == 0 {
        account.invoke_qr_page_active_changed(true);
    }
}

/// Keep QR polling alive after the guide closes only when the underlying Services page is active.
pub fn stop_onboarding_qr_login_if_page_inactive(ui: &AppWindow) {
    if !qr_page_is_active(ui) {
        ui.global::<CloudAccount>().invoke_qr_page_active_changed(false);
    }
}

fn qr_page_is_active(ui: &AppWindow) -> bool {
    const NAV_SETTINGS: i32 = 9;

    let nav = ui.global::<Nav>();
    let settings = ui.global::<SettingsPage>();
    nav.get_selected_index() == NAV_SETTINGS
        && !nav.get_now_playing_open()
        && (settings.get_tab_idx() == settings.get_tab_services()
            || !settings.get_search_query().is_empty())
}

fn start_qr_login(
    weak: Weak<AppWindow>,
    state: AppState,
    controller: QrLoginController,
    automatic: bool,
) {
    let generation = controller.generation.fetch_add(1, Ordering::AcqRel).wrapping_add(1);
    let cancellation = CancellationToken::new();
    if let Some((_, previous)) =
        controller.active.lock().replace((generation, cancellation.clone()))
    {
        previous.cancel();
    }
    set_qr_state(&weak, &controller, generation, 1, true, None, None);

    state.runtime.spawn(async move {
        let key = tokio::select! {
            () = cancellation.cancelled() => return,
            result = async {
                if automatic {
                    state.cloud_playback.begin_qr_login_if_signed_out().await
                } else {
                    state.cloud_playback.begin_qr_login().await.map(Some)
                }
            } => match result {
                Ok(Some(key)) => key,
                Ok(None) => {
                    set_qr_state(&weak, &controller, generation, 0, false, None, None);
                    controller.finish(generation);
                    return;
                }
                Err(error) => {
                    if controller.is_current(generation) {
                        set_qr_state(&weak, &controller, generation, 6, false, None, Some(describe(&error)));
                    }
                    controller.finish(generation);
                    return;
                }
            },
        };

        if !controller.is_current(generation) {
            return;
        }
        let svg = match login_qr_svg(&key) {
            Ok(svg) => svg,
            Err(error) => {
                set_qr_state(
                    &weak,
                    &controller,
                    generation,
                    6,
                    false,
                    None,
                    Some(error.to_string()),
                );
                controller.finish(generation);
                return;
            }
        };
        set_qr_state(&weak, &controller, generation, 2, false, Some(svg.clone()), None);

        loop {
            tokio::select! {
                () = cancellation.cancelled() => return,
                () = tokio::time::sleep(Duration::from_secs(3)) => {},
            }
            if !controller.is_current(generation) {
                return;
            }

            let status = tokio::select! {
                () = cancellation.cancelled() => return,
                result = state.cloud_playback.check_qr_login(&key) => result,
            };
            match status {
                Ok(QrStatus::Waiting) => {}
                Ok(QrStatus::Confirming) => {
                    set_qr_state(&weak, &controller, generation, 3, false, Some(svg.clone()), None);
                }
                Ok(QrStatus::Expired) => {
                    set_qr_state(&weak, &controller, generation, 4, false, Some(svg), None);
                    controller.finish(generation);
                    return;
                }
                Ok(QrStatus::Authorized) => {
                    set_qr_state(&weak, &controller, generation, 5, false, Some(svg), None);
                    controller.finish(generation);
                    return;
                }
                Err(error) => {
                    set_qr_state(
                        &weak,
                        &controller,
                        generation,
                        6,
                        false,
                        Some(svg),
                        Some(describe(&error)),
                    );
                    controller.finish(generation);
                    return;
                }
            }
        }
    });
}

fn cancel_qr_login(weak: &Weak<AppWindow>, controller: &QrLoginController) {
    let generation = controller.generation.fetch_add(1, Ordering::AcqRel).wrapping_add(1);
    if let Some((_, cancellation)) = controller.active.lock().take() {
        cancellation.cancel();
    }
    set_qr_state(weak, controller, generation, 0, false, None, None);
}

fn set_qr_state(
    weak: &Weak<AppWindow>,
    controller: &QrLoginController,
    generation: u64,
    status: i32,
    busy: bool,
    svg: Option<String>,
    notice: Option<String>,
) {
    let weak = weak.clone();
    let current_generation = controller.generation.clone();
    let notice = notice.unwrap_or_default();
    let _ = weak.upgrade_in_event_loop(move |ui| {
        if current_generation.load(Ordering::Acquire) != generation {
            return;
        }
        let account = ui.global::<CloudAccount>();
        let image = svg
            .as_deref()
            .and_then(|svg| Image::load_from_svg_data(svg.as_bytes()).ok())
            .unwrap_or_default();
        account.set_qr_image(image);
        account.set_qr_status(status);
        account.set_qr_busy(busy);
        account.set_notice(notice.into());
    });
}

fn login_qr_svg(key: &str) -> Result<String, qrcode::types::QrError> {
    let url = format!("https://music.163.com/login?codekey={key}");
    let code = QrCode::new(url.as_bytes())?;
    Ok(code
        .render::<svg::Color>()
        .min_dimensions(240, 240)
        .dark_color(svg::Color("#11111b"))
        .light_color(svg::Color("#ffffff"))
        .build())
}

fn subscribe_status(weak: Weak<AppWindow>, state: &AppState) {
    let mut status = state.cloud_playback.subscribe_account();
    state.runtime.spawn(async move {
        while status.changed().await.is_ok() {
            paint_status(&weak, status.borrow().clone());
        }
    });
}

fn paint_status(weak: &Weak<AppWindow>, status: netodia_app::cloud::SessionStatus) {
    let _ = weak.upgrade_in_event_loop(move |ui| {
        let account = ui.global::<CloudAccount>();
        let logged_in = status.account.is_some();
        let account_name = status.account.map_or_else(String::new, |account| account.nickname);
        account.set_logged_in(logged_in);
        account.set_account_name(account_name.into());
        account.set_remember_available(status.remember_available);
        // A sign-in transition briefly publishes a signed-out status before the request resolves.
        // Preserve the request error set by the form during that interval.
        if logged_in || status.notice.is_some() {
            account.set_notice(status.notice.unwrap_or_default().into());
        }
    });
}

fn set_form_state(weak: &Weak<AppWindow>, busy: bool, notice: Option<String>) {
    let notice = notice.unwrap_or_default();
    let _ = weak.upgrade_in_event_loop(move |ui| {
        let account = ui.global::<CloudAccount>();
        account.set_busy(busy);
        account.set_notice(notice.into());
    });
}

impl QrLoginController {
    fn is_current(&self, generation: u64) -> bool {
        self.generation.load(Ordering::Acquire) == generation
    }

    fn finish(&self, generation: u64) {
        let mut active = self.active.lock();
        if active.as_ref().is_some_and(|(current, _)| *current == generation) {
            active.take();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::login_qr_svg;
    use slint::Image;

    #[test]
    fn login_qr_svg_can_be_loaded_by_slint() {
        let svg = login_qr_svg("netodia-test-key");
        assert!(svg.is_ok(), "QR generation should succeed");
        if let Ok(svg) = &svg {
            assert!(svg.contains("<svg"));
            assert!(svg.contains("#11111b"));
            assert!(svg.contains("#ffffff"));
            assert!(svg.contains("viewBox"));
            assert!(
                Image::load_from_svg_data(svg.as_bytes()).is_ok(),
                "generated QR SVG should be accepted by Slint"
            );
        }
    }
}
