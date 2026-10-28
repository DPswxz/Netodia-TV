package com.netodia.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.widget.LinearLayout;

/** Draw the frame after backgrounds, artwork, children and scrollbars. */
final class ContentPanel extends LinearLayout {
  private boolean artworkPage;

  void setArtworkPage(boolean value) {
    artworkPage = value;
    invalidate();
  }

  private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF rect = new RectF();

  ContentPanel(Context context) {
    super(context);
    setOrientation(VERTICAL);
    frame.setStyle(Paint.Style.STROKE);
  }

  @Override
  public void draw(Canvas canvas) {
    super.draw(canvas);
    float width = TvUi.dp(getContext(), 1);
    float inset = width / 2 + .25f;
    rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
    frame.setStrokeWidth(width);
    int base = TvUi.BASE;
    frame.setColor(
        Color.red(base) + Color.green(base) + Color.blue(base) > 450
            ? 0x18000000
            : artworkPage ? 0x0cffffff : 0x24ffffff);
    canvas.drawRoundRect(
        rect, TvUi.dp(getContext(), 8) - inset, TvUi.dp(getContext(), 8) - inset, frame);
  }
}
