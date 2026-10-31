package com.netodia.tv;

import static com.netodia.tv.TvUi.*;

import android.app.*;
import android.content.SharedPreferences;
import android.view.*;
import android.widget.*;
import java.util.*;

/** A persistent first-level lyrics menu with left-opening child panels. */
final class LyricsMenu {
  private final Activity host;
  private final SharedPreferences prefs;
  private final Runnable fontChanged, lyricsChanged, closed;
  private final Dialog main;
  private Dialog child;
  private final ArrayList<View> rows = new ArrayList<>();
  private final ArrayList<TextView> values = new ArrayList<>();
  private int mainX, mainY;

  LyricsMenu(Activity host, Runnable fontChanged, Runnable lyricsChanged, Runnable closed) {
    this.host = host;
    this.fontChanged = fontChanged;
    this.lyricsChanged = lyricsChanged;
    this.closed = closed;
    prefs = host.getSharedPreferences("music", 0);
    main = dialogNew();
    LinearLayout panel = panel();
    String[] icons = {"format_size", "subtitles", "history", "translate", "language"};
    String[] names = {"歌词字号", "显示翻译", "时间偏移", "显示罗马字转写", "在线搜索歌词"};
    for (int i = 0; i < names.length; i++) {
      final int at = i;
      LinearLayout row = row(host);
      row.setPadding(d(8), 0, d(7), 0);
      focusable(row);
      row.addView(glyph(host, icons[i], 23, TEXT), lp(28, 30));
      TextView name = text(host, names[i], 17, TEXT);
      name.setPadding(d(8), 0, d(6), 0);
      row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
      TextView value = text(host, "", 14, MUTED);
      row.addView(value);
      values.add(value);
      row.addView(glyph(host, "chevron_left", 22, MUTED), lp(24, 28));
      panel.addView(row, lp(-1, 44));
      rows.add(row);
      row.setOnClickListener(v -> open(at));
      row.setOnKeyListener((v, key, event) -> {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) { open(at); return true; }
        if (key == KeyEvent.KEYCODE_DPAD_UP && at == 0) return true;
        return key == KeyEvent.KEYCODE_DPAD_DOWN && at == names.length - 1;
      });
    }
    routes(rows);
    main.setContentView(panel);
    main.setOnDismissListener(v -> {
      if (child != null) child.dismiss();
      closed.run();
    });
    refreshValues();
  }

  Dialog dialog() { return main; }
  private Dialog dialogNew() {
    Dialog value = new Dialog(host);
    value.requestWindowFeature(Window.FEATURE_NO_TITLE);
    return value;
  }
  private int d(float value) { return dp(host, value); }
  private LinearLayout.LayoutParams lp(int w, int h) {
    return new LinearLayout.LayoutParams(w < 0 ? w : d(w), h < 0 ? h : d(h));
  }
  private void focusable(View view) {
    view.setId(View.generateViewId());
    view.setFocusableInTouchMode(true);
    eagerTouchFocus(view);
    view.setBackground(focus(host, true));
  }
  private LinearLayout panel() {
    LinearLayout panel = column(host);
    panel.setPadding(d(4), d(6), d(4), d(6));
    android.graphics.drawable.GradientDrawable frame = fill(host, MANTLE, 10);
    frame.setStroke(d(1), BORDER);
    panel.setBackground(frame);
    return panel;
  }
  private void routes(List<View> controls) {
    for (int i = 0; i < controls.size(); i++) {
      controls.get(i).setNextFocusUpId(controls.get(Math.max(0, i - 1)).getId());
      controls.get(i).setNextFocusDownId(controls.get(Math.min(controls.size() - 1, i + 1)).getId());
    }
  }
  private void place(Dialog dialog, int width, int height, int x, int y) {
    Window window = dialog.getWindow();
    window.setBackgroundDrawableResource(android.R.color.transparent);
    window.setWindowAnimations(0);
    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    WindowManager.LayoutParams params = window.getAttributes();
    params.gravity = Gravity.TOP | Gravity.LEFT;
    params.width = width; params.height = height;
    params.x = Math.max(d(8), Math.min(x, host.getResources().getDisplayMetrics().widthPixels - width - d(8)));
    params.y = Math.max(d(8), Math.min(y, host.getResources().getDisplayMetrics().heightPixels - height - d(84)));
    window.setAttributes(params);
  }
  void show() {
    View anchor = host.getCurrentFocus();
    int[] point = new int[2];
    if (anchor != null) anchor.getLocationOnScreen(point);
    mainX = Math.max(d(8), point[0] + (anchor == null ? 0 : anchor.getWidth()) - d(286));
    mainY = point[1] + (anchor == null ? d(40) : anchor.getHeight()) + d(4);
    place(main, d(286), d(232), mainX, mainY);
    main.show();
    main.getWindow().getDecorView().setPadding(0, 0, 0, 0);
    place(main, d(286), d(232), mainX, mainY);
    rows.get(0).requestFocus();
  }
  private void refreshValues() {
    String[] labels = {prefs.getInt("lyric_size", 36) + " sp", prefs.getBoolean("lyric_translation", true) ? "开启" : "关闭", prefs.getInt("lyric_offset", 0) + " ms", prefs.getBoolean("lyric_romanization", true) ? "开启" : "关闭", prefs.getBoolean("lyric_online", true) ? "开启" : "关闭"};
    String[] names = {"歌词字号", "显示翻译", "时间偏移", "显示罗马字转写", "在线搜索歌词"};
    for (int i = 0; i < labels.length; i++) {
      setText(values.get(i), labels[i]);
      rows.get(i).setContentDescription(names[i] + "，" + labels[i]);
    }
  }
  private void open(int index) {
    if (child != null) child.dismiss();
    child = dialogNew();
    rows.get(index).setSelected(true);
    Dialog current = child;
    LinearLayout panel = panel();
    ArrayList<View> controls = new ArrayList<>();
    int height;
    int selected = 0;
    if (index != 2) {
      String[] options = index == 0 ? new String[] {"26", "30", "36", "42"} : new String[] {"开启", "关闭"};
      selected = index == 0 ? Math.max(0, Arrays.asList(26, 30, 36, 42).indexOf(prefs.getInt("lyric_size",36))) : prefs.getBoolean(index == 1 ? "lyric_translation" : index == 3 ? "lyric_romanization" : "lyric_online", true) ? 0 : 1;
      for (int i = 0; i < options.length; i++) {
        final int at = i;
        LinearLayout option = row(host);
        option.setPadding(d(10), 0, d(10), 0);
        focusable(option);
        option.addView(text(host, options[i], 18, i == selected ? ACCENT : TEXT));
        option.setContentDescription(options[i] + (i == selected ? "，已选中" : ""));
        panel.addView(option, lp(-1,44)); controls.add(option);
        option.setOnClickListener(v -> {
          if (index == 0) { prefs.edit().putInt("lyric_size", new int[] {26,30,36,42}[at]).apply(); fontChanged.run(); }
          else {
            prefs.edit().putBoolean(index == 1 ? "lyric_translation" : index == 3 ? "lyric_romanization" : "lyric_online", at == 0).apply();
            if (index == 4 && PlaybackService.instance != null) PlaybackService.instance.reloadLyrics();
            lyricsChanged.run();
          }
          current.dismiss();
        });
        option.setOnKeyListener((v,key,event) -> {
          if (event.getAction() == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_RIGHT) { current.dismiss(); return true; }
          return false;
        });
      }
      height = options.length * 44 + 12;
      routes(controls);
    } else {
      panel.setPadding(d(12),d(10),d(12),d(10));
      TextView amount = text(host, prefs.getInt("lyric_offset",0) + " ms",18,TEXT);
      amount.setGravity(Gravity.CENTER); panel.addView(amount,lp(-1,32));
      SeekBar range = new SeekBar(host);
      range.setMax(100); range.setKeyProgressIncrement(1);
      range.setProgress((prefs.getInt("lyric_offset",0)+5000)/100);
      range.setContentDescription("歌词时间偏移，左右键调整");
      seekStyle(host,range); panel.addView(range,lp(-1,48)); controls.add(range);
      range.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
        public void onStartTrackingTouch(SeekBar s) {}
        public void onStopTrackingTouch(SeekBar s) {}
        public void onProgressChanged(SeekBar s,int p,boolean user) { setText(amount,(p*100-5000)+" ms"); }
      });
      Button apply = button(host,"确定",()-> { prefs.edit().putInt("lyric_offset",range.getProgress()*100-5000).apply(); lyricsChanged.run(); current.dismiss(); });
      panel.addView(apply,lp(-1,40)); controls.add(apply);
      height=144;
    }
    current.setContentView(panel);
    current.setOnDismissListener(v -> {
      rows.get(index).setSelected(false);
      refreshValues();
      if (main.isShowing()) rows.get(index).requestFocus();
      if (child == current) child = null;
    });
    int width=d(190), x=mainX-width-d(6);
    place(current,width,d(height),x,mainY+index*d(44));
    current.show(); current.getWindow().getDecorView().setPadding(0,0,0,0);
    place(current,width,d(height),x,mainY+index*d(44));
    controls.get(Math.min(selected,controls.size()-1)).requestFocus();
  }
}
