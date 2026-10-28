package com.netodia.tv;

import static com.netodia.tv.TvUi.*;

import android.app.*;
import android.graphics.Rect;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;

/** Desktop-style bottom-right playback menu and left-opening submenus, operable by D-pad. */
final class PlayerMenu {
  private final Activity host;
  private final PlaybackService player;
  private final Runnable equalizer;
  private final Rect bounds;
  private final Dialog main;
  private Dialog child;
  private final ArrayList<View> rows = new ArrayList<>();
  private View origin;
  private int customMinutes = 10;

  private int d(float value) {
    return dp(host, value);
  }

  private LinearLayout.LayoutParams lp(int width, int height) {
    return new LinearLayout.LayoutParams(
        width < 0 ? width : d(width), height < 0 ? height : d(height));
  }

  PlayerMenu(Activity host, PlaybackService player, Rect bounds, Runnable equalizer) {
    this.host = host;
    this.player = player;
    this.bounds = bounds;
    this.equalizer = equalizer;
    main = dialog();
    LinearLayout panel = panel();
    main.setContentView(panel);
    String[] icons = {"speed", "bedtime", "graphic_eq", "graphic_eq"};
    String[] names = {"速度", "睡眠定时器", "均衡器", "音质选择"};
    android.content.SharedPreferences prefs = host.getSharedPreferences("music", 0);
    String sleep = player.stopAtEnd ? "曲目结束时" : player.sleepDeadline > 0 ? "已开启" : "关";
    String quality = prefs.getString("quality", "exhigh");
    String qualityLabel =
        quality.equals("standard")
            ? "标准"
            : quality.equals("higher")
                ? "较高"
                : quality.equals("lossless") ? "无损" : quality.equals("hires") ? "Hi-Res" : "极高";
    String[] values = {
      player.speed() + "x", sleep, prefs.getBoolean("eq_enabled", false) ? "开" : "关", qualityLabel
    };
    for (int i = 0; i < names.length; i++) {
      final int at = i;
      LinearLayout row = row(host);
      row.setPadding(d(8), 0, d(7), 0);
      row.setBackground(focus(host, true));
      row.setFocusableInTouchMode(true);
      row.setId(View.generateViewId());
      row.addView(glyph(host, icons[i], 23, TEXT), lp(28, 30));
      TextView title = text(host, names[i], 17, TEXT);
      title.setPadding(d(8), 0, d(6), 0);
      row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
      row.addView(text(host, values[i], 14, MUTED));
      row.addView(glyph(host, "chevron_left", 22, MUTED), lp(24, 28));
      panel.addView(row, lp(-1, 44));
      rows.add(row);
      row.setContentDescription(names[i] + "，" + values[i]);
      row.setOnClickListener(v -> open(at));
      row.setOnKeyListener(
          (v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
              open(at);
              return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_UP && at == 0) return true;
            if (key == KeyEvent.KEYCODE_DPAD_DOWN && at == names.length - 1) return true;
            return false;
          });
    }
    for (int i = 0; i < rows.size(); i++) {
      if (i > 0) rows.get(i).setNextFocusUpId(rows.get(i - 1).getId());
      if (i + 1 < rows.size()) rows.get(i).setNextFocusDownId(rows.get(i + 1).getId());
    }
    main.setOnDismissListener(
        v -> {
          if (child != null && child.isShowing()) child.dismiss();
          if (origin != null && origin.isAttachedToWindow()) origin.requestFocus();
        });
  }

  private Dialog dialog() {
    Dialog d = new Dialog(host);
    d.requestWindowFeature(Window.FEATURE_NO_TITLE);
    return d;
  }

  private LinearLayout panel() {
    LinearLayout p = column(host);
    p.setPadding(d(4), d(6), d(4), d(6));
    android.graphics.drawable.GradientDrawable frame = fill(host, MANTLE, 10);
    frame.setStroke(d(1), BORDER);
    p.setBackground(frame);
    return p;
  }

  private void place(Dialog dialog, int width, int height, int x) {
    Window w = dialog.getWindow();
    if (w == null) return;
    w.setBackgroundDrawableResource(android.R.color.transparent);
    w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    w.setLayout(width, height);
    WindowManager.LayoutParams params = w.getAttributes();
    params.gravity = Gravity.TOP | Gravity.LEFT;
    params.x = x;
    params.y = Math.max(bounds.top, bounds.bottom - height - d(4));
    w.setAttributes(params);
  }

  void show() {
    origin = host.getCurrentFocus();
    main.show();
    place(main, d(242), d(rows.size() * 44 + 12), bounds.right - d(246));
    rows.get(1).requestFocus();
  }

  boolean isShowing() {
    return main.isShowing();
  }

  void dismiss() {
    main.dismiss();
  }

  private void open(int index) {
    if (child != null && child.isShowing()) child.dismiss();
    if (index == 0) {
      choices(
          "速度",
          new String[] {"0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x"},
          at -> player.speed(new float[] {.5f, .75f, 1f, 1.25f, 1.5f, 2f}[at]));
    } else if (index == 1) sleepMenu();
    else if (index == 2) {
      dismiss();
      equalizer.run();
    } else
      choices(
          "音质选择",
          new String[] {"标准", "较高", "极高", "无损", "Hi-Res"},
          at ->
              host.getSharedPreferences("music", 0)
                  .edit()
                  .putString(
                      "quality",
                      new String[] {"standard", "higher", "exhigh", "lossless", "hires"}[at])
                  .apply());
  }

  interface Selection {
    void select(int at);
  }

  private void choices(String title, String[] labels, Selection selection) {
    child = dialog();
    LinearLayout panel = panel();
    for (int i = 0; i < labels.length; i++) {
      final int at = i;
      Button option =
          button(
              host,
              labels[i],
              () -> {
                selection.select(at);
                dismiss();
              });
      option.setFocusableInTouchMode(true);
      option.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
      option.setBackground(focus(host, true));
      panel.addView(option, lp(-1, 42));
    }
    child.setContentView(panel);
    child.setOnDismissListener(
        v -> {
          if (main.isShowing()) rows.get(title.equals("速度") ? 0 : rows.size() - 1).requestFocus();
        });
    child.show();
    place(child, d(180), d(labels.length * 42 + 12), bounds.right - d(434));
    panel.getChildAt(0).requestFocus();
  }

  private void sleepMenu() {
    child = dialog();
    LinearLayout panel = panel();
    String[] values = {"关", "15 分钟", "30 分钟", "45 分钟", "60 分钟", "90 分钟", "曲目结束时"};
    int[] minutes = {0, 15, 30, 45, 60, 90};
    for (int i = 0; i < values.length; i++) {
      final int at = i;
      Button option =
          button(
              host,
              values[i],
              () -> {
                if (at == 6) player.sleepAtEnd();
                else player.sleep(minutes[at]);
                dismiss();
              });
      option.setFocusableInTouchMode(true);
      option.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
      option.setBackground(focus(host, true));
      if (i == 0 && player.sleepDeadline == 0 && !player.stopAtEnd) option.setTextColor(ACCENT);
      panel.addView(option, lp(-1, 40));
    }
    View line = new View(host);
    line.setBackgroundColor(BORDER);
    panel.addView(line, lp(-1, 1));
    LinearLayout custom = row(host);
    custom.setGravity(Gravity.CENTER);
    panel.addView(custom, lp(-1, 42));
    TextView amount = text(host, "10:00", 17, TEXT);
    amount.setGravity(Gravity.CENTER);
    Button minus =
        button(
            host,
            "−",
            () -> {
              customMinutes = Math.max(1, customMinutes - 1);
              setText(amount, customMinutes + ":00");
            });
    minus.setFocusableInTouchMode(true);
    minus.setBackground(focus(host, true));
    custom.addView(minus, lp(36, 36));
    custom.addView(amount, new LinearLayout.LayoutParams(0, -2, 1));
    Button plus =
        button(
            host,
            "+",
            () -> {
              customMinutes = Math.min(240, customMinutes + 1);
              setText(amount, customMinutes + ":00");
            });
    plus.setFocusableInTouchMode(true);
    plus.setBackground(focus(host, true));
    custom.addView(plus, lp(36, 36));
    Button start =
        button(
            host,
            "开始",
            () -> {
              player.sleep(customMinutes);
              dismiss();
            });
    start.setTextColor(MANTLE);
    start.setBackground(fill(host, ACCENT, 24));
    start.setFocusableInTouchMode(true);
    panel.addView(start, lp(-1, 34));
    child.setContentView(panel);
    child.setOnDismissListener(
        v -> {
          if (main.isShowing()) rows.get(1).requestFocus();
        });
    child.show();
    place(child, d(190), d(7 * 40 + 12 + 42 + 34 + 1), bounds.right - d(444));
    panel.getChildAt(0).requestFocus();
  }
}
