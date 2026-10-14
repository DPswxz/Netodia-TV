//! Account sessions for the direct `NetEase` client.
//!
//! This module is the application boundary for login and session lifetime. Views receive typed
//! account state and never see cookies, device credentials, or raw HTTP responses.

use std::future::Future;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use netodia_core::entities::cloud::{
    Account, Album, Artist, Lyrics, Playlist, Quality, SearchKind, SearchResult, Song, StreamTicket,
};
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
        validate_id("歌曲", song_id)?;
        self.public_request(self.client.stream(song_id, quality)).await
    }

    pub async fn discover(&self) -> AppResult<Vec<Playlist>> {
        self.public_request(self.client.discover()).await
    }

    pub async fn charts(&self) -> AppResult<Vec<Playlist>> {
        self.public_request(self.client.charts()).await
    }

    pub async fn search(
        &self,
        keywords: &str,
        kind: SearchKind,
        offset: u64,
    ) -> AppResult<SearchResult> {
        if keywords.trim().is_empty() {
            return Err(AppError::Validation("搜索内容不能为空".to_owned()));
        }
        if keywords.chars().count() > 200 {
            return Err(AppError::Validation("搜索内容不能超过 200 个字符".to_owned()));
        }
        self.public_request(self.client.search(keywords.trim(), kind, offset)).await
    }

    pub async fn songs(&self, ids: &[i64]) -> AppResult<Vec<Song>> {
        if ids.is_empty() {
            return Ok(Vec::new());
        }
        validate_ids("歌曲", ids)?;
        self.public_request(self.client.songs(ids)).await
    }

    pub async fn playlist(&self, id: i64) -> AppResult<(Playlist, Vec<Song>)> {
        validate_id("歌单", id)?;
        self.public_request(self.client.playlist(id)).await
    }

    pub async fn album(&self, id: i64) -> AppResult<(Album, Vec<Song>)> {
        validate_id("专辑", id)?;
        self.public_request(self.client.album(id)).await
    }

    pub async fn artist(&self, id: i64) -> AppResult<(Artist, Vec<Song>)> {
        validate_id("歌手", id)?;
        self.public_request(self.client.artist(id)).await
    }

    pub async fn lyrics(&self, song_id: i64) -> AppResult<Lyrics> {
        validate_id("歌曲", song_id)?;
        self.public_request(self.client.lyrics(song_id)).await
    }

    pub async fn daily(&self) -> AppResult<Vec<Song>> {
        self.private_request(self.client.daily()).await
    }

    pub async fn fm(&self) -> AppResult<Vec<Song>> {
        self.private_request(self.client.fm()).await
    }

    pub async fn fm_trash(&self, song_id: i64) -> AppResult<()> {
        validate_id("歌曲", song_id)?;
        self.private_request(self.client.fm_trash(song_id)).await
    }

    pub async fn user_playlists(&self, offset: u64) -> AppResult<Vec<Playlist>> {
        let (account, cancellation) = self.account_snapshot()?;
        self.account_request_in_generation(
            self.client.user_playlists(account.id, offset),
            cancellation,
        )
        .await
    }

    pub async fn liked_ids(&self) -> AppResult<Vec<i64>> {
        let (account, cancellation) = self.account_snapshot()?;
        self.account_request_in_generation(self.client.liked_ids(account.id), cancellation).await
    }

    pub async fn like(&self, song_id: i64, liked: bool) -> AppResult<()> {
        validate_id("歌曲", song_id)?;
        self.private_request(self.client.like(song_id, liked)).await
    }

    pub async fn saved_albums(&self, offset: u64) -> AppResult<Vec<Album>> {
        self.private_request(self.client.saved_albums(offset)).await
    }

    pub async fn saved_artists(&self, offset: u64) -> AppResult<Vec<Artist>> {
        self.private_request(self.client.saved_artists(offset)).await
    }

    pub async fn subscribe_playlist(&self, id: i64, subscribe: bool) -> AppResult<()> {
        validate_id("歌单", id)?;
        self.private_request(self.client.subscribe_playlist(id, subscribe)).await
    }

    pub async fn subscribe_album(&self, id: i64, subscribe: bool) -> AppResult<()> {
        validate_id("专辑", id)?;
        self.private_request(self.client.subscribe_album(id, subscribe)).await
    }

    pub async fn subscribe_artist(&self, id: i64, subscribe: bool) -> AppResult<()> {
        validate_id("歌手", id)?;
        self.private_request(self.client.subscribe_artist(id, subscribe)).await
    }

    fn require_account(&self) -> AppResult<Account> {
        self.status().account.ok_or_else(|| AppError::network_msg("请先登录网易云账号"))
    }

    fn account_snapshot(&self) -> AppResult<(Account, CancellationToken)> {
        // Capture the account's cancellation generation before reading its identity. A transition
        // cancels that generation before publishing signed-out state, so a request carrying the
        // previous uid can never slip through under a newly installed account.
        let cancellation = self.account_tasks_token();
        let account = self.require_account()?;
        if cancellation.is_cancelled() {
            return Err(AppError::network_msg("网易云请求已取消"));
        }
        Ok((account, cancellation))
    }

    async fn public_request<T>(
        &self,
        request: impl Future<Output = netodia_net::netease::Result<T>>,
    ) -> AppResult<T> {
        self.ensure_guest().await?;
        self.account_request(request).await
    }

    async fn private_request<T>(
        &self,
        request: impl Future<Output = netodia_net::netease::Result<T>>,
    ) -> AppResult<T> {
        let cancellation = self.account_tasks_token();
        self.require_account()?;
        self.account_request_in_generation(request, cancellation).await
    }

    async fn account_request<T>(
        &self,
        request: impl Future<Output = netodia_net::netease::Result<T>>,
    ) -> AppResult<T> {
        let cancellation = self.account_tasks_token();
        self.account_request_in_generation(request, cancellation).await
    }

    async fn account_request_in_generation<T>(
        &self,
        request: impl Future<Output = netodia_net::netease::Result<T>>,
        cancellation: CancellationToken,
    ) -> AppResult<T> {
        if cancellation.is_cancelled() {
            return Err(AppError::network_msg("网易云请求已取消"));
        }
        tokio::select! {
            () = cancellation.cancelled() => Err(AppError::network_msg("网易云请求已取消")),
            result = request => {
                if cancellation.is_cancelled() {
                    Err(AppError::network_msg("网易云请求已取消"))
                } else {
                    result.map_err(Into::into)
                }
            },
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
        self.publish(None, None, self.status().remember_available);
        *self.account_tasks.write() = CancellationToken::new();
        self.client.clear_session().await;
        self.guest_initialized.store(false, Ordering::Release);
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

fn validate_id(label: &str, id: i64) -> AppResult<()> {
    if id <= 0 { Err(AppError::Validation(format!("{label} ID 无效"))) } else { Ok(()) }
}

fn validate_ids(label: &str, ids: &[i64]) -> AppResult<()> {
    if ids.iter().any(|id| *id <= 0) {
        Err(AppError::Validation(format!("{label} ID 无效")))
    } else {
        Ok(())
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

    fn service_for_test() -> CloudService {
        let http = reqwest::Client::new();
        let (status, _) =
            watch::channel(SessionStatus { remember_available: true, ..SessionStatus::default() });
        CloudService {
            client: Client::new(http.clone()),
            http,
            pending_restore: Arc::new(Mutex::new(None)),
            status,
            transition: Arc::new(Mutex::new(())),
            account_tasks: Arc::new(parking_lot::RwLock::new(CancellationToken::new())),
            guest_initialized: Arc::new(AtomicBool::new(false)),
        }
    }

    #[tokio::test]
    async fn private_requests_require_login_before_polling_the_network_future() {
        let service = service_for_test();
        let polled = Arc::new(AtomicBool::new(false));
        let future_polled = polled.clone();

        let result = service
            .private_request(async move {
                future_polled.store(true, Ordering::Release);
                Ok::<(), netodia_net::netease::Error>(())
            })
            .await;

        assert!(result.is_err());
        assert!(!polled.load(Ordering::Acquire));
    }

    #[tokio::test]
    async fn public_inputs_are_validated_without_initializing_a_guest_session() {
        let service = service_for_test();

        assert!(matches!(
            service.search("  ", SearchKind::Songs, 0).await,
            Err(AppError::Validation(_))
        ));
        assert!(matches!(service.songs(&[1, 0]).await, Err(AppError::Validation(_))));
        assert!(!service.guest_initialized.load(Ordering::Acquire));
    }

    #[tokio::test]
    async fn cancelled_account_generation_does_not_poll_a_request() {
        let service = service_for_test();
        service.account_tasks_token().cancel();
        let polled = Arc::new(AtomicBool::new(false));
        let future_polled = polled.clone();

        let result = service
            .account_request(async move {
                future_polled.store(true, Ordering::Release);
                Ok::<(), netodia_net::netease::Error>(())
            })
            .await;

        assert!(result.is_err());
        assert!(!polled.load(Ordering::Acquire));
    }

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
