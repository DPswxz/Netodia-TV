package com.netodia.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Application lifetime network bridge. Never holds an Activity or blocks its main thread. */
final class Backend {
  interface Callback {
    void done(Object data, String error);
  }

  private static Backend instance;
  private final ExecutorService io = Executors.newFixedThreadPool(3);
  private final Handler main = new Handler(Looper.getMainLooper());
  private final CountDownLatch ready = new CountDownLatch(1);
  private final SharedPreferences session;
  private volatile String initError;
  private volatile long nativeAccountId;
  private final ReentrantReadWriteLock accountLock = new ReentrantReadWriteLock(true);
  private final Context appContext;

  static synchronized Backend get(Context context) {
    if (instance == null) instance = new Backend(context.getApplicationContext());
    return instance;
  }

  private Backend(Context context) {
    appContext=context;
    session = context.getSharedPreferences("session", Context.MODE_PRIVATE);
    io.execute(
        () -> {
          try {
            JSONObject restored =
                (JSONObject)
                    raw(
                        "restore",
                        json(
                            "cookie",
                            session.getString("cookie", ""),
                            "device",
                            session.getString("device", "")));
            session.edit().putString("device", restored.optString("device")).apply();
          } catch (Exception e) {
            initError = e.getMessage();
          } finally {
            ready.countDown();
          }
        });
  }

  static JSONObject json(Object... fields) {
    JSONObject result = new JSONObject();
    try {
      for (int i = 0; i < fields.length; i += 2) result.put(fields[i].toString(), fields[i + 1]);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON arguments", e);
    }
    return result;
  }

  private Object raw(String operation, JSONObject input) throws Exception {
    JSONObject reply = new JSONObject(Native.call(operation, input.toString()));
    if (!reply.optBoolean("ok")) throw new Exception(reply.optString("error", "请求失败"));
    return reply.get("data");
  }

  void request(String operation, JSONObject input, Callback callback) {
    io.execute(
        () -> {
          long started=android.os.SystemClock.elapsedRealtime();
          Object value = null;
          String error = null;
          try {
            ready.await();
            if (initError != null) throw new Exception(initError);
            boolean auth = operation.equals("restore") || operation.equals("login_phone") || operation.equals("login_email") || operation.equals("qr_check") || operation.equals("logout");
            java.util.concurrent.locks.Lock lock = auth ? accountLock.writeLock() : accountLock.readLock();
            lock.lock();
            try {
              boolean login = operation.equals("login_phone") || operation.equals("login_email");
              JSONObject previous = login ? (JSONObject) raw("session", json()) : null;
              long previousId = nativeAccountId;
              try {
                if (login) nativeAccountId = -1;
                value = raw(operation, input);
                if (operation.equals("account") || login) nativeAccountId = ((JSONObject) value).optLong("userId");
                else if (operation.equals("logout") || operation.equals("restore")) nativeAccountId = 0;
                else if (operation.equals("qr_check") && ((JSONObject) value).optString("status").equals("authorized")) nativeAccountId = -1;
              } catch (Exception failed) {
                if (login) { raw("restore", previous); nativeAccountId = previousId; }
                throw failed;
              }
            } finally { lock.unlock(); }
          } catch (Exception e) {
            error = e.getMessage() == null ? "请求失败，请重试" : e.getMessage();
          }
          Diagnostics.record(appContext,"request "+operation+" "+(error==null?"ok":"failed")+" "+(android.os.SystemClock.elapsedRealtime()-started)+"ms",error==null);
          Object result = value;
          String failure = error;
          main.post(() -> callback.done(result, failure));
        });
  }

  void saveSession() {
    io.execute(() -> {
      try {
        ready.await(); accountLock.readLock().lock();
        try {
          JSONObject snapshot = (JSONObject) raw("session", json());
          session.edit().putString("cookie", snapshot.optString("cookie"))
              .putString("device", snapshot.optString("device")).apply();
        } finally { accountLock.readLock().unlock(); }
      } catch (Exception ignored) {}
    });
  }

  private JSONObject accountRecords() {
    try { return new JSONObject(session.getString("accounts", "{}")); }
    catch (Exception invalid) { return new JSONObject(); }
  }

  JSONArray savedAccounts() {
    try { return SavedAccounts.metadata(accountRecords()); }
    catch (Exception invalid) { return new JSONArray(); }
  }

  private void storeAccount(long id, String nickname, JSONObject snapshot) throws Exception {
    synchronized (session) {
      if (id <= 0 || snapshot.optString("cookie").isEmpty()) return;
    JSONObject records = SavedAccounts.put(accountRecords(), id, nickname, snapshot.optString("cookie"), snapshot.optString("device"));
    session.edit().putString("accounts", records.toString()).putLong("account_id", id)
        .putString("cookie", snapshot.optString("cookie")).putString("device", snapshot.optString("device")).apply();
    }
  }

  void rememberAccount(long id, String nickname, Callback callback) {
    io.execute(() -> {
      String error = null;
      try {
        ready.await(); accountLock.writeLock().lock();
        try {
          if (nativeAccountId != id) throw new Exception("Account changed");
          storeAccount(id, nickname, (JSONObject) raw("session", json()));
        }
        finally { accountLock.writeLock().unlock(); }
      } catch (Exception failed) { error = "无法保存账号登录状态"; }
      String result = error;
      main.post(() -> callback.done(json("userId", id, "nickname", nickname), result));
    });
  }

  void restoreSavedAccount(long id) {
    io.execute(() -> {
      try {
        ready.await(); accountLock.writeLock().lock();
        try {
          JSONObject entry = accountRecords().optJSONObject(Long.toString(id));
          JSONObject snapshot = entry == null ? json("cookie", "", "device", session.getString("device", "")) : entry;
          raw("restore", snapshot); nativeAccountId = id;
          session.edit().putString("cookie", snapshot.optString("cookie")).putLong("account_id", id).apply();
        } finally { accountLock.writeLock().unlock(); }
      } catch (Exception ignored) {}
    });
  }

  void switchAccount(long id, Callback callback) {
    io.execute(() -> {
      JSONObject account = null;
      String error = null;
      try {
        ready.await(); accountLock.writeLock().lock();
        try {
          JSONObject target = accountRecords().optJSONObject(Long.toString(id));
          if (target == null || target.optString("cookie").isEmpty()) throw new Exception();
          JSONObject previous = (JSONObject) raw("session", json());
          long previousId = nativeAccountId;
          try {
            raw("restore", json("cookie", target.optString("cookie"), "device", target.optString("device")));
            account = (JSONObject) raw("account", json());
            if (account.optLong("userId") != id) throw new Exception();
            nativeAccountId = id;
            storeAccount(id, account.optString("nickname"), (JSONObject) raw("session", json()));
          } catch (Exception expired) {
            raw("restore", previous); nativeAccountId = previousId;
            throw expired;
          }
        } finally { accountLock.writeLock().unlock(); }
      } catch (Exception failed) { error = "切换失败，登录状态可能已过期，请重新添加此账号"; }
      JSONObject result = account; String failure = error;
      main.post(() -> callback.done(result, failure));
    });
  }

  void forgetAccount(long id) {
    synchronized (session) {
      JSONObject records = accountRecords(); records.remove(Long.toString(id));
      session.edit().putString("accounts", records.toString()).apply();
    }
  }

  void clearSession() {
    synchronized (session) {
      forgetAccount(session.getLong("account_id", 0));
      session.edit().remove("cookie").remove("account_id").apply();
    }
  }

  boolean hasSession() {
    return !session.getString("cookie", "").isEmpty();
  }
}
