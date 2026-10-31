package com.netodia.tv;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.media.audiofx.Visualizer;
import android.view.View;

/** Captures only this player's nonzero audio session; never the microphone or global mix. */
final class AudioVisualization extends View {
  private final SharedPreferences prefs;
  private final Runnable unavailable;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path = new Path();
  private final float[] bars = new float[48];
  private final byte[] fft = new byte[512], wave = new byte[512];
  private Visualizer capture;
  private int session = -1, failedSession = -1;
  private boolean attached;

  AudioVisualization(Context context, Runnable unavailable) {
    super(context);
    prefs = context.getSharedPreferences("music", 0);
    this.unavailable = unavailable;
    java.util.Arrays.fill(wave, (byte) 128);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    setFocusable(false);
  }

  void configure() {
    setVisibility(prefs.getBoolean("visualizer_enabled", false) ? VISIBLE : GONE);
    release();
    failedSession = -1;
    removeCallbacks(frame);
    if (attached && getWindowVisibility() == VISIBLE) post(frame);
    invalidate();
  }

  private void release() {
    if (capture != null) {
      try { capture.setEnabled(false); } catch (RuntimeException ignored) {}
      capture.release(); capture = null;
    }
    session = -1;
  }

  @Override protected void onAttachedToWindow() {
    super.onAttachedToWindow(); attached = true; configure();
  }
  @Override protected void onDetachedFromWindow() {
    attached = false; removeCallbacks(frame); release(); super.onDetachedFromWindow();
  }
  @Override protected void onWindowVisibilityChanged(int visibility) {
    super.onWindowVisibilityChanged(visibility);
    if (frame == null) return;
    removeCallbacks(frame);
    if (visibility == VISIBLE && attached) post(frame); else release();
  }

  private final Runnable frame = new Runnable() {
    @Override public void run() {
      if (!attached || getWindowVisibility() != VISIBLE || getVisibility() != VISIBLE) return;
      PlaybackService service = PlaybackService.instance;
      int id = service == null ? 0 : service.audioSessionId();
      if (capture != null && id != session) release();
      boolean playing = service != null && service.playing();
      if (!playing && capture != null) release();
      if (playing && id > 0 && capture == null && id != failedSession
          && getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
        try {
          capture = new Visualizer(id);
          if (capture.setCaptureSize(fft.length) != Visualizer.SUCCESS) throw new IllegalStateException();
          capture.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
          if (capture.setEnabled(true) != Visualizer.SUCCESS) throw new IllegalStateException();
          session = id;
        } catch (RuntimeException error) {
          release(); failedSession = id; unavailable.run();
        }
      }
      boolean fresh = playing && capture != null;
      if (fresh) {
        try {
          fresh = capture.getFft(fft) == Visualizer.SUCCESS;
          capture.getWaveForm(wave);
        } catch (RuntimeException error) { release(); fresh = false; }
      }
      for (int i = 0; i < bars.length; i++) {
        float value = 0;
        if (fresh) value = SpectrumData.band(fft, i, bars.length);
        bars[i] += (value - bars[i]) * (value > bars[i] ? .7f : .22f);
      }
      if (!fresh) java.util.Arrays.fill(wave, (byte) 128);
      invalidate(); postDelayed(this, playing ? 50 : 200);
    }
  };

  @Override protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float w = getWidth(), h = getHeight();
    paint.setColor(TvUi.TEXT); paint.setAlpha(190); paint.setStyle(Paint.Style.FILL);
    int style = prefs.getInt("visualizer_style", 0);
    if (style == 2) {
      path.reset();
      for (int i = 0; i < 160; i++) {
        float x = w * i / 159f;
        float y = h / 2 + SpectrumData.sample(wave[i * (wave.length - 1) / 159]) * h * .45f;
        if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
      }
      paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(TvUi.dp(getContext(), 2));
      canvas.drawPath(path, paint); return;
    }
    float step = w / bars.length, gap = Math.max(2, step * .28f);
    for (int i = 0; i < bars.length; i++) {
      float height = Math.max(TvUi.dp(getContext(), 2), bars[i] * h * (style == 1 ? .46f : .95f));
      float bottom = style == 1 ? h / 2 + height : h;
      float top = style == 1 ? h / 2 - height : h - height;
      canvas.drawRect(i * step, top, (i + 1) * step - gap, bottom, paint);
    }
  }
}
