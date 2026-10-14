//! Account sessions for the direct `NetEase` client.
//!
//! This module is the application boundary for login and session lifetime. Views receive typed
//! account state and never see cookies, device credentials, or raw HTTP responses.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use netodia_core::entities::cloud::{Account, Quality, StreamTicket};
use netodia_core::error::{AppError, AppResult};
use netodia_net::netease::Client;
pub use netodia_net::netease::QrStatus;
use serde::{Deserialize, Serialize};
use tokio::sync::{Mutex, watch};
use tokio_util::sync::CancellationToken;

const KEYRING_SERVICE: &str = "Netodia";
const KEYRING_USER: &str = "netease-session";
const SESSION_VERSION: u8 = 1;

/// UI-safe account state. The credential and the session cookie intentionally have no public
/// representation in this type.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct SessionStatus {
    pub account: Option<Account>,
    pub remember_available: bool,
    pub notice: Option<String>,
    /// Changes on account transitions so views can discard private cached models.
    pub generation: u64,
}

/// Owns one `NetEase` client and coordinates login, restoration, logout, and account-scoped work.
#[derive(Clone)]
pub struct CloudService {
    client: Client,
    http: reqwest::Client,
    pending_restore: Arc<Mutex<Option<StoredSession>>>,
    status: watch::Sender<SessionStatus>,
    transition: Arc<Mutex<()>>,
    account_tasks: Arc<parking_lot::RwLock<CancellationToken>>,
    guest_initialized: Arc<AtomicBool>,
}

#[derive(Serialize, Deserialize)]
struct StoredSession {
    version: u8,
    device_id: String,
    cookie: String,
}

impl CloudService {
    /// Reads only the OS credential store. If it is unavailable, the service remains usable and
    /// reports that this process cannot remember a login.
    pub async fn new(http: reqwest::Client) -> Self {
        let read = tokio::task::spawn_blocking(read_credential).await;
        let (stored, remember_available, notice) = match read {
            Ok(Ok(Some(secret))) => {
                if let Some(stored) = decode_session(&secret) {
                    (Some(stored), true, None)
                } else {
                    let removed = tokio::task::spawn_blocking(delete_credential)
                        .await
                        .is_ok_and(|result| result.is_ok());
                    (None, removed, Some("已清除无法读取的登录信息。".into()))
                }
            }
            Ok(Ok(None)) => (None, true, None),
            _ => (None, false, Some("系统凭据存储不可用，登录信息只会保留在本次运行中。".into())),
        };

        let device_id = stored
            .as_ref()
            .map(|session| session.device_id.clone())
            .filter(|id| valid_device_id(id));
        let client = device_id
            .and_then(|id| Client::with_device_id(http.clone(), id).ok())
            .unwrap_or_else(|| Client::new(http.clone()));
        let initial = SessionStatus { account: None, remember_available, notice, generation: 0 };
        let (status, _) = watch::channel(initial);

        Self {
            client,
            http,
            pending_restore: Arc::new(Mutex::new(stored)),
            status,
            transition: Arc::new(Mutex::new(())),
            account_tasks: Arc::new(parking_lot::RwLock::new(CancellationToken::new())),
            guest_initialized: Arc::new(AtomicBool::new(false)),
        }
    }

    pub fn subscribe(&self) -> watch::Receiver<SessionStatus> {
        self.status.subscribe()
    }

    pub fn status(&self) -> SessionStatus {
        self.status.borrow().clone()
    }

    /// Tasks that hold account-private data should select on this token. It is cancelled at the
    /// beginning of every account transition, before a new session becomes visible.
    pub fn account_tasks_token(&self) -> CancellationToken {
        self.account_tasks.read().clone()
    }

    /// Resolve a short-lived CDN ticket under the current account task generation. Session changes
    /// cancel the HTTP future before its result can reach a player.
    pub async fn stream_ticket(&self, song_id: i64, quality: Quality) -> AppResult<StreamTicket> {
        if song_id <= 0 {
            return Err(AppError::Validation("歌曲 ID 无效".to_owned()));
        }
        self.ensure_guest().await?;
        let cancellation = self.account_tasks_token();
        tokio::select! {
            () = cancellation.cancelled() => Err(AppError::network_msg("网易云播放请求已取消")),
            result = self.client.stream(song_id, quality) => result.map_err(Into::into),
        }
    }

    /// Restores a saved cookie once during startup. A rejected cookie is deleted and leaves the
    /// application signed out; it is never exposed to the UI or written to logs.
    pub async fn restore_session(&self) -> AppResult<bool> {
        let _guard = self.transition.lock().await;
        let Some(saved) = self.pending_restore.lock().await.take() else {
            return Ok(false);
        };
        if saved.version != SESSION_VERSION || !valid_device_id(&saved.device_id) {
            let removed = tokio::task::spawn_blocking(delete_credential)
                .await
                .is_ok_and(|result| result.is_ok());
            self.publish(None, Some("已清除无法读取的登录信息。".into()), removed);
            return Ok(false);
        }

        self.begin_transition().await;
        self.client.restore_cookie(&saved.cookie).await;
        match self.client.account().await {
            Ok(account) => {
                self.guest_initialized.store(true, Ordering::Release);
                self.publish(Some(account), None, self.status().remember_available);
                Ok(true)
            }
            Err(error) => {
                self.client.clear_session().await;
                self.guest_initialized.store(false, Ordering::Release);
                if matches!(error, netodia_net::netease::Error::Authentication) {
                    let removed = tokio::task::spawn_blocking(delete_credential)
                        .await
                        .is_ok_and(|result| result.is_ok());
                    self.publish(None, Some("保存的登录已失效，请重新登录。".into()), removed);
                    Ok(false)
                } else {
                    *self.pending_restore.lock().await = Some(saved);
                    self.publish(
                        None,
                        Some("暂时无法连接网易云，登录恢复将在网络可用后重试。".into()),
                        self.status().remember_available,
                    );
                    Err(error.into())
                }
            }
        }
    }

    /// Initializes a guest session for endpoints that allow anonymous discovery.
    pub async fn ensure_guest(&self) -> AppResult<()> {
        if self.status().account.is_some() || self.guest_initialized.load(Ordering::Acquire) {
            return Ok(());
        }
        let _guard = self.transition.lock().await;
        if self.status().account.is_some() || self.guest_initialized.load(Ordering::Acquire) {
            return Ok(());
        }
        self.client.initialize_guest().await?;
        self.guest_initialized.store(true, Ordering::Release);
        Ok(())
    }

    pub async fn login_phone(
        &self,
        phone: &str,
        country_code: &str,
        password: &str,
    ) -> AppResult<Account> {
        validate_nonempty("手机号", phone)?;
        validate_nonempty("国家区号", country_code)?;
        validate_nonempty("密码", password)?;
        let _guard = self.transition.lock().await;
        self.begin_login().await;
        let account = self.client.login_phone(phone, country_code, password).await?;
        self.complete_login(account).await
    }

    pub async fn login_email(&self, email: &str, password: &str) -> AppResult<Account> {
        validate_nonempty("邮箱", email)?;
        validate_nonempty("密码", password)?;
        let _guard = self.transition.lock().await;
        self.begin_login().await;
        let account = self.client.login_email(email, password).await?;
        self.complete_login(account).await
    }

    pub async fn begin_qr_login(&self) -> AppResult<String> {
        let _guard = self.transition.lock().await;
        self.begin_login().await;
        self.client.qr_key().await.map_err(Into::into)
    }

    pub async fn check_qr_login(&self, key: &str) -> AppResult<QrStatus> {
        let _guard = self.transition.lock().await;
        let result = self.client.qr_check(key).await?;
        if result == QrStatus::Authorized {
            let account = self.client.account().await?;
            self.complete_login(account).await?;
        }
        Ok(result)
    }

    /// Invalidates account tasks and local session state before asking the server to end its
    /// session. Local logout still completes if the remote endpoint is unavailable.
    pub async fn logout(&self) -> AppResult<()> {
        let _guard = self.transition.lock().await;
        let cookie = self.client.cookie_for_storage().await;
        let device_id = self.client.device_id().to_owned();
        let http = self.http.clone();
        self.begin_transition().await;
        if !cookie.is_empty()
            && let Ok(remote_client) = Client::with_device_id(http, device_id)
        {
            tokio::spawn(async move {
                remote_client.restore_cookie(&cookie).await;
                let _ = remote_client.logout().await;
            });
        }
        self.guest_initialized.store(false, Ordering::Release);
        self.pending_restore.lock().await.take();
        let deleted =
            tokio::task::spawn_blocking(delete_credential).await.is_ok_and(|result| result.is_ok());
        let notice = (!deleted).then(|| "已退出当前账号，但系统未能删除已保存的登录信息。".into());
        self.publish(None, notice, deleted);
        Ok(())
    }

    async fn begin_transition(&self) {
        self.account_tasks.read().cancel();
        *self.account_tasks.write() = CancellationToken::new();
        self.client.clear_session().await;
        self.guest_initialized.store(false, Ordering::Release);
        self.publish(None, None, self.status().remember_available);
    }

    async fn begin_login(&self) {
        self.begin_transition().await;
        self.pending_restore.lock().await.take();
        let deleted =
            tokio::task::spawn_blocking(delete_credential).await.is_ok_and(|result| result.is_ok());
        if !deleted {
            self.publish(
                None,
                Some("无法清除先前保存的登录信息；新登录成功后会尝试覆盖。".into()),
                false,
            );
        }
    }

    async fn complete_login(&self, account: Account) -> AppResult<Account> {
        self.guest_initialized.store(true, Ordering::Release);
        let cookie = self.client.cookie_for_storage().await;
        let saved = StoredSession {
            version: SESSION_VERSION,
            device_id: self.client.device_id().to_owned(),
            cookie,
        };
        let serialized = serde_json::to_string(&saved)
            .map_err(|_| AppError::network_msg("无法保存网易云登录状态"))?;
        let write = tokio::task::spawn_blocking(move || write_credential(&serialized)).await;
        let remembered = matches!(write, Ok(Ok(())));
        let notice = if remembered {
            None
        } else {
            Some("本机凭据存储不可用，本次登录只保留在内存中。".into())
        };
        self.publish(Some(account.clone()), notice, remembered);
        Ok(account)
    }

    fn publish(&self, account: Option<Account>, notice: Option<String>, remember_available: bool) {
        let generation = self.status.borrow().generation.wrapping_add(1);
        self.status.send_replace(SessionStatus { account, remember_available, notice, generation });
    }
}

fn validate_nonempty(label: &str, value: &str) -> AppResult<()> {
    if value.trim().is_empty() {
        Err(AppError::Validation(format!("{label}不能为空")))
    } else {
        Ok(())
    }
}

fn valid_device_id(value: &str) -> bool {
    value.len() == 52 && value.bytes().all(|b| b.is_ascii_digit() || (b'A'..=b'F').contains(&b))
}

fn decode_session(value: &str) -> Option<StoredSession> {
    let session = serde_json::from_str::<StoredSession>(value).ok()?;
    (session.version == SESSION_VERSION
        && valid_device_id(&session.device_id)
        && !session.cookie.is_empty()
        && !session.cookie.contains(['\r', '\n']))
    .then_some(session)
}

fn read_credential() -> Result<Option<String>, ()> {
    let entry = keyring::Entry::new(KEYRING_SERVICE, KEYRING_USER).map_err(|_| ())?;
    match entry.get_password() {
        Ok(value) => Ok(Some(value)),
        Err(keyring::Error::NoEntry) => Ok(None),
        Err(_) => Err(()),
    }
}

fn write_credential(value: &str) -> Result<(), ()> {
    let entry = keyring::Entry::new(KEYRING_SERVICE, KEYRING_USER).map_err(|_| ())?;
    entry.set_password(value).map_err(|_| ())
}

fn delete_credential() -> Result<(), ()> {
    let entry = keyring::Entry::new(KEYRING_SERVICE, KEYRING_USER).map_err(|_| ())?;
    match entry.delete_credential() {
        Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
        Err(_) => Err(()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const DEVICE_ID: &str = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123";

    #[test]
    fn saved_session_round_trips_without_changing_cookie_pairs() -> Result<(), serde_json::Error> {
        let session = StoredSession {
            version: SESSION_VERSION,
            device_id: DEVICE_ID.into(),
            cookie: "MUSIC_U=session-value; __csrf=csrf-value".into(),
        };
        let encoded = serde_json::to_string(&session)?;
        let decoded = decode_session(&encoded);
        assert!(decoded.is_some(), "the serialized session should be valid");
        let decoded = decoded.unwrap_or(StoredSession {
            version: 0,
            device_id: String::new(),
            cookie: String::new(),
        });
        assert_eq!(decoded.version, SESSION_VERSION);
        assert_eq!(decoded.device_id, DEVICE_ID);
        assert_eq!(decoded.cookie, "MUSIC_U=session-value; __csrf=csrf-value");
        Ok(())
    }

    #[test]
    fn invalid_saved_sessions_are_rejected() {
        for session in [
            format!(r#"{{"version":2,"device_id":"{DEVICE_ID}","cookie":"MUSIC_U=x"}}"#),
            r#"{"version":1,"device_id":"bad","cookie":"MUSIC_U=x"}"#.into(),
            format!(
                r#"{{"version":1,"device_id":"{DEVICE_ID}","cookie":"MUSIC_U=x\nInjected: y"}}"#
            ),
        ] {
            assert!(decode_session(&session).is_none());
        }
    }

    #[test]
    fn login_fields_reject_blank_values_before_network_access() {
        assert!(validate_nonempty("邮箱", " \t").is_err());
        assert!(validate_nonempty("邮箱", "user@example.com").is_ok());
    }
}
