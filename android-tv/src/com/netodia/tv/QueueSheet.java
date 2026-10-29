package com.netodia.tv;

import static com.netodia.tv.TvUi.*;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;

/** The original floating queue sheet, with explicit remote routes for every row action. */
final class QueueSheet {
  interface Menu {
    void open(JSONObject song, int index);
  }

  private final Activity host;
  private final PlaybackService player;
  private final android.graphics.Rect panelBounds;
  private final Menu menu;
  private final java.util.function.Consumer<JSONObject> like;
  private final Runnable clear;
  private final Dialog dialog;
  private final Handler main = new Handler(Looper.getMainLooper());
  private LinearLayout rows;
  private TextView count;
  private Button clearButton;
  private View origin;
  private int lastRevision = -1, lastIndex = -2, visible = 80;
  private ScrollView scroll;
  private boolean rebuilding;
  private View focusedPart;
  private final ArrayList<View> tracks = new ArrayList<>();
  private final Runnable listener = this::refresh;
  private final Set<Integer> selected = new TreeSet<>();

  private int d(float value) {
    return dp(host, value);
  }

  private LinearLayout.LayoutParams lp(int width, int height) {
    return new LinearLayout.LayoutParams(
        width < 0 ? width : d(width), height < 0 ? height : d(height));
  }

  QueueSheet(
      Activity host,
      PlaybackService player,
      android.graphics.Rect panelBounds,
      Menu menu,
      java.util.function.Consumer<JSONObject> like,
      Runnable clear) {
    this.host = host;
    this.player = player;
    this.panelBounds = panelBounds;
    this.menu = menu;
    this.like = like;
    this.clear = clear;
    dialog = new Dialog(host);
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    LinearLayout panel = column(host);
    android.graphics.drawable.GradientDrawable frame = fill(host, MANTLE, 16);
    frame.setStroke(d(1), BORDER);
    panel.setBackground(frame);
    panel.setClipToOutline(true);
    LinearLayout header = row(host);
    header.setPadding(d(20), d(10), d(16), d(10));
    panel.addView(header, lp(-1, 60));
    LinearLayout title = column(host);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    TextView name = text(host, "队列", 20, TEXT);
    heading(name);
    title.addView(name);
    count = text(host, "", 14, MUTED);
    title.addView(count);
    clearButton =
        button(
            host,
            "清空队列",
            () -> {
              if (selected.isEmpty()) {
                clear.run();
                return;
              }
              ArrayList<Integer> all = new ArrayList<>(selected);
              Collections.sort(all, Collections.reverseOrder());
              selected.clear();
              for (int at : all) player.remove(at);
              lastRevision = -1;
              refresh();
            });
    clearButton.setTextSize(13 * SCALE);
    clearButton.setPadding(d(12), 0, d(12), 0);
    clearButton.setBackground(focus(host, true));
    header.addView(clearButton, lp(94, 36));
    Button close = icon("close", "关闭队列", this::dismiss);
    header.addView(close, lp(32, 36));
    View line = new View(host);
    line.setBackgroundColor(BORDER);
    panel.addView(line, lp(-1, 1));
    scroll = new ScrollView(host);
    scroll.setFocusable(false);
    scroll.setOnScrollChangeListener(
        (v, x, y, ox, oy) -> {
          if (!rebuilding
              && y > oy
              && rows != null
              && rows.getHeight() - y - v.getHeight() < d(120)) more();
        });
    scroll.setFillViewport(true);
    scroll.setPadding(d(8), 0, d(8), 0);
    panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    rows = column(host);
    rows.setPadding(0, d(8), 0, d(8));
    scroll.addView(rows);
    dialog.setContentView(panel);
    dialog.setOnDismissListener(
        ignored -> {
          player.unobserve(listener);
          if (origin != null && origin.isAttachedToWindow()) origin.requestFocus();
        });
    clearButton.setNextFocusRightId(close.getId());
    close.setNextFocusLeftId(clearButton.getId());
    close.setOnKeyListener(
        (v, key, event) -> {
          if (event.getAction() == KeyEvent.ACTION_DOWN
              && key == KeyEvent.KEYCODE_DPAD_DOWN
              && !tracks.isEmpty()) {
            focusSelection(0);
            return true;
          }
          return false;
        });
    clearButton.setOnKeyListener(
        (v, key, event) -> {
          if (event.getAction() == KeyEvent.ACTION_DOWN
              && key == KeyEvent.KEYCODE_DPAD_DOWN
              && !tracks.isEmpty()) {
            focusSelection(0);
            return true;
          }
          return false;
        });
  }

  private Button icon(String glyph, String label, Runnable action) {
    Button b = button(host, "", action);
    TvUi.icon(b, glyph);
    b.setPadding(0, 0, 0, 0);
    b.setTextSize(21 * SCALE);
    b.setFocusableInTouchMode(true);
    b.setBackground(focus(host, true));
    b.setContentDescription(label);
    return b;
  }

  void show() {
    origin = host.getCurrentFocus();
    visible = Math.max(80, player.index() + 40);
    dialog.show();
    Window w = dialog.getWindow();
    if (w != null) {
      int width = Math.min(d(640), Math.max(d(400), panelBounds.width() / 2));
      int height = (int) (panelBounds.height() * .8);
      int y = panelBounds.bottom - height;
      w.setBackgroundDrawableResource(android.R.color.transparent);
      w.setDimAmount(.22f);
      w.setLayout(width, height);
      WindowManager.LayoutParams params = w.getAttributes();
      params.gravity = Gravity.TOP | Gravity.LEFT;
      params.x = panelBounds.left + (panelBounds.width() - width) / 2;
      params.y = y;
      w.setAttributes(params);
    }
    player.observe(listener);
    refresh();
    main.post(
        () -> {
          if (!tracks.isEmpty()) {
            if (!focusSelection(player.index())) focusSelection(0);
          } else clearButton.requestFocus();
        });
  }

  boolean isShowing() {
    return dialog.isShowing();
  }

  void dismiss() {
    dialog.dismiss();
  }

  void refreshFavorites() {
    if (!dialog.isShowing() || rows == null) return;
    for (int i = 0; i < Math.min(visible, player.queue().length()); i++) {
      View view = rows.findViewWithTag("favorite:" + i);
      if (view instanceof Button) {
        boolean liked = MusicData.favorite(host, player.queue().optJSONObject(i));
        ((Button) view).setTextColor(liked ? ACCENT : TEXT);
        ((Button) view).setTypeface(liked ? TvUi.filled : TvUi.symbols);
      }
    }
  }

  private void refresh() {
    if (!dialog.isShowing()) return;
    if (lastRevision == player.queueRevision && lastIndex == player.index()) return;
    lastRevision = player.queueRevision;
    lastIndex = player.index();
    View old = dialog.getCurrentFocus();
    Object oldTag = old == null ? null : old.getTag();
    int oldScroll = scroll.getScrollY();
    rebuilding = true;
    rows.removeAllViews();
    tracks.clear();
    focusedPart = null;
    setText(count, player.queue().length() + " 首曲目");
    setText(clearButton, selected.isEmpty() ? "清空队列" : "移除选中(" + selected.size() + ")");
    if (player.queue().length() == 0) {
      TextView empty = text(host, "播放队列为空", 18, MUTED);
      empty.setGravity(Gravity.CENTER);
      empty.setPadding(0, d(50), 0, 0);
      rows.addView(empty);
      return;
    }
    visible = Math.max(visible, player.index() + 40);
    int end = Math.min(visible, player.queue().length());
    appendRows(0, end);
    main.post(
        () -> {
          scroll.scrollTo(0, oldScroll);
          rebuilding = false;
        });
    View focusAfterBuild = dialog.getCurrentFocus();
    if (oldTag != null)
      main.post(
          () -> {
            if (dialog.getCurrentFocus() != focusAfterBuild) return;
            View restored = rows.findViewWithTag(oldTag);
            if (restored != null) restored.requestFocus();
            else if (!tracks.isEmpty()) focusSelection(tracks.size() - 1);
          });
  }

  private void appendRows(int from, int end) {
    for (int i = from; i < end; i++) {
      final int at = i;
      JSONObject song = player.queue().optJSONObject(i);
      if (song == null) continue;
      boolean active = i == player.index();
      LinearLayout item = row(host);
      item.setPadding(d(8), d(5), d(8), d(5));
      item.setBackground(focus(host, true));
      item.setAddStatesFromChildren(true);
      item.setTag(i);
      rows.addView(item, lp(-1, 50));
      SelectionMarker select = new SelectionMarker(at);
      select.setFocusableInTouchMode(true);
      select.setId(View.generateViewId());
      select.setContentDescription("选择 " + song.optString("name"));
      select.setOnClickListener(v -> {
        // Selection changes no queue data: keep this view and scroll position intact.
        select.requestFocus();
        focusedPart = select;
        if (!selected.add(at)) selected.remove(at);
        setText(clearButton, selected.isEmpty() ? "清空队列" : "移除选中(" + selected.size() + ")");
        refreshFocusActions();
        select.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
      });
      select.setTag("select:" + i);
      item.addView(select, lp(24, 36));
      ImageView image = cover(host, 36);
      item.addView(image);
      Artwork.load(image, MusicData.cover(song));
      LinearLayout mainSong = row(host);
      mainSong.setPadding(d(11), 0, d(8), 0);
      item.addView(mainSong, new LinearLayout.LayoutParams(0, -1, 1));
      mainSong.setFocusableInTouchMode(true);
      TvUi.eagerTouchFocus(mainSong);
      mainSong.setFocusable(true);
      mainSong.setId(View.generateViewId());
      mainSong.setTag("track:" + i);
      mainSong.setContentDescription((i + 1) + "，" + song.optString("name"));
      mainSong.setBackgroundColor(Color.TRANSPARENT);
      TextView name = text(host, song.optString("name"), 16, active ? ACCENT : TEXT);
      name.setSingleLine();
      name.setEllipsize(android.text.TextUtils.TruncateAt.END);
      mainSong.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
      TextView duration = text(host, MusicData.time((int) song.optLong("dt")), 12, TEXT);
      duration.setGravity(Gravity.RIGHT);
      mainSong.addView(duration, lp(43, -2));
      Button favorite =
          icon(
              "favorite",
              "喜欢 " + song.optString("name"),
              () -> {
                like.accept(song);
                lastRevision = -1;
                refresh();
              });
      favorite.setTextColor(MusicData.favorite(host, song) ? ACCENT : TEXT);
      favorite.setTypeface(MusicData.favorite(host, song) ? TvUi.filled : TvUi.symbols);
      favorite.setTag("favorite:" + i);
      item.addView(favorite, lp(29, 36));
      Button remove = icon("close", "移除 " + song.optString("name"), () -> player.remove(at));
      remove.setTag("remove:" + i);
      item.addView(remove, lp(29, 36));
      mainSong.setOnClickListener(v -> player.playIndex(at));
      mainSong.setOnLongClickListener(
          v -> {
            dismiss();
            menu.open(song, at);
            return true;
          });
      tracks.add(mainSong);
      favorite.setVisibility(View.INVISIBLE);
      remove.setVisibility(View.INVISIBLE);
      marker(select, at, selected.contains(at));
      for (View part : new View[] {mainSong, select, favorite, remove})
        part.setOnFocusChangeListener((v, has) -> {
          if (has) { focusedPart = v; refreshFocusActions(); }
          else main.post(this::refreshFocusActions);
        });
      mainSong.setOnKeyListener(
          (v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
              select.requestFocus();
              return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
              favorite.setVisibility(View.VISIBLE);
              remove.setVisibility(View.VISIBLE);
              favorite.requestFocus();
              refreshFocusActions();
              return true;
            }
            if (key == KeyEvent.KEYCODE_MENU) {
              mainSong.performLongClick();
              return true;
            }
            return vertical(at, key);
          });
      select.setNextFocusRightId(mainSong.getId());
      favorite.setNextFocusLeftId(mainSong.getId());
      favorite.setNextFocusRightId(remove.getId());
      remove.setNextFocusLeftId(favorite.getId());
      for (View action : new View[] {select, favorite, remove})
        action.setOnKeyListener(
            (v, key, event) -> {
              if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
              if (vertical(at, key)) return true;
              if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                (v == remove ? favorite : v == favorite ? mainSong : select).requestFocus();
                refreshFocusActions(); return true;
              }
              if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (v == select) mainSong.requestFocus();
                else { favorite.setVisibility(View.VISIBLE); remove.setVisibility(View.VISIBLE); remove.requestFocus(); }
                refreshFocusActions(); return true;
              }
              return false;
            });
    }
  }

  private void refreshFocusActions() {
    if (!dialog.isShowing()) return;
    for (int i = 0; i < rows.getChildCount(); i++) {
      View view = rows.getChildAt(i);
      if (!(view instanceof LinearLayout) || !(view.getTag() instanceof Integer)) continue;
      LinearLayout item = (LinearLayout) view;
      int at = (Integer) item.getTag();
      boolean active = focusedPart != null && focusedPart.isFocused()
          && (focusedPart == item.getChildAt(0) || focusedPart == item.getChildAt(2)
              || focusedPart == item.getChildAt(3) || focusedPart == item.getChildAt(4));
      marker((SelectionMarker) item.getChildAt(0), at, selected.contains(at) || active);
      item.getChildAt(3).setVisibility(active ? View.VISIBLE : View.INVISIBLE);
      item.getChildAt(4).setVisibility(active ? View.VISIBLE : View.INVISIBLE);
    }
  }

  private void marker(SelectionMarker select, int index, boolean checkbox) {
    select.checkbox = checkbox;
    select.invalidate();
  }

  private final class SelectionMarker extends View {
    final int index;
    boolean checkbox;
    final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    SelectionMarker(int index) { super(host); this.index = index; }
    @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
      super.onInitializeAccessibilityNodeInfo(info);
      info.setClassName(CheckBox.class.getName());
      info.setCheckable(true);
      info.setChecked(selected.contains(index));
    }
    @Override protected void onDraw(android.graphics.Canvas canvas) {
      float x = getWidth() / 2f, y = getHeight() / 2f;
      paint.setStyle(android.graphics.Paint.Style.FILL);
      if (!checkbox) {
        paint.setColor(MUTED);
        paint.setTypeface(TvUi.font);
        paint.setTextSize(d(14));
        paint.setTextAlign(android.graphics.Paint.Align.CENTER);
        android.graphics.Paint.FontMetrics metrics = paint.getFontMetrics();
        canvas.drawText(Integer.toString(index + 1), x, y - (metrics.ascent + metrics.descent) / 2, paint);
        return;
      }
      boolean checked = selected.contains(index);
      paint.setColor(checked ? ACCENT : isFocused() ? ACCENT : BORDER);
      paint.setStrokeWidth(d(1));
      paint.setStyle(checked ? android.graphics.Paint.Style.FILL : android.graphics.Paint.Style.STROKE);
      canvas.drawRoundRect(x - d(7), y - d(7), x + d(7), y + d(7), d(3), d(3), paint);
      if (checked) {
        paint.setStyle(android.graphics.Paint.Style.STROKE);
        paint.setColor(MANTLE);
        paint.setStrokeWidth(d(1.5f));
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(x - d(4), y); path.lineTo(x - d(1), y + d(3)); path.lineTo(x + d(4), y - d(3));
        canvas.drawPath(path, paint);
      }
    }
  }

  void invalidate() {
    lastRevision = -1;
    refresh();
  }

  private void more() {
    if (rebuilding || visible >= player.queue().length()) return;
    rebuilding = true;
    int from = tracks.size();
    visible = Math.min(player.queue().length(), visible + 80);
    appendRows(from, visible);
    rebuilding = false;
  }

  private boolean focusSelection(int index) {
    View marker = rows.findViewWithTag("select:" + index);
    if (marker == null) return false;
    boolean focused = marker.requestFocus();
    refreshFocusActions();
    return focused;
  }

  private boolean vertical(int at, int key) {
    if (key != KeyEvent.KEYCODE_DPAD_UP && key != KeyEvent.KEYCODE_DPAD_DOWN) return false;
    int next = at + (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
    if (key == KeyEvent.KEYCODE_DPAD_DOWN
        && at >= tracks.size() - 3
        && visible < player.queue().length()) {
      more();
    }
    if (next < 0) clearButton.requestFocus();
    else if (next < tracks.size()) focusSelection(next);
    refreshFocusActions();

    return true;
  }
}
