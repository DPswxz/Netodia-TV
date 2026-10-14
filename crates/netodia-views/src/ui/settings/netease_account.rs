//! `NetEase` account form and session status binding.

use netodia_app::state::AppState;
use netodia_core::error::describe;
use netodia_ui::{AppWindow, CloudAccount};
use slint::{ComponentHandle, Weak};

/// Seed the account card and wire password login and logout actions.
pub fn install(ui: &AppWindow, state: &AppState) {
    let weak = ui.as_weak();
    paint_status(&weak, state.cloud_playback.account_status());
    subscribe_status(weak, state);

    let account = ui.global::<CloudAccount>();
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
        account.on_logout(move || {
            let state = state.clone();
            let weak = weak.clone();
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
