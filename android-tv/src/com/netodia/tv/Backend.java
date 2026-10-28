package com.netodia.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

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
            value = raw(operation, input);
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
    request(
        "session",
        json(),
        (data, error) -> {
          if (error == null) {
            JSONObject s = (JSONObject) data;
            session
                .edit()
                .putString("cookie", s.optString("cookie"))
                .putString("device", s.optString("device"))
                .apply();
          }
        });
  }

  void clearSession() {
    session.edit().remove("cookie").apply();
  }

  boolean hasSession() {
    return !session.getString("cookie", "").isEmpty();
  }
}
