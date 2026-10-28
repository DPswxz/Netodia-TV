package com.netodia.tv;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/** The original equalizer-style play-button animation (not an audio spectrum). */
final class PlaybackBarsDrawable extends Drawable implements Runnable {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

  PlaybackBarsDrawable(int color) {
    paint.setColor(color);
  }

  @Override
  public void draw(Canvas canvas) {
    float side = Math.min(getBounds().width(), getBounds().height());
    float middleX = getBounds().exactCenterX(), middleY = getBounds().exactCenterY();
    double time = SystemClock.uptimeMillis() / 220.0;
    for (int i = 0; i < 4; i++) {
      float x = middleX + (i - 1.5f) * side * .11f;
      float h = side * (.16f + .24f * (float) ((Math.sin(time + i * 1.7) + 1) / 2));
      canvas.drawRoundRect(
          x - side * .025f,
          middleY - h / 2,
          x + side * .025f,
          middleY + h / 2,
          side * .025f,
          side * .025f,
          paint);
    }
    scheduleSelf(this, SystemClock.uptimeMillis() + 33);
  }

  @Override
  public void run() {
    invalidateSelf();
  }

  @Override
  public void setAlpha(int alpha) {
    paint.setAlpha(alpha);
  }

  @Override
  public void setColorFilter(ColorFilter filter) {
    paint.setColorFilter(filter);
  }

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }
}
