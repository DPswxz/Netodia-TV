package com.netodia.tv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bounded artwork cache; artwork requests never share the music/API worker. */
final class Artwork {
  private static final ExecutorService IO = Executors.newFixedThreadPool(3);
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static final LruCache<String, Bitmap> CACHE =
      new LruCache<String, Bitmap>(16 * 1024) {
        protected int sizeOf(String key, Bitmap value) {
          return value.getByteCount() / 1024;
        }
      };

  interface Loaded {
    void ready(Bitmap image);
  }

  static void load(ImageView view, String raw) {
    load(view, raw, null);
  }

  static void load(ImageView view, String raw, Loaded loaded) {
    view.setTag(raw);
    view.setImageResource(
        view.getResources().getIdentifier("logo", "drawable", view.getContext().getPackageName()));
    boolean local = raw.startsWith("content://");
    if (!local && !raw.startsWith("https://") && !raw.startsWith("http://")) return;
    int requested =
        view.getLayoutParams() != null && view.getLayoutParams().width > 0
            ? view.getLayoutParams().width
            : 360;
    int imageSize = Math.max(128, Math.min(480, requested));
    String url =
        local
            ? raw
            : raw + (raw.contains("?") ? "&" : "?") + "param=" + imageSize + "y" + imageSize;
    Bitmap cached = CACHE.get(url);
    if (cached != null) {
      view.setImageBitmap(cached);
      if (loaded != null) loaded.ready(cached);
      return;
    }
    IO.execute(
        () -> {
          HttpURLConnection connection = null;
          try {
            InputStream source;
            if (local)
              source =
                  view.getContext()
                      .getContentResolver()
                      .openInputStream(android.net.Uri.parse(raw));
            else {
              connection = (HttpURLConnection) new URL(url).openConnection();
              connection.setConnectTimeout(7000);
              connection.setReadTimeout(7000);
              if (connection.getResponseCode() != 200) return;
              source = connection.getInputStream();
            }
            if (source == null) return;
            byte[] bytes;
            try (InputStream in = source;
                ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
              byte[] part = new byte[8192];
              int n;
              while ((n = in.read(part)) >= 0) {
                if (buffer.size() + n > 4 * 1024 * 1024) return;
                buffer.write(part, 0, n);
              }
              bytes = buffer.toByteArray();
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return;
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 640)
              options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (bitmap == null) return;
            CACHE.put(url, bitmap);
            MAIN.post(
                () -> {
                  if (raw.equals(view.getTag())) {
                    view.setImageBitmap(bitmap);
                    if (loaded != null) loaded.ready(bitmap);
                  }
                });
          } catch (Exception ignored) {
            /* A missing cover must not block music or focus. */
          } finally {
            if (connection != null) connection.disconnect();
          }
        });
  }
}
