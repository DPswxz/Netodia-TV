package com.netodia.tv;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Artwork colors painted with the original AuroraBackdrop's three sweep directions. */
final class ArtworkBackdrop extends Drawable {
  private final int base, bandHeight;
  private final float radius;
  private final int[][] samples = new int[4][];
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final int[] colors = new int[3];
  private boolean hasArtwork;

  ArtworkBackdrop(int base, int bandHeight, float radius) {
    this.base = base;
    this.radius = radius;
    this.bandHeight = bandHeight;
  }

  void artwork(int slot, Bitmap bitmap) {
    int[] pixels = new int[256];
    for (int y = 0; y < 16; y++)
      for (int x = 0; x < 16; x++)
        pixels[y * 16 + x] =
            bitmap.getPixel(
                Math.min(bitmap.getWidth() - 1, x * bitmap.getWidth() / 16),
                Math.min(bitmap.getHeight() - 1, y * bitmap.getHeight() / 16));
    samples[slot] = pixels;
    int[] counts = new int[4096], red = new int[4096], green = new int[4096], blue = new int[4096];
    for (int[] source : samples)
      if (source != null)
        for (int color : source) {
          int r = Color.red(color),
              g = Color.green(color),
              b = Color.blue(color),
              bin = (r >> 4) * 256 + (g >> 4) * 16 + (b >> 4);
          counts[bin]++;
          red[bin] += r;
          green[bin] += g;
          blue[bin] += b;
        }
    for (int i = 0; i < 3; i++) {
      int best = 0;
      for (int n = 1; n < counts.length; n++) if (counts[n] > counts[best]) best = n;
      if (counts[best] == 0) {
        colors[i] = i == 0 ? base : colors[0];
        continue;
      }
      int r = red[best] / counts[best],
          g = green[best] / counts[best],
          b = blue[best] / counts[best];
      colors[i] = Color.rgb(r, g, b);
      for (int n = 0; n < counts.length; n++)
        if (counts[n] > 0) {
          int dr = red[n] / counts[n] - r,
              dg = green[n] / counts[n] - g,
              db = blue[n] / counts[n] - b;
          if (dr * dr + dg * dg + db * db < 3600) counts[n] = 0;
        }
    }
    hasArtwork = true;
    invalidateSelf();
  }

  @Override
  public void draw(Canvas canvas) {
    Rect bounds = getBounds();
    float w = bounds.width(),
        h = bandHeight > 0 ? Math.min(bandHeight, bounds.height()) : bounds.height();
    canvas.save();
    canvas.translate(bounds.left, bounds.top);
    paint.setShader(null);
    paint.setColor(base);
    canvas.drawRect(0, 0, w, bounds.height(), paint);
    if (hasArtwork) {
      float[] headings = {336, 217, 127};
      for (int i = 0; i < 3; i++) {
        int tint = colors[2 - i];
        double angle = Math.toRadians(headings[i]);
        float dx = (float) Math.sin(angle),
            dy = -(float) Math.cos(angle),
            length = Math.abs(w * dx) + Math.abs(h * dy);
        paint.setColor(Color.WHITE);
        paint.setShader(
            new LinearGradient(
                w / 2 - dx * length / 2,
                h / 2 - dy * length / 2,
                w / 2 + dx * length / 2,
                h / 2 + dy * length / 2,
                new int[] {(tint & 0xffffff) | 0x8c000000, tint & 0xffffff},
                null,
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
      }
    }
    paint.setShader(null);
    canvas.restore();
  }

  @Override
  public void getOutline(Outline outline) {
    outline.setRoundRect(getBounds(), radius);
    outline.setAlpha(1);
  }

  @Override
  public void setAlpha(int alpha) {}

  @Override
  public void setColorFilter(ColorFilter filter) {}

  @Override
  public int getOpacity() {
    return PixelFormat.OPAQUE;
  }
}
