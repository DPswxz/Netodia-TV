package com.netodia.tv;

import static com.netodia.tv.TvUi.*;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** TV presentation of Netodia's desktop navigation and music surfaces. */
public class MainActivity extends Activity {
  private static final int PAGE_SIZE = 40;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService files = Executors.newSingleThreadExecutor();
  private final FocusRouter focus = new FocusRouter();
  private final ArrayDeque<Page> history = new ArrayDeque<>();

  private static final class CachedPage {
    final JSONArray items;
    final JSONObject entity;
    final boolean more;
    final int offset;
    final long created = SystemClock.elapsedRealtime();

    CachedPage(Page page) {
      items = page.items;
      entity = page.entity;
      more = page.more;
      offset = page.offset;
    }
  }

  private final LinkedHashMap<String, CachedPage> pageCache =
      new LinkedHashMap<String, CachedPage>(8, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedPage> eldest) {
          return size() > 8;
        }
      };

  private String cacheKey(String op, JSONObject args) {
    return accountId + ":" + op + ":" + args.toString();
  }

  private final ArrayList<View> sidebarButtons = new ArrayList<>();
  private Backend backend;
  private LinearLayout root, sidebar, content, header, tabs, body;
  private FrameLayout stage;
  private LinearLayout curatedHero;
  private ScrollView scroller;
  private TextView heading,
      subtitle,
      status,
      nowTitle,
      nowArtist,
      clock,
      accountLabel,
      lyricPrevious,
      lyricCurrent,
      lyricNext,
      lyricTranslation;
  private ImageView nowCover, playerCover;
  private Button playButton, modeButton, favoriteButton, playerFavoriteButton;
  private SeekBar progress;
  private Page current;
  private int contentRow, renderVersion, qrVersion, lastLyric = -2, navTileSize = 84;
  private boolean restoringScroll;
  private final Map<String, Integer> itemPositions = new HashMap<>();
  private int displayedItems;
  private long accountId;
  private String nickname = "未登录", paintedSong = "";
  private boolean destroyed, started, accountRestoring;
  private Dialog modal;
  private View modalOrigin;
  private PlaybackService observed;
  private final Runnable playbackChanged = this::updatePlayback;
  private final String[] sideKeys = {
    "search", "browse", "favorites", "recent", "library", "settings"
  };
  private final String[] sideLabels = {"搜索", "浏览", "收藏", "最近", "音乐库", "设置"};
  private final String[] sideIcons = {
    "search", "folder", "favorite", "history", "library_music", "settings"
  };
  private TextView nowAlbum, durationLabel;
  private Button shuffleButton;

  private static final class Page {
    String type, title, section, kind = "playlists", query = "", path = "", restoreFocus = "";
    String failure = "";
    JSONObject entity = new JSONObject();
    JSONArray items = new JSONArray();
    int tab, offset, visible = PAGE_SIZE, scrollY, generation;
    String pageOp = "";
    JSONObject pageArgs = new JSONObject();
    boolean loaded, loading, more;

    Page(String type, String title, String section) {
      this.type = type;
      this.title = title;
      this.section = section;
    }
  }

  private int d(float value) {
    return dp(this, value);
  }

  private PlaybackService service() {
    return PlaybackService.instance;
  }

  private android.content.SharedPreferences prefs() {
    return getSharedPreferences("music", 0);
  }

  private LinearLayout.LayoutParams lp(int width, int height) {
    return new LinearLayout.LayoutParams(
        width < 0 ? width : d(width), height < 0 ? height : d(height));
  }

  private void margin(View view, int right, int bottom) {
    LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) view.getLayoutParams();
    p.rightMargin = d(right);
    p.bottomMargin = d(bottom);
    view.setLayoutParams(p);
  }

  private TextView label(String value, int size, int color) {
    return TvUi.text(this, value, size, color);
  }

  private Button action(String title, Runnable run, int row, int col, String key) {
    Button b = button(this, title, run);
    focus.add(b, FocusRouter.CONTENT, row, col, key);
    return b;
  }

  private void message(String value) {
    if (status == null) return;
    String text = value == null ? "操作失败，请重试" : value;
    TvUi.setText(status, text);
    if (text.startsWith("方向键") || text.startsWith("正在加载")) {
      status.setVisibility(View.GONE);
      return;
    }
    status.setVisibility(View.VISIBLE);
    handler.postDelayed(
        () -> {
          if (!destroyed && text.contentEquals(status.getText())) status.setVisibility(View.GONE);
        },
        4000);
  }

  private void request(String op, JSONObject args, Backend.Callback callback) {
    backend.request(
        op,
        args,
        (data, error) -> {
          if (!destroyed) callback.done(data, error);
        });
  }

  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    Diagnostics.initialize(this);
    TvUi.initialize(this);
    backend = Backend.get(this);
    accountRestoring = backend.hasSession();
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    startForegroundService(new Intent(this, PlaybackService.class));
    buildShell();
    navigate(new Page("library", "我的音乐库", "library"), true);
    focus.focus("nav:library");
    if (accountRestoring)
      request(
          "account",
          Backend.json(),
          (data, error) -> {
            accountRestoring = false;
            if (error == null) {
              JSONObject a = (JSONObject) data;
              accountId = a.optLong("userId");
              nickname = a.optString("nickname");
              TvUi.setText(accountLabel, nickname);
            }
            if (current != null
                && (current.type.equals("library")
                    || current.type.equals("favorites")
                    || current.type.equals("recent"))) {
              snapshot();
              render();
            }
          });
    handler.post(attachService);
  }

  private void buildShell() {
    root = column(this);
    root.setBackgroundColor(MANTLE);
    root.setPadding(d(8), d(8), d(5), 0);
    setContentView(root);
    LinearLayout titlebar = row(this);
    titlebar.setGravity(Gravity.CENTER);
    ImageView app = cover(this, 16);
    titlebar.addView(app);
    app.setImageResource(getResources().getIdentifier("logo", "drawable", getPackageName()));
    TextView appName = label("Netodia", 14, TEXT);
    appName.setPadding(d(8), 0, 0, 0);
    titlebar.addView(appName);
    LinearLayout top = row(this);
    top.setGravity(Gravity.TOP);
    root.addView(top, new LinearLayout.LayoutParams(-1, 0, 1));
    sidebar = column(this);
    sidebar.setPadding(0, d(8), d(8), d(8));
    int logicalHeight =
        (int)
            (getResources().getDisplayMetrics().heightPixels
                / (getResources().getDisplayMetrics().density * TvUi.SCALE));
    navTileSize = Math.max(48, Math.min(84, (logicalHeight - 178) / 6));
    top.addView(sidebar, lp(navTileSize + 8, -1));
    sidebar.addView(titlebar, lp(-1, 32));
    for (int i = 0; i < sideKeys.length; i++) {
      final int at = i;
      if (i == 2) divider(sidebar);
      if (i == sideKeys.length - 1) {
        sidebar.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        divider(sidebar);
      }
      LinearLayout item =
          desktopNav(
              sideIcons[i],
              sideLabels[i],
              () -> navigate(new Page(sideKeys[at], sideLabels[at], sideKeys[at]), true));
      sidebar.addView(item, lp(-1, navTileSize));
      margin(item, 0, 3);
      focus.add(item, FocusRouter.SIDE, i + 1, 0, "nav:" + sideKeys[i]);
      sidebarButtons.add(item);
    }
    // Account remains under Settings > Services, matching the original sidebar.
    accountLabel = label(nickname, 13, MUTED);
    accountLabel.setVisibility(View.GONE);
    content = new ContentPanel(this);
    content.setPadding(d(16), d(8), d(16), d(8));
    android.graphics.drawable.GradientDrawable border = fill(this, BASE, 8);
    content.setBackground(border);
    content.setClipToOutline(true);
    LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(0, -1, 1);
    contentParams.bottomMargin = d(4);
    top.addView(content, contentParams);
    header = row(this);
    content.addView(header, lp(-1, 52));
    Button back = button(this, "", this::back);
    TvUi.icon(back, "arrow_back");
    back.setBackground(TvUi.focus(this, true));
    back.setContentDescription("返回");
    header.addView(back, lp(38, 38));
    LinearLayout titles = column(this);
    titles.setPadding(d(10), 0, 0, 0);
    titles.setGravity(Gravity.CENTER_VERTICAL);
    header.addView(titles, new LinearLayout.LayoutParams(0, -1, 1));
    heading = label("我的音乐库", 23, TEXT);
    heading(heading);
    titles.addView(heading);
    subtitle = label("", 14, MUTED);
    subtitle.setPadding(0, d(4), 0, 0);
    titles.addView(subtitle);
    Button queue = button(this, "", this::showQueue);
    TvUi.icon(queue, "queue_music");
    queue.setBackground(TvUi.focus(this, true));
    queue.setContentDescription("播放队列");
    header.addView(queue, lp(40, 40));
    tabs = row(this);
    tabs.setPadding(0, 0, 0, d(8));
    content.addView(tabs, lp(-1, 64));
    curatedHero = row(this);
    curatedHero.setVisibility(View.GONE);
    content.addView(curatedHero, lp(-1, 160));
    stage = new FrameLayout(this);
    content.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
    status = label("", 13, MUTED);
    status.setPadding(d(4), d(4), 0, d(4));
    status.setVisibility(View.GONE);
    content.addView(status, lp(-1, 26));
    buildPlayerBar();
  }

  private LinearLayout desktopNav(String symbol, String name, Runnable action) {
    LinearLayout tile = column(this);
    tile.setGravity(Gravity.CENTER);
    tile.setBackground(TvUi.focus(this, true));
    tile.addView(
        TvUi.glyph(this, symbol, Math.min(26, navTileSize / 2), TEXT),
        lp(32, Math.min(32, (int) (navTileSize * .42f))));
    TextView title = label(name, Math.min(16, (navTileSize - 8) / 3), MUTED);
    title.setGravity(Gravity.CENTER);
    title.setSingleLine();
    tile.addView(title, lp(-1, Math.min(24, (int) (navTileSize * .3f))));
    tile.setContentDescription(name);
    tile.setOnClickListener(v -> action.run());
    return tile;
  }

  private void divider(LinearLayout parent) {
    View line = new View(this);
    line.setBackgroundColor(BORDER);
    parent.addView(line, lp(-1, 1));
    LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) line.getLayoutParams();
    p.topMargin = d(6);
    p.bottomMargin = d(6);
  }

  private Button transport(
      LinearLayout row, String symbol, String description, Runnable action, int col, boolean play) {
    Button b = button(this, "", action);
    TvUi.icon(b, symbol);
    b.setPadding(0, 0, 0, 0);
    b.setBackground(TvUi.focus(this, true));
    b.setContentDescription(description);
    row.addView(b, lp(play ? 42 : 34, play ? 42 : 38));
    margin(b, 5, 0);
    focus.add(b, FocusRouter.PLAYER, 0, col, "player:" + description);
    if (play) {
      android.graphics.drawable.StateListDrawable states =
          new android.graphics.drawable.StateListDrawable();
      android.graphics.drawable.GradientDrawable ring = fill(this, ACCENT, 50);
      ring.setStroke(d(2), TEXT);
      states.addState(new int[] {android.R.attr.state_focused}, ring);
      states.addState(new int[] {}, fill(this, ACCENT, 50));
      b.setBackground(states);
      b.setTextColor(MANTLE);
    }
    return b;
  }

  private void buildPlayerBar() {
    LinearLayout bar = row(this);
    bar.setPadding(d(8), d(10), d(8), d(10));
    bar.setBackgroundColor(MANTLE);
    root.addView(bar, lp(-1, 78));
    LinearLayout info = row(this);
    info.setPadding(d(6), d(3), d(8), d(3));
    info.setBackground(TvUi.focus(this, true));
    bar.addView(info, lp(260, -1));
    info.setOnClickListener(v -> push(new Page("player", "正在播放", current.section)));
    focus.add(info, FocusRouter.PLAYER, 0, 0, "player:info");
    info.setContentDescription("打开正在播放");
    nowCover = cover(this, 46);
    info.addView(nowCover);
    Artwork.load(nowCover, "");
    LinearLayout facts = column(this);
    facts.setPadding(d(10), 0, d(6), 0);
    info.addView(facts, new LinearLayout.LayoutParams(0, -2, 1));
    nowTitle = label("尚未播放", 15, TEXT);
    nowTitle.setSingleLine();
    nowTitle.setEllipsize(TextUtils.TruncateAt.END);
    facts.addView(nowTitle);
    nowArtist = label("", 12, TEXT);
    nowArtist.setSingleLine();
    nowArtist.setPadding(0, d(2), 0, 0);
    facts.addView(nowArtist);
    nowAlbum = label("", 12, MUTED);
    nowAlbum.setSingleLine();
    nowAlbum.setPadding(0, d(2), 0, 0);
    facts.addView(nowAlbum);
    transport(
        bar,
        "chevron_left",
        "上一首",
        () -> {
          if (service() != null) service().previous();
        },
        1,
        false);
    playButton =
        transport(
            bar,
            "play_arrow",
            "播放 / 暂停",
            () -> {
              if (service() != null) service().toggle();
            },
            2,
            true);
    transport(
        bar,
        "chevron_right",
        "下一首",
        () -> {
          if (service() != null) service().next(false);
        },
        3,
        false);
    clock = label("0:00", 11, TEXT);
    clock.setGravity(Gravity.CENTER);
    bar.addView(clock, lp(42, -2));
    progress = new SeekBar(this);
    progress.setMax(1000);
    TvUi.seekStyle(this, progress);
    progress.setPadding(d(6), 0, d(6), 0);
    progress.setBackground(TvUi.focus(this, true));
    progress.setContentDescription("播放进度，左右键调整 10 秒");
    bar.addView(progress, new LinearLayout.LayoutParams(0, d(38), 1));
    focus.add(progress, FocusRouter.PLAYER, 0, 4, "player:seek");
    progress.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onStartTrackingTouch(SeekBar s) {}

          public void onStopTrackingTouch(SeekBar s) {}

          public void onProgressChanged(SeekBar s, int v, boolean user) {
            if (user && service() != null) service().seek((int) (service().duration() * v / 1000L));
          }
        });
    durationLabel = label("0:00", 11, TEXT);
    durationLabel.setGravity(Gravity.CENTER);
    bar.addView(durationLabel, lp(44, -2));
    modeButton =
        transport(
            bar,
            "repeat",
            "循环模式",
            () -> {
              if (service() != null) service().setMode(service().mode() == 1 ? 0 : 1);
            },
            5,
            false);
    shuffleButton =
        transport(
            bar,
            "shuffle",
            "随机播放",
            () -> {
              if (service() != null) service().setMode(service().mode() == 2 ? 0 : 2);
            },
            6,
            false);
    favoriteButton =
        transport(
            bar,
            "favorite",
            "喜欢",
            () -> toggleFavorite(service() == null ? new JSONObject() : service().song()),
            7,
            false);
    transport(
        bar,
        "push_pin",
        "屏幕常亮",
        () -> {
          boolean keep = !prefs().getBoolean("keep_screen", true);
          prefs().edit().putBoolean("keep_screen", keep).apply();
          if (keep) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
          else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
          message(keep ? "屏幕常亮已开启" : "屏幕常亮已关闭");
        },
        8,
        false);
    transport(bar, "queue_music", "队列", this::showQueue, 9, false);
    transport(bar, "volume_up", "音量", this::volumeDialog, 10, false);
    transport(bar, "more_vert", "更多", this::showPlayerMenu, 11, false);
  }

  private PlayerMenu playerMenu;

  private void showPlayerMenu() {
    if (service() == null) return;
    if (playerMenu != null && playerMenu.isShowing()) {
      playerMenu.dismiss();
      return;
    }
    int[] position = new int[2];
    content.getLocationOnScreen(position);
    android.graphics.Rect bounds =
        new android.graphics.Rect(
            position[0],
            position[1],
            position[0] + content.getWidth(),
            position[1] + content.getHeight());
    playerMenu = new PlayerMenu(this, service(), bounds, this::equalizerDialog);
    playerMenu.show();
  }

  private void sleepDialog() {
    choice(
        "睡眠定时",
        new String[] {"关闭定时", "15 分钟", "30 分钟", "60 分钟", "90 分钟"},
        -1,
        at -> {
          if (service() != null) service().sleep(new int[] {0, 15, 30, 60, 90}[at]);
        });
  }

  private void snapshot() {
    if (current != null) {
      String key = focus.key(getCurrentFocus());
      if (!key.isEmpty()) current.restoreFocus = key;
      current.scrollY = scroller == null ? 0 : scroller.getScrollY();
    }
  }

  private void push(Page next) {
    if (next.restoreFocus.isEmpty()) {
      if (next.type.equals("detail")
          || next.type.equals("recommendation")
          || next.type.equals("songs")) next.restoreFocus = "header:back";
      else if (next.type.equals("settings")) next.restoreFocus = "tab:" + next.tab;
      else if (next.type.equals("browse")) next.restoreFocus = "browse:home";
      else if (next.type.equals("player")) next.restoreFocus = "player:back";
      else if (next.type.equals("login")) next.restoreFocus = "login:refresh";
      else if (next.type.equals("search")) next.restoreFocus = "search:input";
    }
    snapshot();
    if (current != null) history.push(current);
    navigate(next, false);
  }

  private void navigate(Page next, boolean rootPage) {
    if (rootPage) {
      history.clear();
      snapshot();
    }
    current = next;
    focus.selectSide("nav:" + next.section);
    for (int i = 0; i < sidebarButtons.size(); i++)
      sidebarButtons.get(i).setSelected(sideKeys[i].equals(next.section));
    for (int i = 0; i < sidebarButtons.size(); i++) {
      LinearLayout item = (LinearLayout) sidebarButtons.get(i);
      boolean selected = sideKeys[i].equals(next.section);
      ((TextView) item.getChildAt(0)).setTextColor(selected ? ACCENT : TEXT);
      ((TextView) item.getChildAt(0)).setTypeface(selected ? TvUi.filled : TvUi.symbols);
      ((TextView) item.getChildAt(1)).setTextColor(selected ? TEXT : MUTED);
    }
    render();
  }

  private void render() {
    LinearLayout.LayoutParams stageParams = (LinearLayout.LayoutParams) stage.getLayoutParams();
    stageParams.leftMargin = 0;
    stageParams.rightMargin = 0;
    stage.setLayoutParams(stageParams);
    playerFavoriteButton = null;
    lyricScroll = null;
    lyricLines = null;
    paintedLyrics = null;
    int version = ++renderVersion;
    qrVersion++;
    lastLyric = -2;
    playerCover = null;
    curatedHero.removeAllViews();
    curatedHero.setVisibility(View.GONE);
    android.graphics.drawable.GradientDrawable neutral = fill(this, BASE, 8);
    content.setBackground(neutral);
    ((ContentPanel) content).setArtworkPage(current.type.equals("player"));
    stage.setBackgroundColor(BASE);
    lyricCurrent = null;
    focus.clearContent();
    itemPositions.clear();
    displayedItems = 0;
    contentRow = 1;
    tabs.removeAllViews();
    tabs.setGravity(Gravity.CENTER_VERTICAL);
    stage.removeAllViews();
    scroller = null;
    body = null;
    header.setVisibility(
        current.type.equals("settings")
                || current.type.equals("browse")
                || current.type.equals("player")
                || current.type.equals("library")
                || current.type.equals("search")
                || current.type.equals("favorites")
                || current.type.equals("recent")
                || current.type.equals("favorites")
                || current.type.equals("recent")
            ? View.GONE
            : View.VISIBLE);
    TvUi.setText(heading, current.title);
    TvUi.setText(subtitle, "网易云音乐 · 为电视遥控器而设计");
    focus.add(header.getChildAt(0), FocusRouter.CONTENT, -3, 0, "header:back");
    focus.add(header.getChildAt(2), FocusRouter.CONTENT, -3, 1, "header:queue");
    message("方向键移动 · 确定选择 · 返回上一级 · 菜单键打开歌曲操作");
    switch (current.type) {
      case "home":
        home();
        break;
      case "search":
        searchPage();
        break;
      case "library":
        library();
        break;
      case "favorites":
        favorites();
        break;
      case "recent":
        recent();
        break;
      case "browse":
        browse();
        break;
      case "detail":
        detail();
        break;
      case "player":
        player();
        break;
      case "queue":
        queue();
        break;
      case "settings":
        settings();
        break;
      case "login":
        login();
        break;
      case "recommendation":
        if (ready(current.kind, Backend.json())) songList();
        break;
      case "songs":
        songsPage();
        break;
    }
    focus.selectTab(current.tab);
    View attached = getCurrentFocus();
    if (!current.restoreFocus.isEmpty() && focus.focus(current.restoreFocus)) {
    } else if (attached == null || !attached.isAttachedToWindow()) {
      if (!focus.focus("tab:" + current.tab)) focus.focus("nav:" + current.section);
    }
    restoringScroll = true;
    Page rendered = current;
    handler.post(
        () -> {
          if (destroyed || version != renderVersion || current != rendered) return;
          if (scroller != null) scroller.scrollTo(0, rendered.scrollY);
          restoringScroll = false;
        });
    updatePlayback();
  }

  private void scroll() {
    scroller = new ScrollView(this);
    scroller.setFillViewport(true);
    scroller.setClipToPadding(false);
    scroller.setPadding(d(4), d(4), d(6), d(8));
    scroller.setFocusable(false);
    Page source = current;
    int layoutVersion = renderVersion;
    scroller.setOnScrollChangeListener(
        (v, x, y, oldX, oldY) -> {
          if (!restoringScroll
              && current == source
              && scroller == v
              && y > oldY
              && body != null
              && body.getHeight() - y - v.getHeight() < d(180))
            handler.post(
                () -> {
                  if (current == source && renderVersion == layoutVersion) appendMore();
                });
        });
    body = column(this);
    body.setPadding(d(2), d(2), d(4), d(12));
    scroller.addView(body);
    stage.addView(scroller, new FrameLayout.LayoutParams(-1, -1));
  }

  private void tabRow(String[] labels) {
    String[] icons =
        current.type.equals("settings")
            ? new String[] {"library_music", "play_circle", "palette", "link", "info"}
            : current.type.equals("library")
                ? new String[] {"favorite", "playlist_play", "auto_awesome", "shuffle"}
                : current.type.equals("favorites")
                    ? new String[] {"music_note", "album"}
                    : current.type.equals("recent")
                        ? new String[] {"music_note", "bar_chart"}
                        : new String[0];
    for (int i = 0; i < labels.length; i++) {
      final int tab = i;
      LinearLayout item = column(this);
      item.setGravity(Gravity.CENTER);
      item.setPadding(d(10), 0, d(10), 0);
      item.setBackground(TvUi.focus(this, true));
      LinearLayout name = row(this);
      name.setGravity(Gravity.CENTER);
      item.addView(name, new LinearLayout.LayoutParams(-1, 0, 1));
      boolean onHero = current.type.equals("favorites") || current.type.equals("recent");
      int ink = onHero ? TEXT : i == current.tab ? ACCENT : TEXT;
      if (i < icons.length) {
        TextView icon = TvUi.glyph(this, icons[i], 23, ink);
        icon.setTypeface(i == current.tab ? TvUi.filled : TvUi.symbols);
        name.addView(icon, lp(28, 30));
      }
      TextView title = label(labels[i], 17, ink);
      heading(title);
      title.setPadding(d(8), 0, d(4), 0);
      name.addView(title);
      View underline = new View(this);
      underline.setBackgroundColor(i == current.tab ? (onHero ? TEXT : ACCENT) : Color.TRANSPARENT);
      item.addView(underline, lp(-1, 3));
      int tabWidth =
          (int)
                  Math.ceil(
                      title.getPaint().measureText(labels[i])
                          / (getResources().getDisplayMetrics().density * TvUi.SCALE))
              + (i < icons.length ? 28 : 0)
              + 36;
      tabs.addView(item, lp(tabWidth, -1));
      margin(item, 5, 0);
      focus.add(item, FocusRouter.CONTENT, 0, i, "tab:" + i);
      item.setContentDescription(labels[i]);
      item.setOnClickListener(
          v -> {
            if (current.tab == tab) {
              focus.focus("tab:" + tab);
              return;
            }
            snapshot();
            current.tab = tab;
            current.pageOp = "";
            current.pageArgs = new JSONObject();
            current.more = false;
            current.query = "";
            current.entity = new JSONObject();
            current.generation++;
            current.items = new JSONArray();
            current.loaded = false;
            current.loading = false;
            current.failure = "";
            current.visible = PAGE_SIZE;
            current.offset = 0;
            current.scrollY = 0;
            current.restoreFocus = "tab:" + tab;
            render();
          });
    }
    if (current.type.equals("settings")
        || current.type.equals("library")
        || current.type.equals("favorites")
        || current.type.equals("recent")) {
      tabs.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
      String filterLabel =
          !current.query.isEmpty()
              ? current.query
              : current.type.equals("settings")
                  ? "搜索设置…"
                  : current.type.equals("favorites")
                      ? "筛选收藏…"
                      : current.type.equals("recent") ? "筛选最近播放…" : "筛选播放列表…";
      LinearLayout search = row(this);
      search.setPadding(d(10), 0, d(12), 0);
      search.addView(TvUi.glyph(this, "search", 19, MUTED), lp(23, 24));
      TextView filterText = label(filterLabel, 15, MUTED);
      filterText.setSingleLine();
      filterText.setEllipsize(TextUtils.TruncateAt.END);
      filterText.setPadding(d(5), 0, 0, 0);
      search.addView(filterText, new LinearLayout.LayoutParams(0, -2, 1));
      android.graphics.drawable.StateListDrawable states =
          new android.graphics.drawable.StateListDrawable();
      android.graphics.drawable.GradientDrawable active = fill(this, SURFACE1, 30);
      active.setStroke(d(1.5f), ACCENT);
      android.graphics.drawable.GradientDrawable idle = fill(this, BASE, 30);
      idle.setStroke(d(1), BORDER);
      states.addState(new int[] {android.R.attr.state_focused}, active);
      states.addState(new int[] {}, idle);
      search.setBackground(states);
      search.setContentDescription(filterLabel);
      focus.add(search, FocusRouter.CONTENT, 0, labels.length, "page:filter");
      search.setOnClickListener(
          v ->
              inputDialog(
                  current.type.equals("settings") ? "搜索设置" : "筛选播放列表",
                  "输入关键词",
                  current.query,
                  query -> {
                    snapshot();
                    current.query = query;
                    render();
                  }));
      tabs.addView(search, lp(222, 34));
    }
  }

  private void empty(String title, String description, String button, Runnable action) {
    if (title.startsWith("正在") && button == null) {
      loadingView();
      return;
    }
    LinearLayout box = column(this);
    box.setGravity(Gravity.CENTER);
    box.setPadding(d(24), d(24), d(24), d(24));
    TextView mark = TvUi.glyph(this, "music_note", 52, ACCENT);
    box.addView(mark);
    TextView name = label(title, 24, TEXT);
    name.setGravity(Gravity.CENTER);
    name.setPadding(0, d(18), 0, d(12));
    box.addView(name);
    TextView help = label(description, 17, MUTED);
    help.setGravity(Gravity.CENTER);
    box.addView(help);
    if (button != null) {
      Button b = action(button, action, 2, 0, "empty:action");
      box.addView(b, lp(-2, 50));
      ((LinearLayout.LayoutParams) b.getLayoutParams()).topMargin = d(24);
    }
    stage.addView(box, new FrameLayout.LayoutParams(-1, -1));
  }

  private boolean signedIn() {
    if (accountRestoring) {
      loadingView();
      return false;
    }
    if (accountId != 0) return true;
    empty(
        "登录你的网易云账号",
        "扫码后即可访问歌单、收藏和每日推荐",
        "扫码登录",
        () -> push(new Page("login", "网易云账号", current.section)));
    return false;
  }

  private void load(String op, JSONObject input, boolean append) {
    Page target = current;
    int generation = target.generation;
    target.pageOp = op;
    target.pageArgs = input;
    if (target.loading) return;
    target.loading = true;
    target.failure = "";
    message("正在加载…");
    request(
        op,
        input,
        (data, error) -> {
          if (target.generation != generation) return;
          target.loading = false;
          if (error != null) {
            if (target.loaded && target.items.length() > 0) {
              message("暂时无法刷新，保留已加载内容");
              return;
            }
            target.failure = error;
            if (current == target) {
              snapshot();
              render();
            }
            return;
          }
          try {
            JSONArray result;
            if (data instanceof JSONArray) result = (JSONArray) data;
            else {
              JSONObject object = (JSONObject) data;
              if (target.type.equals("detail")) {
                target.entity = object.optJSONObject(op.equals("playlist") ? "playlist" : "entity");
                if (target.entity == null) target.entity = new JSONObject();
                result = object.getJSONArray("songs");
              } else if (op.equals("search") || op.equals("search_all")) {
                target.entity = object;
                result = object.optJSONArray(target.kind);
                if (result == null) result = new JSONArray();
                String countKey =
                    target.kind.equals("songs")
                        ? "songCount"
                        : target.kind.equals("albums")
                            ? "albumCount"
                            : target.kind.equals("artists") ? "artistCount" : "playlistCount";
                target.more =
                    input.optInt("offset", 0) + result.length() < object.optLong(countKey);
              } else {
                result = object.optJSONArray("items");
                if (result == null) result = new JSONArray();
                target.more = object.optBoolean("more");
              }
            }
            target.offset = input.optInt("offset", target.offset);
            if (append) {
              HashSet<String> ids = new HashSet<>();
              for (int i = 0; i < target.items.length(); i++)
                ids.add(MusicData.identity(target.items.optJSONObject(i)));
              int added = 0;
              for (int i = 0; i < result.length(); i++) {
                JSONObject item = result.optJSONObject(i);
                if (item != null && ids.add(MusicData.identity(item))) {
                  target.items.put(item);
                  added++;
                }
              }
              if (result.length() > 0 && added == 0) target.more = false;
            } else target.items = result;
            target.loaded = true;
            JSONObject baseArgs = new JSONObject(input.toString());
            if (baseArgs.has("offset")) baseArgs.put("offset", 0);
            pageCache.put(cacheKey(op, baseArgs), new CachedPage(target));
            if (current == target) {
              snapshot();
              render();
            }
          } catch (Exception e) {
            target.failure = "数据格式异常，请重试";
            if (current == target) {
              snapshot();
              render();
            }
          }
        });
  }

  private void loadingView() {
    boolean list = current.type.equals("favorites") || current.type.equals("recent");
    if (list) {
      LinearLayout skeleton = column(this);
      skeleton.setPadding(d(12), d(12), d(12), d(12));
      LinearLayout layout = column(this);
      layout.addView(songTableHeader(), lp(-1, 37));
      layout.addView(skeleton, new LinearLayout.LayoutParams(-1, 0, 1));
      stage.addView(layout, new FrameLayout.LayoutParams(-1, -1));
      for (int i = 0; i < 5; i++) {
        LinearLayout row = row(this);
        row.setAlpha(.5f);
        skeleton.addView(row, lp(-1, 56));
        View art = new View(this);
        art.setBackground(fill(this, SURFACE1, 6));
        row.addView(new View(this), lp(64, 1));
        row.addView(art, lp(36, 36));
        margin(art, 8, 0);
        LinearLayout words = column(this);
        words.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(words, new LinearLayout.LayoutParams(0, -1, 1));
        View title = new View(this);
        title.setBackground(fill(this, SURFACE1, 4));
        words.addView(title, lp(180 - i % 3 * 24, 12));
        margin(title, 0, 8);
        View caption = new View(this);
        caption.setBackground(fill(this, SURFACE, 4));
        words.addView(caption, lp(100, 8));
      }
    }
    LinearLayout message = row(this);
    message.setGravity(Gravity.CENTER);
    message.setPadding(d(12), d(8), d(12), d(8));
    ProgressBar spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
    spinner.setIndeterminateTintList(ColorStateList.valueOf(MUTED));
    message.addView(spinner, lp(20, 20));
    TextView caption = label("加载中…", 14, MUTED);
    caption.setPadding(d(10), 0, 0, 0);
    message.addView(caption);
    FrameLayout.LayoutParams params =
        new FrameLayout.LayoutParams(
            -2, d(38), list ? Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL : Gravity.CENTER);
    params.bottomMargin = list ? d(12) : 0;
    stage.addView(message, params);
  }

  private boolean ready(String op, JSONObject input) {
    current.pageOp = op;
    current.pageArgs = input;
    if (!current.failure.isEmpty()) {
      empty(
          "暂时无法加载",
          current.failure,
          "重试",
          () -> {
            current.loaded = false;
            current.failure = "";
            render();
          });
      return false;
    }
    if (!current.loaded) {
      CachedPage cached = pageCache.get(cacheKey(op, input));
      if (cached != null) {
        current.items = cached.items;
        current.entity = cached.entity;
        current.more = cached.more;
        current.offset = cached.offset;
        current.loaded = true;
        if (cached.offset == 0 && SystemClock.elapsedRealtime() - cached.created > 30000)
          load(op, input, false);
        return true;
      }
      loadingView();
      load(op, input, false);
      return false;
    }
    return true;
  }

  private JSONObject entityArgs() {
    return Backend.json("id", current.entity.optLong("id"));
  }

  private void home() {
    tabRow(new String[] {"发现歌单", "排行榜", "每日推荐", "私人漫游"});
    String op = new String[] {"discover", "charts", "daily", "fm"}[current.tab];
    current.kind = current.tab < 2 ? "playlists" : "songs";
    if (current.tab >= 2 && !signedIn()) return;
    if (!ready(op, Backend.json())) return;
    TvUi.setText(
        subtitle, current.tab == 0 ? "从熟悉的旋律，到下一首心动" : current.tab == 1 ? "此刻流行，尽在耳边" : "为你而来的音乐");
    if (current.tab < 2) grid("playlist");
    else songList();
  }

  private void library() {
    tabRow(new String[] {"收藏的歌单", "我创建的歌单", "推荐", "无限漫游"});
    if (current.tab == 5) {
      localLibrary();
      return;
    }
    if (current.tab == 2) {
      if (ready(accountId == 0 ? "discover" : "recommendations", Backend.json())) grid("playlist");
      return;
    }
    if (!signedIn()) return;
    if (current.tab == 3) {
      if (ready("fm", Backend.json())) songList();
      return;
    }
    if (ready(
        "library",
        Backend.json("id", accountId, "created", current.tab == 1, "offset", current.offset)))
      grid("playlist");
  }

  private void favorites() {
    tabRow(new String[] {"歌曲", "专辑"});
    curated("收藏", "favorite", current.items);
    if (accountRestoring) {
      loadingView();
      return;
    }
    if (current.tab == 0 && accountId == 0) {
      current.items = MusicData.saved(this, "favorites");
      current.loaded = true;
      curated("收藏", "favorite", current.items);
      songList();
      return;
    }
    if (!signedIn()) return;
    if (!ready(
        current.tab == 0 ? "liked" : "albums",
        Backend.json("id", accountId, "offset", current.offset))) return;
    curated("收藏", "favorite", current.items);
    if (current.tab == 0) songList();
    else grid("album");
  }

  private void recent() {
    tabRow(new String[] {"歌曲", "播放最多"});
    if (accountRestoring) {
      curated("最近播放", "history", current.items);
      loadingView();
      return;
    }
    if (current.tab == 0 && accountId != 0) {
      curated("最近播放", "history", current.items);
      if (!ready("recent", Backend.json())) return;
    } else {
      current.items =
          current.tab == 0 ? MusicData.saved(this, "history") : MusicData.mostPlayed(this);
      current.loaded = true;
    }
    curated("最近播放", "history", current.items);
    songList();
  }

  private void curated(String title, String empty, JSONArray items) {
    curatedHero.removeAllViews();
    curatedHero.setVisibility(View.VISIBLE);
    curatedHero.setPadding(0, d(4), 0, d(16));
    LinearLayout.LayoutParams stageParams = (LinearLayout.LayoutParams) stage.getLayoutParams();
    stageParams.leftMargin = -d(15);
    stageParams.rightMargin = -d(15);
    stage.setLayoutParams(stageParams);
    FrameLayout mosaic = new FrameLayout(this);
    mosaic.setBackground(fill(this, SURFACE, 10));
    mosaic.setClipToOutline(true);
    curatedHero.addView(mosaic, lp(140, 140));
    Page source = current;
    ArtworkBackdrop backdrop = new ArtworkBackdrop(BASE, d(232), d(8));
    content.setBackground(backdrop);
    ArrayList<String> covers = new ArrayList<>();
    for (int i = 0; i < items.length() && covers.size() < 4; i++) {
      String url = MusicData.cover(items.optJSONObject(i));
      if (!url.isEmpty() && !covers.contains(url)) covers.add(url);
    }
    if (covers.isEmpty()) {
      TextView glyph = TvUi.glyph(this, items.length() == 0 ? empty : "music_note", 56, MUTED);
      glyph.setVisibility(!current.loaded && items.length() == 0 ? View.INVISIBLE : View.VISIBLE);
      if (items.length() == 0) glyph.setTypeface(TvUi.filled);
      mosaic.addView(glyph, new FrameLayout.LayoutParams(-1, -1));
    } else
      for (int i = 0; i < covers.size(); i++) {
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int count = covers.size();
        int w = count == 1 ? 140 : 70, h = count <= 2 ? 140 : count == 3 && i == 0 ? 140 : 70;
        int x = count == 1 ? 0 : count == 3 ? (i == 0 ? 0 : 70) : i % 2 * 70;
        int y = count <= 2 ? 0 : count == 3 ? (i == 2 ? 70 : 0) : i / 2 * 70;
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(d(w), d(h));
        params.leftMargin = d(x);
        params.topMargin = d(y);
        mosaic.addView(image, params);
        final int slot = i;
        Artwork.load(
            image,
            covers.get(i),
            bitmap -> {
              if (current == source && mosaic.isAttachedToWindow()) backdrop.artwork(slot, bitmap);
            });
      }
    LinearLayout facts = column(this);
    facts.setPadding(d(18), 0, 0, 0);
    curatedHero.addView(facts, new LinearLayout.LayoutParams(0, -1, 1));
    TextView name = label(title, 23, TEXT);
    heading(name);
    facts.addView(name);
    long total = 0;
    Set<String> artists = new HashSet<>(), albums = new HashSet<>();
    for (int i = 0; i < items.length(); i++) {
      JSONObject song = items.optJSONObject(i);
      total += song.optLong("dt");
      JSONArray credited = song.optJSONArray("ar");
      if (credited != null)
        for (int n = 0; n < credited.length(); n++)
          artists.add(credited.optJSONObject(n).optString("name"));
      else if (!MusicData.artist(song).isEmpty()) artists.add(MusicData.artist(song));
      albums.add(MusicData.album(song).optString("name"));
    }
    LinearLayout chips = row(this);
    facts.addView(chips, lp(-1, 40));
    String[] values = {
      items.length()
          + (current.type.equals("favorites") ? current.tab == 1 ? " 张专辑" : " 项收藏" : " 首曲目"),
      String.format(Locale.ROOT, "%d:%02d", total / 3600000, total % 3600000 / 60000),
      artists.size() + " 位艺人",
      albums.size() + " 张专辑"
    };
    if (items.length() == 0 && !current.loaded) return;
    if (items.length() == 0) {
      TextView none = label("尚无" + (current.type.equals("favorites") ? "收藏" : "播放记录"), 15, MUTED);
      none.setPadding(0, d(8), 0, 0);
      facts.removeView(chips);
      facts.addView(none);
      return;
    }
    for (String value : values) {
      TextView chip = label(value, 13, MUTED);
      chip.setPadding(d(12), d(5), d(12), d(5));
      chip.setBackground(fill(this, SURFACE1, 22));
      chips.addView(chip, lp(-2, -2));
      margin(chip, 8, 0);
    }
  }

  private int mixColor(int a, int b, float fraction) {
    return Color.rgb(
        Math.round(Color.red(a) * (1 - fraction) + Color.red(b) * fraction),
        Math.round(Color.green(a) * (1 - fraction) + Color.green(b) * fraction),
        Math.round(Color.blue(a) * (1 - fraction) + Color.blue(b) * fraction));
  }

  private void songsPage() {
    songList();
  }

  private void detail() {
    if (!ready(current.kind, entityArgs())) return;
    scroll();
    LinearLayout hero = row(this);
    hero.setPadding(d(10), d(8), d(10), d(18));
    ImageView artwork = cover(this, 120);
    hero.addView(artwork);
    Artwork.load(artwork, MusicData.cover(current.entity));
    LinearLayout facts = column(this);
    facts.setPadding(d(22), 0, 0, 0);
    hero.addView(facts, new LinearLayout.LayoutParams(0, -2, 1));
    TextView name = label(current.entity.optString("name", current.title), 25, TEXT);
    heading(name);
    name.setMaxLines(2);
    facts.addView(name);
    String description = current.entity.optString("description", "");
    TextView desc =
        label(description.isEmpty() ? current.items.length() + " 首歌曲" : description, 15, MUTED);
    desc.setMaxLines(2);
    desc.setEllipsize(TextUtils.TruncateAt.END);
    desc.setPadding(0, d(8), 0, d(12));
    facts.addView(desc);
    LinearLayout buttons = row(this);
    facts.addView(buttons);
    Button play = action("▶ 播放全部", () -> play(current.items, 0), contentRow, 0, "detail:play");
    buttons.addView(play, lp(-2, 44));
    margin(play, 8, 0);
    Button shuffle =
        action(
            "随机播放",
            () -> {
              prefs().edit().putInt("mode", 2).apply();
              play(current.items, 0);
            },
            contentRow,
            1,
            "detail:shuffle");
    buttons.addView(shuffle, lp(-2, 44));
    margin(shuffle, 8, 0);
    Button save =
        action(
            "收藏", () -> subscribe(current.kind, current.entity), contentRow, 2, "detail:subscribe");
    buttons.addView(save, lp(86, 44));
    contentRow++;
    body.addView(hero);
    addSongRows(current.items, false);
  }

  private int gridColumns = 1;

  private void grid(String kind) {
    JSONArray items = filtered(current.items, current.query);
    if (items.length() == 0) {
      if (current.more) {
        loadingView();
        handler.post(this::appendMore);
        return;
      }
      empty(
          "没有匹配的内容",
          "可以清除筛选或稍后刷新。",
          current.query.isEmpty() ? null : "清除筛选",
          () -> {
            current.query = "";
            render();
          });
      return;
    }
    scroll();
    float density = getResources().getDisplayMetrics().density * TvUi.SCALE;
    LinearLayout.LayoutParams stageParams = (LinearLayout.LayoutParams) stage.getLayoutParams();
    float available =
        content.getWidth() > 0
            ? (content.getWidth()
                    - content.getPaddingLeft()
                    - content.getPaddingRight()
                    - stageParams.leftMargin
                    - stageParams.rightMargin)
                / density
            : getResources().getDisplayMetrics().widthPixels / density - navTileSize - 48;
    gridColumns = Math.max(1, (int) ((available + 16) / 196));
    int cardWidth =
        Math.min(
            224, Math.max(120, (int) ((available - 16 * (gridColumns - 1) - 20) / gridColumns)));
    boolean recommended = current.type.equals("library") && current.tab == 2;
    TextView count =
        label(
            recommended
                ? "推荐歌单"
                : items.length()
                    + " 个"
                    + (kind.equals("album") ? "专辑" : kind.equals("artist") ? "歌手" : "播放列表"),
            22,
            TEXT);
    heading(count);
    count.setPadding(d(8), d(6), d(8), d(10));
    body.addView(count, lp(-1, 44));
    if (recommended) {
      recommendationShortcuts(items);
      section("推荐歌单");
    }
    int firstRow = contentRow;
    int total = Math.min(current.visible, items.length());
    LinearLayout row = null;
    for (int i = 0; i < total; i++) {
      int col = i % gridColumns;
      if (col == 0) {
        row = row(this);
        row.setGravity(Gravity.TOP);
        body.addView(row, lp(-1, cardWidth + 54));
      }
      JSONObject item = items.optJSONObject(i);
      if (item == null) continue;
      LinearLayout card = column(this);
      card.setPadding(d(8), d(8), d(8), d(6));
      card.setBackground(TvUi.focus(this, true));
      LinearLayout.LayoutParams params = lp(cardWidth, cardWidth + 46);
      params.rightMargin = d(16);
      row.addView(card, params);
      ImageView cover = cover(this, cardWidth - 16);
      cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
      card.addView(cover);
      Artwork.load(cover, MusicData.cover(item));
      TextView title = label(item.optString("name"), 17, TEXT);
      heading(title);
      title.setSingleLine();
      title.setEllipsize(TextUtils.TruncateAt.END);
      title.setMarqueeRepeatLimit(-1);
      title.setPadding(0, d(4), 0, 0);
      card.addView(title, lp(-1, 28));
      String metadata =
          kind.equals("playlist")
              ? (item.optLong("trackCount") > 0 ? item.optLong("trackCount") + " 首曲目" : "歌单")
                  + " · "
                  + MusicData.time((int) item.optLong("duration"))
              : kind.equals("album") ? "专辑" : "歌手";
      card.addView(label(metadata, 13, MUTED), lp(-1, 20));
      String key = "entity:" + item.optLong("id");
      itemPositions.put(key, i);
      focus.add(card, FocusRouter.CONTENT, firstRow + i / gridColumns, col, key);
      card.setContentDescription(item.optString("name") + "，" + metadata);
      View.OnFocusChangeListener prior = card.getOnFocusChangeListener();
      card.setOnFocusChangeListener(
          (v, has) -> {
            if (prior != null) prior.onFocusChange(v, has);
            title.setEllipsize(has ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
            title.setSelected(has);
          });
      card.setOnClickListener(v -> openEntity(kind, item));
      card.setOnLongClickListener(
          v -> {
            subscribe(kind, item);
            return true;
          });
    }
    displayedItems = total;
    contentRow = firstRow + (total + gridColumns - 1) / gridColumns;
  }

  private void recommendationShortcuts(JSONArray items) {
    LinearLayout row = row(this);
    body.addView(row, lp(-1, 126));
    if (accountId != 0)
      recommendationShortcut(
          row,
          "playlist_play",
          "每日推荐",
          () -> {
            Page page = new Page("recommendation", "每日推荐", "library");
            page.kind = "daily";
            push(page);
          },
          0);
    JSONObject radar = null;
    for (int i = 0; i < items.length(); i++)
      if (items.optJSONObject(i).optString("name").contains("私人雷达")) {
        radar = items.optJSONObject(i);
        break;
      }
    if (radar != null) {
      final JSONObject entity = radar;
      recommendationShortcut(
          row,
          "auto_awesome",
          "私人雷达",
          () -> openEntity("playlist", entity),
          accountId != 0 ? 1 : 0);
    }
    contentRow++;
  }

  private void recommendationShortcut(
      LinearLayout parent, String symbol, String title, Runnable click, int column) {
    LinearLayout card = row(this);
    card.setPadding(d(20), d(20), d(20), d(20));
    card.setBackground(TvUi.focus(this, false));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, d(112), 1);
    p.rightMargin = d(12);
    parent.addView(card, p);
    card.addView(TvUi.glyph(this, symbol, 32, ACCENT), lp(40, 44));
    LinearLayout words = column(this);
    words.setPadding(d(14), 0, 0, 0);
    TextView heading = label(title, 20, TEXT);
    heading(heading);
    words.addView(heading);
    words.addView(label("歌单", 14, MUTED));
    card.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
    card.addView(TvUi.glyph(this, "play_arrow", 24, ACCENT), lp(30, 36));
    focus.add(card, FocusRouter.CONTENT, contentRow, column, "recommendation:" + title);
    card.setContentDescription(title);
    card.setOnClickListener(v -> click.run());
  }

  private boolean appendMore() {
    if (current == null
        || !current.loaded
        || current.type.equals("search")
        || current.type.equals("settings")
        || current.type.equals("player")) return false;
    if (current.loading) return false;
    int count = filtered(current.items, current.query).length();
    if (current.visible < count) {
      snapshot();
      current.visible += PAGE_SIZE;
      render();
      return true;
    }
    if (!current.more || current.pageOp.isEmpty()) return false;
    try {
      JSONObject args = new JSONObject(current.pageArgs.toString());
      args.put("offset", current.offset + 30);
      snapshot();
      load(current.pageOp, args, true);
      return true;
    } catch (JSONException error) {
      message("无法加载更多内容");
      return false;
    }
  }

  private void openEntity(String kind, JSONObject entity) {
    Page p = new Page("detail", entity.optString("name"), current.section);
    p.kind = kind;
    p.entity = entity;
    push(p);
  }

  private void songList() {
    JSONArray visible = filtered(current.items, current.query);
    if (current.items.length() == 0) {
      empty("暂无歌曲", "收藏或播放音乐后，可以在这里找到它们", null, null);
      return;
    }
    scroll();
    if (current.type.equals("favorites") || current.type.equals("recent")) {
      stage.removeView(scroller);
      LinearLayout table = column(this);
      table.addView(songTableHeader(), lp(-1, 37));
      table.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
      stage.addView(table, new FrameLayout.LayoutParams(-1, -1));
      addSongRows(visible, false);
      return;
    }
    LinearLayout actions = row(this);
    body.addView(actions, lp(-1, 56));
    Button all = action("▶ 播放全部", () -> play(current.items, 0), contentRow, 0, "songs:play");
    actions.addView(all, lp(-2, 46));
    margin(all, 8, 0);
    Button random =
        action(
            "随机播放",
            () -> {
              prefs().edit().putInt("mode", 2).apply();
              play(current.items, 0);
            },
            contentRow,
            1,
            "songs:shuffle");
    actions.addView(random, lp(-2, 46));
    margin(random, 8, 0);
    Button filter = action("筛选歌曲", this::filterSongs, contentRow, 2, "songs:filter");
    actions.addView(filter, lp(-2, 46));
    contentRow++;
    addSongRows(visible, false);
  }

  private JSONArray filtered(JSONArray items, String query) {
    if (query.isEmpty()) return items;
    JSONArray result = new JSONArray();
    String needle = query.toLowerCase(Locale.ROOT);
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.optJSONObject(i);
      if ((item.optString("name")
              + " "
              + MusicData.artist(item)
              + " "
              + MusicData.album(item).optString("name"))
          .toLowerCase(Locale.ROOT)
          .contains(needle)) result.put(item);
    }
    return result;
  }

  private LinearLayout songTableHeader() {
    LinearLayout container = column(this);
    LinearLayout head = row(this);
    head.setPadding(d(12), 0, d(12), 0);
    container.addView(head, lp(-1, 36));
    TextView number = label("#  ▴", 14, ACCENT);
    head.addView(number, lp(64, -2));
    String[] names =
        prefs().getBoolean("show_album_column", true)
            ? new String[] {"标题", "艺人", "专辑"}
            : new String[] {"标题", "艺人"};
    float[] weights = {1.6f, 1, 1.1f};
    for (int i = 0; i < names.length; i++) {
      LinearLayout cell = row(this);
      View divider = new View(this);
      divider.setBackgroundColor(BORDER);
      cell.addView(divider, lp(1, 24));
      TextView name = label(names[i], 15, TEXT);
      heading(name);
      name.setPadding(d(12), 0, 0, 0);
      cell.addView(name);
      head.addView(cell, new LinearLayout.LayoutParams(0, -1, weights[i]));
    }
    TextView duration = label("长度", 15, TEXT);
    heading(duration);
    duration.setPadding(d(12), 0, 0, 0);
    head.addView(duration, lp(84, -2));
    View line = new View(this);
    line.setBackgroundColor(BORDER);
    container.addView(line, lp(-1, 1));
    return container;
  }

  private void addSongRows(JSONArray songs, boolean queuePage) {
    boolean showAlbum = prefs().getBoolean("show_album_column", true);
    if (!current.type.equals("favorites") && !current.type.equals("recent"))
      body.addView(songTableHeader(), lp(-1, 37));
    int total = Math.min(current.visible, songs.length());
    for (int i = 0; i < total; i++) {
      final int at = i;
      JSONObject song = songs.optJSONObject(i);
      if (song == null) continue;
      boolean playing =
          service() != null
              && MusicData.identity(song).equals(MusicData.identity(service().song()));
      LinearLayout row = row(this);
      row.setPadding(d(12), d(8), d(12), d(8));
      row.setBackground(TvUi.focus(this, true));
      body.addView(row, lp(-1, 56));
      TextView index = label(playing ? "♫" : Integer.toString(i + 1), 15, playing ? ACCENT : TEXT);
      row.addView(index, lp(64, -2));
      TextView name = label(song.optString("name"), 18, playing ? ACCENT : TEXT);
      name.setSingleLine();
      name.setEllipsize(TextUtils.TruncateAt.END);
      boolean saved = MusicData.favorite(this, song) || current.type.equals("favorites");
      LinearLayout title =
          new LinearLayout(this) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
              if (View.MeasureSpec.getMode(widthSpec) != View.MeasureSpec.UNSPECIFIED) {
                int width = Math.max(0, View.MeasureSpec.getSize(widthSpec) - d(saved ? 70 : 44));
                if (name.getMaxWidth() != width) name.setMaxWidth(width);
              }
              super.onMeasure(widthSpec, heightSpec);
            }
          };
      title.setGravity(Gravity.CENTER_VERTICAL);
      row.addView(title, new LinearLayout.LayoutParams(0, -2, 1.6f));
      ImageView image = cover(this, 36);
      title.addView(image);
      Artwork.load(image, MusicData.cover(song));
      ((LinearLayout.LayoutParams) image.getLayoutParams()).rightMargin = d(8);
      title.addView(name, new LinearLayout.LayoutParams(-2, -2));
      if (saved) {
        TextView heart = TvUi.glyph(this, "favorite", 19, RED);
        heart.setTypeface(TvUi.filled);
        title.addView(heart, lp(26, 26));
      }
      TextView artist = label(MusicData.artist(song), 17, TEXT);
      artist.setSingleLine();
      artist.setEllipsize(TextUtils.TruncateAt.END);
      artist.setPadding(d(10), 0, d(8), 0);
      row.addView(artist, new LinearLayout.LayoutParams(0, -2, 1));
      if (showAlbum) {
        TextView album = label(MusicData.album(song).optString("name"), 16, TEXT);
        album.setSingleLine();
        album.setEllipsize(TextUtils.TruncateAt.END);
        album.setPadding(d(10), 0, d(8), 0);
        row.addView(album, new LinearLayout.LayoutParams(0, -2, 1.1f));
      }
      TextView duration = label(MusicData.time((int) song.optLong("dt")), 15, TEXT);
      duration.setGravity(Gravity.CENTER);
      row.addView(duration, lp(84, -2));
      focus.add(
          row, FocusRouter.CONTENT, contentRow++, 0, "song:" + MusicData.identity(song) + ":" + i);
      itemPositions.put("song:" + MusicData.identity(song) + ":" + i, i);
      row.setContentDescription(
          (i + 1) + "，" + song.optString("name") + "，" + MusicData.artist(song));
      row.setOnClickListener(
          v -> {
            if (queuePage && service() != null) service().playIndex(at);
            else play(songs, at);
          });
      row.setOnLongClickListener(
          v -> {
            songMenu(song, queuePage ? at : -1);
            return true;
          });
    }
    displayedItems = total;
    TvUi.setText(subtitle, songs.length() + " 首歌曲");
  }

  private void play(JSONArray songs, int at) {
    if (service() == null) {
      message("播放器正在启动，请稍候");
      return;
    }
    service().setQueue(songs, at);
    message("正在播放：" + songs.optJSONObject(at).optString("name"));
  }

  private void filterSongs() {
    inputDialog(
        "筛选歌曲",
        "输入歌曲或歌手",
        "",
        query -> {
          JSONArray found = new JSONArray();
          String q = query.toLowerCase(Locale.ROOT);
          for (int i = 0; i < current.items.length(); i++) {
            JSONObject song = current.items.optJSONObject(i);
            if ((song.optString("name") + " " + MusicData.artist(song))
                .toLowerCase(Locale.ROOT)
                .contains(q)) found.put(song);
          }
          Page p = new Page("songs", "筛选：" + query, current.section);
          p.loaded = true;
          p.items = found;
          push(p);
        });
  }

  private void searchPage() {
    current.kind = "songs";
    tabs.setGravity(Gravity.CENTER);
    Button query =
        action(
            current.query.isEmpty() ? "搜索" : "搜索：" + current.query,
            () -> inputDialog("搜索", "歌曲、歌手或专辑", current.query, this::searchFor),
            0,
            0,
            "search:input");
    query.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
    query.setPadding(d(16), 0, d(16), 0);
    query.setTextColor(current.query.isEmpty() ? MUTED : TEXT);
    android.graphics.drawable.GradientDrawable pill = fill(this, BASE, 30);
    pill.setStroke(d(1), BORDER);
    query.setBackground(pill);
    tabs.addView(query, lp(420, 36));
    if (current.query.isEmpty()) {
      scroll();
      JSONArray queries = MusicData.array(prefs().getString("searches", "[]"));
      if (queries.length() > 0) {
        section("最近搜索");
        for (int i = 0; i < queries.length(); i++) {
          String word = queries.optString(i);
          Button recent =
              action(word, () -> searchFor(word), contentRow++, 0, "search:history:" + i);
          recent.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
          body.addView(recent, lp(-1, 48));
          margin(recent, 0, 8);
        }
      }
      return;
    }
    if (!ready("search_all", Backend.json("query", current.query, "offset", current.offset)))
      return;
    if (current.items.length() == 0) {
      empty("没有找到结果", "试试其他关键词", null, null);
      return;
    }
    scroll();
    LinearLayout first = row(this);
    first.setGravity(Gravity.TOP);
    body.addView(first, lp(-1, -2));
    JSONArray albums = current.entity.optJSONArray("albums"),
        artists = current.entity.optJSONArray("artists");
    JSONObject top =
        albums != null && albums.length() > 0
            ? albums.optJSONObject(0)
            : artists != null && artists.length() > 0 ? artists.optJSONObject(0) : null;
    if (top != null) {
      String kind = albums != null && albums.length() > 0 ? "album" : "artist";
      LinearLayout column = column(this);
      first.addView(column, lp(244, -2));
      TextView title = label("最佳结果", 16, TEXT);
      heading(title);
      column.addView(title, lp(-1, 34));
      LinearLayout card = column(this);
      card.setPadding(d(12), d(12), d(12), d(12));
      card.setBackground(TvUi.focus(this, false));
      column.addView(card, lp(240, 320));
      ImageView cover = cover(this, 216);
      card.addView(cover);
      Artwork.load(cover, MusicData.cover(top));
      TextView category = label(kind.equals("album") ? "专辑" : "歌手", 13, ACCENT);
      category.setPadding(0, d(10), 0, d(6));
      card.addView(category);
      TextView name = label(top.optString("name"), 17, TEXT);
      heading(name);
      name.setSingleLine();
      name.setEllipsize(TextUtils.TruncateAt.END);
      card.addView(name);
      focus.add(card, FocusRouter.CONTENT, 1, 0, "search:top");
      card.setOnClickListener(v -> openEntity(kind, top));
    }
    LinearLayout songs = column(this);
    songs.setPadding(d(top == null ? 0 : 18), 0, 0, 0);
    first.addView(songs, new LinearLayout.LayoutParams(0, -2, 1));
    TextView songHeading = label("歌曲", 16, TEXT);
    heading(songHeading);
    songs.addView(songHeading, lp(-1, 34));
    for (int i = 0; i < Math.min(6, current.items.length()); i++) {
      final int index = i;
      JSONObject song = current.items.optJSONObject(i);
      LinearLayout row = row(this);
      row.setPadding(d(10), d(7), d(10), d(7));
      row.setBackground(TvUi.focus(this, true));
      songs.addView(row, lp(-1, 47));
      ImageView art = cover(this, 32);
      row.addView(art);
      Artwork.load(art, MusicData.cover(song));
      LinearLayout text = column(this);
      text.setPadding(d(10), 0, d(8), 0);
      row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
      TextView title = label(song.optString("name"), 16, TEXT);
      title.setSingleLine();
      title.setEllipsize(TextUtils.TruncateAt.END);
      text.addView(title);
      TextView artist = label(MusicData.artist(song), 12, MUTED);
      artist.setSingleLine();
      text.addView(artist);
      row.addView(label(MusicData.time((int) song.optLong("dt")), 12, MUTED), lp(44, -2));
      focus.add(row, FocusRouter.CONTENT, i + 1, 1, "search:song:" + i);
      row.setOnClickListener(v -> play(current.items, index));
      row.setOnLongClickListener(
          v -> {
            songMenu(song, -1);
            return true;
          });
    }
    Button all =
        action(
            "显示全部歌曲",
            () -> {
              Page page = new Page("songs", "搜索：" + current.query, "search");
              page.items = current.items;
              page.kind = "songs";
              page.more = current.more;
              page.pageOp = "search_all";
              page.pageArgs = Backend.json("query", current.query, "offset", current.offset);
              page.offset = current.offset;
              page.loaded = true;
              push(page);
            },
            7,
            1,
            "search:all");
    songs.addView(all, lp(-1, 38));
    contentRow = 8;
    entityStrip("专辑", albums, "album");
    entityStrip("歌手", artists, "artist");
  }

  private void entityStrip(String title, JSONArray entities, String kind) {
    if (entities == null || entities.length() == 0) return;
    section(title);
    LinearLayout row = row(this);
    row.setGravity(Gravity.TOP);
    body.addView(row, lp(-1, -2));
    for (int i = 0; i < Math.min(5, entities.length()); i++) {
      JSONObject item = entities.optJSONObject(i);
      LinearLayout card = column(this);
      card.setPadding(d(8), d(8), d(8), d(12));
      card.setBackground(TvUi.focus(this, true));
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, d(220), 1);
      params.rightMargin = d(10);
      row.addView(card, params);
      ImageView art = new ImageView(this);
      art.setScaleType(ImageView.ScaleType.CENTER_CROP);
      art.setBackground(fill(this, SURFACE, kind.equals("artist") ? 120 : 8));
      art.setClipToOutline(true);
      card.addView(art, new LinearLayout.LayoutParams(-1, 0, 1));
      Artwork.load(art, MusicData.cover(item));
      TextView name = label(item.optString("name"), 16, TEXT);
      name.setMaxLines(2);
      name.setEllipsize(TextUtils.TruncateAt.END);
      name.setPadding(0, d(8), 0, 0);
      card.addView(name, lp(-1, 48));
      focus.add(card, FocusRouter.CONTENT, contentRow, i, "search:" + kind + ":" + i);
      card.setOnClickListener(v -> openEntity(kind, item));
    }
    contentRow++;
  }

  private void searchFor(String query) {
    query = query.trim();
    if (query.isEmpty()) return;
    JSONArray old = MusicData.array(prefs().getString("searches", "[]")), saved = new JSONArray();
    saved.put(query);
    for (int i = 0; i < old.length() && saved.length() < 12; i++)
      if (!query.equals(old.optString(i))) saved.put(old.optString(i));
    prefs().edit().putString("searches", saved.toString()).apply();
    current.query = query;
    current.loaded = false;
    current.loading = false;
    current.failure = "";
    current.offset = 0;
    current.scrollY = 0;
    current.visible = PAGE_SIZE;
    current.generation++;
    current.restoreFocus = "search:input";
    render();
  }

  private void player() {
    PlaybackService s = service();
    Button back = action("", this::back, 0, 0, "player:back");
    TvUi.icon(back, "arrow_back");
    back.setPadding(0, 0, 0, 0);
    back.setBackground(TvUi.focus(this, true));
    back.setContentDescription("返回上一页");
    tabs.addView(back, lp(40, 40));
    tabs.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    if (s != null && s.hasSong()) {
      Button favorite = action("", () -> toggleFavorite(s.song()), 0, 1, "player:favorite");
      playerFavoriteButton = favorite;
      TvUi.icon(favorite, "favorite");
      favorite.setTypeface(MusicData.favorite(this, s.song()) ? TvUi.filled : TvUi.symbols);
      favorite.setBackground(TvUi.focus(this, true));
      favorite.setPadding(0, 0, 0, 0);
      favorite.setContentDescription("收藏当前歌曲");
      tabs.addView(favorite, lp(40, 40));
    }
    Button settings = action("", this::lyricSettings, 0, 2, "player:lyrics-settings");
    TvUi.icon(settings, "tune");
    settings.setPadding(0, 0, 0, 0);
    settings.setBackground(TvUi.focus(this, true));
    settings.setContentDescription("歌词设置");
    tabs.addView(settings, lp(40, 40));
    LinearLayout toggle = row(this);
    toggle.setPadding(d(8), 0, d(8), 0);
    android.graphics.drawable.GradientDrawable shape = fill(this, 0x24383838, 28);
    shape.setStroke(d(1), 0x66888888);
    toggle.setBackground(shape);
    toggle.addView(TvUi.glyph(this, "lyrics", 22, TEXT), lp(28, 30));
    TextView state = label(current.tab == 0 ? "开" : "关", 14, TEXT);
    state.setGravity(Gravity.CENTER);
    state.setBackground(fill(this, current.tab == 0 ? 0x665d5d5d : 0x22383838, 20));
    toggle.addView(state, lp(34, 26));
    tabs.addView(toggle, lp(-2, 36));
    focus.add(toggle, FocusRouter.CONTENT, 0, 3, "player:lyric-toggle");
    toggle.setContentDescription(current.tab == 0 ? "显示播放队列" : "显示歌词");
    toggle.setOnClickListener(
        v -> {
          snapshot();
          current.tab = current.tab == 0 ? 1 : 0;
          render();
        });
    if (s == null || !s.hasSong()) {
      empty("音乐尚未开始", "选择歌曲后显示封面和歌词", null, null);
      return;
    }
    stage.setBackgroundColor(Color.TRANSPARENT);
    ArtworkBackdrop backdrop = new ArtworkBackdrop(BASE, 0, d(8));
    content.setBackground(backdrop);
    LinearLayout columns = row(this);
    columns.setGravity(Gravity.TOP);
    stage.addView(columns, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout artwork = column(this);
    artwork.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    artwork.setPadding(d(18), 0, d(24), d(12));
    columns.addView(artwork, new LinearLayout.LayoutParams(0, -1, .64f));
    int height =
        (int)
                (getResources().getDisplayMetrics().heightPixels
                    / (getResources().getDisplayMetrics().density * TvUi.SCALE))
            - 164;
    int artSize = Math.max(160, Math.min(260, (int) (height * .55f)));
    playerCover = cover(this, artSize);
    artwork.addView(playerCover);
    Page source = current;
    Artwork.load(
        playerCover,
        MusicData.cover(s.song()),
        bitmap -> {
          if (current == source) backdrop.artwork(0, bitmap);
        });
    TextView title = label(s.title(), 24, TEXT);
    heading(title);
    title.setGravity(Gravity.CENTER);
    title.setMaxLines(2);
    title.setEllipsize(TextUtils.TruncateAt.END);
    title.setPadding(0, d(14), 0, d(6));
    artwork.addView(title, lp(-1, -2));
    TextView artist = label(MusicData.artist(s.song()), 20, MUTED);
    artist.setGravity(Gravity.CENTER);
    artist.setMaxLines(2);
    artwork.addView(artist, lp(-1, -2));
    TextView album = label(MusicData.album(s.song()).optString("name"), 17, MUTED);
    album.setGravity(Gravity.CENTER);
    album.setSingleLine();
    album.setEllipsize(TextUtils.TruncateAt.END);
    album.setPadding(0, d(4), 0, 0);
    artwork.addView(album, lp(-1, -2));
    LinearLayout right = column(this);
    right.setPadding(d(8), 0, d(4), 0);
    columns.addView(right, new LinearLayout.LayoutParams(0, -1, .36f));
    TextView section = label(current.tab == 0 ? "歌词" : "接下来", 18, TEXT);
    heading(section);
    right.addView(section, lp(-1, 32));
    View line = new View(this);
    line.setBackgroundColor(BORDER);
    right.addView(line, lp(-1, 1));
    if (current.tab == 1) {
      ScrollView list = new ScrollView(this);
      list.setFocusable(false);
      LinearLayout entries = column(this);
      list.addView(entries);
      right.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
      for (int i = Math.max(0, s.index()); i < Math.min(s.queue().length(), s.index() + 40); i++) {
        final int at = i;
        JSONObject song = s.queue().optJSONObject(i);
        Button b =
            action(
                song.optString("name"), () -> s.playIndex(at), i - s.index() + 1, 1, "upnext:" + i);
        b.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        entries.addView(b, lp(-1, 54));
        margin(b, 0, 6);
      }
    } else {
      lyricScroll = new ScrollView(this);
      lyricScroll.setFocusable(false);
      lyricScroll.setFillViewport(false);
      lyricScroll.setVerticalScrollBarEnabled(true);
      lyricLines = column(this);
      lyricLines.setPadding(0, d(6), d(4), d(28));
      lyricScroll.addView(lyricLines);
      right.addView(lyricScroll, new LinearLayout.LayoutParams(-1, 0, 1));
      paintedLyrics = null;
      lastLyric = -2;
      refreshLyrics(s);
    }
    if (current.restoreFocus.isEmpty()) current.restoreFocus = "player:back";
  }

  private void refreshLyrics(PlaybackService s) {
    if (lyricLines == null) return;
    MusicData.Lyrics lyrics = s.lyrics;
    if (paintedLyrics != lyrics) {
      // A service callback may replace the model without replacing this page.
      focus.removePrefix("lyric:");
      paintedLyrics = lyrics;
      lyricTextViews.clear();
      lyricRowViews.clear();
      lyricLines.removeAllViews();
      lastLyric = -2;
      if (lyrics.lines.isEmpty()) {
        TextView text =
            label(
                lyrics.plain.isEmpty() ? (s.loading ? "正在加载歌词…" : "暂无歌词") : lyrics.plain,
                22,
                MUTED);
        text.setPadding(d(8), d(18), d(8), d(18));
        lyricLines.addView(text, lp(-1, -2));
      } else
        for (int i = 0; i < lyrics.lines.size(); i++) {
          final int at = i;
          LinearLayout row = column(this);
          row.setPadding(d(8), d(14), d(8), d(14));
          row.setBackground(TvUi.focus(this, true));
          TextView text = label(lyrics.lines.get(i), 22, MUTED);
          text.setMaxLines(5);
          row.addView(text, lp(-1, -2));
          lyricTextViews.add(text);
          if (prefs().getBoolean("lyric_translation", true)) {
            String roman = lyrics.romanizations.get(i), translation = lyrics.translations.get(i);
            for (String extra : new String[] {roman, translation})
              if (!extra.isEmpty() && !extra.equals(lyrics.lines.get(i))) {
                TextView detail = label(extra, 17, MUTED);
                detail.setPadding(0, d(6), 0, 0);
                row.addView(detail, lp(-1, -2));
              }
          }
          lyricLines.addView(row, lp(-1, -2));
          lyricRowViews.add(row);
          focus.add(row, FocusRouter.CONTENT, i + 1, 1, "lyric:" + i);
          row.setContentDescription(lyrics.lines.get(i));
          row.setOnClickListener(
              v ->
                  s.seek(
                      (int) Math.max(0, lyrics.times.get(at) - prefs().getInt("lyric_offset", 0))));
        }
    }
    int active = lyrics.at((long) s.position() + prefs().getInt("lyric_offset", 0));
    if (active == lastLyric) return;
    if (lastLyric >= 0 && lastLyric < lyricTextViews.size()) {
      TextView old = lyricTextViews.get(lastLyric);
      old.setTypeface(TvUi.font);
      old.setTextSize(22 * TvUi.SCALE);
      old.setTextColor(MUTED);
    }
    lastLyric = active;
    if (active >= 0 && active < lyricTextViews.size()) {
      TextView text = lyricTextViews.get(active);
      heading(text);
      text.setTextSize(prefs().getInt("lyric_size", 36) * TvUi.SCALE);
      text.setTextColor(TEXT);
      if (!focus.key(getCurrentFocus()).startsWith("lyric:"))
        lyricScroll.post(
            () -> {
              if (lyricScroll != null && active < lyricRowViews.size())
                lyricScroll.smoothScrollTo(
                    0,
                    Math.max(0, lyricRowViews.get(active).getTop() - lyricScroll.getHeight() / 3));
            });
    }
  }

  private ScrollView lyricScroll;
  private LinearLayout lyricLines;
  private MusicData.Lyrics paintedLyrics;
  private final ArrayList<TextView> lyricTextViews = new ArrayList<>();
  private final ArrayList<LinearLayout> lyricRowViews = new ArrayList<>();
  private QueueSheet queueSheet;

  private void showQueue() {
    if (service() == null) return;
    if (queueSheet != null && queueSheet.isShowing()) {
      queueSheet.dismiss();
      return;
    }
    int[] position = new int[2];
    content.getLocationOnScreen(position);
    android.graphics.Rect panelBounds =
        new android.graphics.Rect(
            position[0],
            position[1],
            position[0] + content.getWidth(),
            position[1] + content.getHeight());
    queueSheet =
        new QueueSheet(
            this,
            service(),
            panelBounds,
            this::songMenu,
            this::toggleFavorite,
            () ->
                confirm(
                    "清空播放队列？",
                    "当前播放也会停止。",
                    () -> {
                      if (service() != null) service().clear();
                    }));
    queueSheet.show();
  }

  private void queue() {
    PlaybackService s = service();
    if (s == null || s.queue().length() == 0) {
      empty(
          "播放队列为空",
          "在歌曲上长按确定，可加入队列或下一首播放",
          "发现音乐",
          () -> navigate(new Page("home", "发现音乐", "home"), true));
      return;
    }
    Button clear =
        action(
            "清空队列",
            () ->
                confirm(
                    "清空播放队列？",
                    "当前播放也会停止。",
                    () -> {
                      s.clear();
                      render();
                    }),
            0,
            0,
            "queue:clear");
    tabs.addView(clear, lp(130, 42));
    margin(clear, 8, 0);
    Button mode = action(s.modeLabel(), s::cycleMode, 0, 1, "queue:mode");
    tabs.addView(mode, lp(130, 42));
    scroll();
    addSongRows(s.queue(), true);
  }

  private void songMenu(JSONObject song, int queueIndex) {
    String title = song.optString("name", "歌曲操作");
    ArrayList<String> labels = new ArrayList<>();
    ArrayList<Runnable> actions = new ArrayList<>();
    labels.add("列设置");
    actions.add(
        () ->
            choice(
                "显示列",
                new String[] {"标题、艺人、长度", "标题、艺人、专辑、长度"},
                prefs().getBoolean("show_album_column", true) ? 1 : 0,
                at -> {
                  prefs().edit().putBoolean("show_album_column", at == 1).apply();
                  snapshot();
                  render();
                }));
    labels.add("立即播放");
    actions.add(
        () -> {
          if (queueIndex >= 0 && service() != null) service().playIndex(queueIndex);
          else play(new JSONArray().put(song), 0);
        });
    labels.add("下一首播放");
    actions.add(
        () -> {
          if (service() != null) {
            service().enqueue(song, true);
            message("已加入下一首播放");
          }
        });
    labels.add("加入播放队列");
    actions.add(
        () -> {
          if (service() != null) {
            service().enqueue(song, false);
            message("已加入播放队列");
          }
        });
    labels.add(MusicData.favorite(this, song) ? "取消喜欢" : "喜欢这首歌");
    actions.add(() -> toggleFavorite(song));
    JSONObject album = MusicData.album(song);
    if (!song.has("uri") && album.optLong("id") > 0) {
      labels.add("查看专辑");
      actions.add(() -> openEntity("album", album));
    }
    JSONArray artists = song.optJSONArray("ar");
    if (!song.has("uri") && artists != null && artists.length() > 0) {
      JSONObject artist = artists.optJSONObject(0);
      if (artist != null && artist.optLong("id") > 0) {
        labels.add("查看歌手");
        actions.add(() -> openEntity("artist", artist));
      }
    }
    if (!song.has("uri")) {
      labels.add("相似歌曲");
      actions.add(
          () -> {
            Page p = new Page("songs", "与「" + song.optString("name") + "」相似的歌曲", current.section);
            push(p);
            request(
                "similar",
                Backend.json("id", song.optLong("id")),
                (data, error) -> {
                  if (error != null) {
                    message(error);
                    return;
                  }
                  p.items = (JSONArray) data;
                  p.loaded = true;
                  if (current == p) render();
                });
          });
    }
    if (queueIndex >= 0) {
      if (queueIndex > 0) {
        labels.add("队列中上移");
        actions.add(
            () -> {
              service().move(queueIndex, queueIndex - 1);
              render();
            });
      }
      if (service() != null && queueIndex < service().queue().length() - 1) {
        labels.add("队列中下移");
        actions.add(
            () -> {
              service().move(queueIndex, queueIndex + 1);
              render();
            });
      }
      labels.add("从队列移除");
      actions.add(
          () -> {
            if (service() != null) service().remove(queueIndex);
            render();
          });
    }
    choice(title, labels.toArray(new String[0]), -1, at -> actions.get(at).run());
  }

  private void toggleFavorite(JSONObject song) {
    pageCache.clear();
    if (song.length() == 0) return;
    boolean value = !MusicData.favorite(this, song);
    if (song.has("uri")) {
      MusicData.favorite(this, song, value);
      updatePlayback();
      message(value ? "已加入本机收藏" : "已取消本机收藏");
      return;
    }
    if (accountId == 0) {
      MusicData.favorite(this, song, value);
      updatePlayback();
      message(value ? "已加入本机收藏；登录后可收藏到网易云" : "已取消本机收藏");
      return;
    }
    confirm(
        value ? "喜欢这首歌？" : "取消喜欢？",
        "将同步到当前网易云账号。",
        () ->
            request(
                "like",
                Backend.json("id", song.optLong("id"), "value", value),
                (data, error) -> {
                  if (error != null) {
                    message(error);
                    return;
                  }
                  MusicData.favorite(this, song, value);
                  message(value ? "已加入喜欢的歌曲" : "已取消喜欢");
                  updatePlayback();
                }));
  }

  private void subscribe(String kind, JSONObject entity) {
    pageCache.clear();
    if (accountId == 0) {
      push(new Page("login", "网易云账号", current.section));
      return;
    }
    choice(
        entity.optString("name"),
        new String[] {"收藏到网易云", "取消收藏"},
        -1,
        at ->
            confirm(
                at == 0 ? "收藏此内容？" : "取消收藏？",
                "将同步到当前网易云账号。",
                () ->
                    request(
                        "subscribe",
                        Backend.json("id", entity.optLong("id"), "kind", kind, "value", at == 0),
                        (data, error) -> message(error == null ? "已更新收藏" : error))));
  }

  private boolean storageAllowed() {
    if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        == PackageManager.PERMISSION_GRANTED) return true;
    empty(
        "访问本地音乐",
        "允许读取音乐文件后，可浏览电视和 USB 存储。",
        "允许访问",
        () -> requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE}, 41));
    return false;
  }

  public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (request == 41) {
      current.loaded = false;
      current.failure = "";
      render();
    }
  }

  private void localLibrary() {
    if (!storageAllowed()) return;
    if (!current.failure.isEmpty()) {
      empty(
          "未能读取本地音乐",
          current.failure,
          "重试",
          () -> {
            current.failure = "";
            current.loaded = false;
            render();
          });
      return;
    }
    if (!current.loaded) {
      empty("正在扫描音乐", "扫描电视和已挂载存储中的音频文件。", null, null);
      if (!current.loading) {
        Page target = current;
        int generation = target.generation;
        target.loading = true;
        files.execute(
            () -> {
              try {
                JSONArray songs = LocalMusic.library(getApplicationContext());
                handler.post(
                    () -> {
                      if (destroyed || target.generation != generation) return;
                      target.items = songs;
                      target.loaded = true;
                      target.loading = false;
                      if (current == target) {
                        snapshot();
                        render();
                      }
                    });
              } catch (Exception e) {
                handler.post(
                    () -> {
                      if (destroyed || target.generation != generation) return;
                      target.loading = false;
                      target.failure = "无法读取媒体库，请检查存储权限";
                      if (current == target) {
                        snapshot();
                        render();
                      }
                    });
              }
            });
      }
      return;
    }
    if (current.items.length() == 0) {
      empty(
          "没有扫描到音乐",
          "可以在「浏览文件」中直接打开音乐文件。",
          "浏览存储",
          () -> navigate(new Page("browse", "浏览文件", "browse"), true));
      return;
    }
    songList();
  }

  private void browseHeader() {
    Button back =
        action(
            "",
            () -> {
              if (!current.path.isEmpty()) openFolder("", false);
            },
            0,
            0,
            "browse:back");
    TvUi.icon(back, "arrow_back");
    back.setBackground(TvUi.focus(this, true));
    back.setPadding(0, 0, 0, 0);
    back.setContentDescription("返回音乐库文件夹");
    back.setEnabled(!current.path.isEmpty());
    back.setAlpha(current.path.isEmpty() ? .35f : 1);
    tabs.addView(back, lp(36, 38));
    Button home = action("", () -> openFolder("", false), 0, 1, "browse:home");
    TvUi.icon(home, "home");
    home.setTypeface(TvUi.filled);
    home.setPadding(0, 0, 0, 0);
    home.setBackground(TvUi.focus(this, true));
    home.setContentDescription("音乐库文件夹");
    tabs.addView(home, lp(36, 38));
    TextView breadcrumb =
        label(current.path.isEmpty() ? "" : new File(current.path).getName(), 15, MUTED);
    breadcrumb.setSingleLine();
    breadcrumb.setEllipsize(TextUtils.TruncateAt.END);
    breadcrumb.setPadding(d(8), 0, 0, 0);
    tabs.addView(breadcrumb, new LinearLayout.LayoutParams(0, -2, 1));
    LinearLayout pill = row(this);
    android.graphics.drawable.GradientDrawable shape = fill(this, SURFACE, 28);
    shape.setStroke(d(1), BORDER);
    pill.setBackground(shape);
    pill.setPadding(d(4), 0, d(4), 0);
    tabs.addView(pill, lp(-2, 34));
    boolean cards = prefs().getBoolean("browse_cards", false);
    Button view =
        action(
            "",
            () -> {
              snapshot();
              prefs().edit().putBoolean("browse_cards", !cards).apply();
              render();
            },
            0,
            2,
            "browse:view");
    TvUi.icon(view, cards ? "view_list" : "grid_view");
    view.setPadding(0, 0, 0, 0);
    view.setBackground(TvUi.focus(this, true));
    view.setContentDescription(cards ? "切换列表视图" : "切换封面视图");
    pill.addView(view, lp(32, 30));
    if (!cards) {
      View divider = new View(this);
      divider.setBackgroundColor(BORDER);
      pill.addView(divider, lp(1, 20));
      LinearLayout columns = row(this);
      columns.setPadding(d(8), 0, d(8), 0);
      columns.setBackground(TvUi.focus(this, true));
      columns.addView(TvUi.glyph(this, "tune", 17, TEXT), lp(20, 24));
      columns.addView(label("列", 14, TEXT));
      pill.addView(columns, lp(-2, 30));
      focus.add(columns, FocusRouter.CONTENT, 0, 3, "browse:columns");
      columns.setContentDescription("歌曲列表列设置");
      columns.setOnClickListener(
          v ->
              choice(
                  "专辑列",
                  new String[] {"显示", "隐藏"},
                  prefs().getBoolean("show_album_column", true) ? 0 : 1,
                  at -> {
                    prefs().edit().putBoolean("show_album_column", at == 0).apply();
                    render();
                  }));
    }
  }

  private void browseEmpty() {
    LinearLayout box = column(this);
    box.setGravity(Gravity.CENTER);
    stage.addView(box, new FrameLayout.LayoutParams(-1, -1));
    box.addView(TvUi.glyph(this, "folder_off", 64, 0xff5d5d5d), lp(76, 76));
    TextView title = label("尚未配置音乐库文件夹", 20, TEXT);
    heading(title);
    title.setGravity(Gravity.CENTER);
    title.setPadding(0, d(12), 0, d(10));
    box.addView(title, lp(-1, -2));
    TextView copy = label("打开“设置”添加文件夹", 15, MUTED);
    copy.setGravity(Gravity.CENTER);
    box.addView(copy, lp(-1, -2));
    LinearLayout action = row(this);
    action.setGravity(Gravity.CENTER);
    action.setPadding(d(12), 0, d(12), 0);
    action.setBackground(TvUi.focus(this, true));
    action.addView(TvUi.glyph(this, "settings", 17, TEXT), lp(22, 24));
    TextView text = label("打开“设置”", 15, TEXT);
    text.setPadding(d(5), 0, 0, 0);
    action.addView(text);
    box.addView(action, lp(-2, 38));
    margin(action, 0, 0);
    ((LinearLayout.LayoutParams) action.getLayoutParams()).topMargin = d(14);
    focus.add(action, FocusRouter.CONTENT, 1, 0, "browse:settings");
    action.setContentDescription("打开设置添加音乐库文件夹");
    action.setOnClickListener(
        v -> {
          Page page = new Page("settings", "设置", "settings");
          page.tab = 0;
          push(page);
        });
  }

  private void browse() {
    browseHeader();
    JSONArray configured = MusicData.array(prefs().getString("library_folders", "[]"));
    if (current.path.isEmpty() && configured.length() == 0) {
      browseEmpty();
      return;
    }
    if (!storageAllowed()) return;
    if (current.path.isEmpty()) {
      current.items = new JSONArray();
      for (int i = 0; i < configured.length(); i++) {
        String path = configured.optString(i);
        current.items.put(
            Backend.json("name", new File(path).getName(), "path", path, "directory", true));
      }
      current.loaded = true;
    }
    if (!current.failure.isEmpty()) {
      empty("无法打开文件夹", current.failure, "返回上一级", this::back);
      return;
    }
    if (!current.loaded) {
      empty("正在读取文件夹", "支持常见音乐格式；可直接用遥控器打开。", null, null);
      if (!current.loading) {
        Page target = current;
        target.loading = true;
        files.execute(
            () -> {
              try {
                JSONArray entries = LocalMusic.folder(target.path);
                handler.post(
                    () -> {
                      if (destroyed) return;
                      target.items = entries;
                      target.loaded = true;
                      target.loading = false;
                      if (current == target) {
                        snapshot();
                        render();
                      }
                    });
              } catch (Exception e) {
                handler.post(
                    () -> {
                      if (destroyed) return;
                      target.loading = false;
                      target.failure = e.getMessage();
                      if (current == target) {
                        snapshot();
                        render();
                      }
                    });
              }
            });
      }
      return;
    }
    scroll();
    if (!current.path.isEmpty()) {
      String parent = new File(current.path).getParent();
      Button up =
          action(
              "↑ 上一级文件夹",
              () -> openFolder(parent == null ? "" : parent, true),
              contentRow++,
              0,
              "browse:up");
      body.addView(up, lp(-1, 50));
      margin(up, 0, 8);
    }
    JSONArray songs = new JSONArray();
    for (int i = 0; i < current.items.length(); i++)
      if (!current.items.optJSONObject(i).optBoolean("directory"))
        songs.put(current.items.optJSONObject(i));
    for (int i = 0; i < Math.min(current.visible, current.items.length()); i++) {
      JSONObject entry = current.items.optJSONObject(i);
      boolean folder = entry.optBoolean("directory");
      String path = entry.optString("path");
      int songIndex = -1;
      for (int n = 0; n < songs.length(); n++)
        if (songs.optJSONObject(n) == entry) {
          songIndex = n;
          break;
        }
      final int at = songIndex;
      boolean cards = prefs().getBoolean("browse_cards", false);
      LinearLayout entryView = cards ? column(this) : row(this);
      entryView.setGravity(cards ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
      entryView.setPadding(d(12), d(8), d(12), d(8));
      entryView.setBackground(TvUi.focus(this, true));
      entryView.addView(
          TvUi.glyph(
              this, folder ? "folder" : "music_note", cards ? 44 : 24, folder ? MUTED : ACCENT),
          lp(cards ? 52 : 32, cards ? 52 : 32));
      TextView entryName = label(entry.optString("name"), 17, TEXT);
      entryName.setSingleLine();
      entryName.setEllipsize(TextUtils.TruncateAt.END);
      entryName.setPadding(d(cards ? 0 : 12), 0, 0, 0);
      entryView.addView(
          entryName, new LinearLayout.LayoutParams(cards ? -1 : 0, -2, cards ? 0 : 1));
      if (cards) {
        LinearLayout cardRow;
        if (i % 4 == 0) {
          cardRow = row(this);
          body.addView(cardRow, lp(-1, 116));
        } else cardRow = (LinearLayout) body.getChildAt(body.getChildCount() - 1);
        cardRow.addView(entryView, new LinearLayout.LayoutParams(0, -1, 1));
        margin(entryView, 12, 8);
      } else {
        body.addView(entryView, lp(-1, 50));
        margin(entryView, 0, 2);
      }
      focus.add(
          entryView,
          FocusRouter.CONTENT,
          cards ? contentRow + i / 4 : contentRow + i,
          cards ? i % 4 : 0,
          "file:" + path);
      entryView.setContentDescription(entry.optString("name"));
      entryView.setOnClickListener(
          v -> {
            if (folder) openFolder(path, true);
            else play(songs, at);
          });
      itemPositions.put("file:" + path, i);
      View b = entryView;
      if (!folder)
        b.setOnLongClickListener(
            v -> {
              songMenu(entry, -1);
              return true;
            });
    }
    if (current.items.length() == 0) body.addView(label("文件夹中没有音乐或子文件夹。", 19, MUTED));
    contentRow += current.items.length();
    displayedItems = Math.min(current.visible, current.items.length());
  }

  private void openFolder(String path, boolean addHistory) {
    Page p = new Page("browse", "浏览文件", "browse");
    p.path = path;
    if (addHistory) push(p);
    else {
      snapshot();
      navigate(p, false);
    }
  }

  private void settings() {
    tabRow(new String[] {"音乐库", "播放", "界面", "服务", "关于"});
    scroll();
    if (current.tab == 1) {
      playbackSettings();
      section("音频输出");
      String quality = prefs().getString("quality", "exhigh");
      String[] levels = {"standard", "higher", "exhigh", "lossless", "hires"};
      String[] labels = {"标准 · 128 kbps", "较高 · 192 kbps", "极高 · 320 kbps", "无损", "Hi-Res"};
      int selected = Arrays.asList(levels).indexOf(quality);
      setting(
          "云端播放音质",
          labels[Math.max(0, selected)],
          () ->
              choice(
                  "云端播放音质",
                  labels,
                  selected,
                  at -> {
                    prefs().edit().putString("quality", levels[at]).apply();
                    render();
                    message("下次播放时生效，实际音质取决于账号和曲目权限");
                  }));
      setting(
          "播放模式",
          service() == null ? "列表循环" : service().modeLabel(),
          () -> {
            if (service() != null) service().cycleMode();
            render();
          });
      setting("系统音量", "左右键调整音量", this::volumeDialog);
      setting(
          "均衡器", prefs().getBoolean("eq_enabled", false) ? "已开启" : "已关闭", this::equalizerDialog);
      setting(
          "睡眠定时",
          service() != null && service().sleepDeadline > 0 ? "定时关闭中" : "未设置",
          () ->
              choice(
                  "睡眠定时",
                  new String[] {"关闭定时", "15 分钟", "30 分钟", "60 分钟", "90 分钟"},
                  -1,
                  at -> {
                    if (service() != null) service().sleep(new int[] {0, 15, 30, 60, 90}[at]);
                    render();
                  }));
      setting(
          "停止播放并退出",
          "停止后台播放服务",
          () ->
              confirm(
                  "停止播放并退出？",
                  "当前音乐将停止。",
                  () -> {
                    stopService(new Intent(this, PlaybackService.class));
                    finish();
                  }));
    } else if (current.tab == 2) {
      appearance();
    } else if (current.tab == 0) {
      librarySettings();
      section("本机数据");
      setting(
          "清空本机最近播放",
          "不影响云端记录",
          () ->
              confirm(
                  "清空最近播放？",
                  "只清除本机播放记录。",
                  () -> {
                    prefs().edit().remove("history").apply();
                    message("已清除本机最近播放");
                  }));
      setting(
          "清空本机收藏",
          "不影响网易云收藏",
          () ->
              confirm(
                  "清空本机收藏？",
                  "只清除本机收藏列表。",
                  () -> {
                    prefs().edit().remove("favorites").apply();
                    updatePlayback();
                    message("已清除本机收藏");
                  }));
    } else if (current.tab == 3) {
      section("网易云账号");
      setting(
          "当前账号",
          accountId == 0 ? "未登录" : nickname,
          () -> push(new Page("login", "网易云账号", "settings")));
      setting("扫码登录", "使用手机网易云音乐确认", () -> push(new Page("login", "网易云账号", "settings")));
      if (accountId != 0)
        setting(
            "退出账号",
            nickname,
            () ->
                confirm(
                    "退出网易云账号？",
                    "本机收藏和本地音乐会保留。",
                    () ->
                        request(
                            "logout",
                            Backend.json(),
                            (data, error) -> {
                              if (error != null) {
                                message(error);
                                return;
                              }
                              accountId = 0;
                              nickname = "未登录";
                              backend.clearSession();
                              TvUi.setText(accountLabel, nickname);
                              render();
                            })));
    } else {
      section("Netodia TV " + appVersion());
      TextView about =
          label(
              "基于 Netodia 的电视版\nRust 音乐服务 · Android TV 界面\n许可：AGPL-3.0-or-later\n\n设备："
                  + Build.MODEL
                  + " · Android "
                  + Build.VERSION.RELEASE,
              18,
              MUTED);
      about.setLineSpacing(d(8), 1);
      body.addView(about);
      setting(
          "查看许可",
          "开源许可与项目信息",
          () ->
              info(
                  "开源许可",
                  "Netodia 按 GNU Affero GPL v3 或更高版本发布。\n"
                      + "项目：https://github.com/HELPMEEADICE/Netodia\n"
                      + "多屏互动安装脚本来自 pengchujin/aerial-tv（MIT）。\n"
                      + "完整许可位于随附源码及 APK 资源中。"));
    }
  }

  // These names and icons correspond directly to the original Slint sections.
  private void librarySettings() {
    section("音乐库文件夹");
    LinearLayout card = settingsCard();
    LinearLayout header = row(this);
    header.setPadding(d(16), d(16), d(16), d(16));
    TextView title = label("管理文件夹", 17, TEXT);
    heading(title);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    LinearLayout add = row(this);
    add.setPadding(d(14), 0, d(14), 0);
    android.graphics.drawable.StateListDrawable addStates =
        new android.graphics.drawable.StateListDrawable();
    android.graphics.drawable.GradientDrawable selected = fill(this, ACCENT, 32);
    selected.setStroke(d(2), TEXT);
    addStates.addState(new int[] {android.R.attr.state_focused}, selected);
    addStates.addState(new int[] {}, fill(this, ACCENT, 32));
    add.setBackground(addStates);
    add.addView(TvUi.glyph(this, "create_new_folder", 21, MANTLE), lp(24, 28));
    TextView addLabel = label("添加文件夹", 16, MANTLE);
    heading(addLabel);
    addLabel.setPadding(d(6), 0, 0, 0);
    add.addView(addLabel);
    header.addView(add, lp(-2, 38));
    focus.add(add, FocusRouter.CONTENT, contentRow++, 0, "settings:add-folder");
    add.setContentDescription("添加文件夹");
    add.setOnClickListener(
        v -> {
          if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
              != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE}, 41);
          } else chooseLibraryFolder("/storage");
        });
    card.addView(header, lp(-1, 70));
    settingsDivider(card);
    JSONArray folders = MusicData.array(prefs().getString("library_folders", "[]"));
    if (folders.length() == 0) {
      LinearLayout empty = column(this);
      empty.setGravity(Gravity.CENTER);
      empty.addView(TvUi.glyph(this, "folder_open", 36, MUTED), lp(48, 48));
      TextView emptyTitle = label("尚未添加文件夹", 18, TEXT);
      heading(emptyTitle);
      emptyTitle.setGravity(Gravity.CENTER);
      emptyTitle.setPadding(0, d(8), 0, d(8));
      empty.addView(emptyTitle);
      TextView emptyDescription = label("添加用于扫描音乐文件的文件夹。", 15, MUTED);
      emptyDescription.setGravity(Gravity.CENTER);
      empty.addView(emptyDescription);
      card.addView(empty, lp(-1, 148));
    } else {
      for (int i = 0; i < folders.length(); i++) {
        final String path = folders.optString(i);
        if (i > 0) settingsDivider(card);
        LinearLayout folderRow = settingsRow(card, "folder", new File(path).getName(), path);
        TextView arrow = TvUi.glyph(this, "chevron_right", 24, MUTED);
        folderRow.addView(arrow, lp(32, 32));
        focus.add(folderRow, FocusRouter.CONTENT, contentRow++, 0, "settings:folder:" + path);
        folderRow.setContentDescription(path);
        folderRow.setOnClickListener(
            v ->
                choice(
                    path,
                    new String[] {"浏览文件夹", "从音乐库移除"},
                    -1,
                    at -> {
                      if (at == 0) openFolder(path, true);
                      else
                        confirm(
                            "移除文件夹？",
                            "只移除音乐库目录，不删除文件。",
                            () -> {
                              JSONArray next = new JSONArray();
                              for (int n = 0; n < folders.length(); n++)
                                if (!path.equals(folders.optString(n)))
                                  next.put(folders.optString(n));
                              prefs().edit().putString("library_folders", next.toString()).apply();
                              render();
                            });
                    }));
      }
    }
    section("文件监视");
    LinearLayout watching = settingsCard();
    boolean watch = prefs().getBoolean("watch_files", true);
    toggleSetting(
        watching,
        "visibility",
        "监视文件更改",
        "自动检测音乐库文件夹中新增、移除或更改的文件。",
        watch,
        true,
        () -> {
          prefs().edit().putBoolean("watch_files", !watch).apply();
          updateFileWatcher();
          render();
        });
  }

  private void chooseLibraryFolder(String path) {
    files.execute(
        () -> {
          try {
            JSONArray entries = LocalMusic.folder(path);
            ArrayList<String> labels = new ArrayList<>(), paths = new ArrayList<>();
            labels.add("添加当前文件夹");
            paths.add(path);
            if (!path.equals("/storage")) {
              labels.add("上一级");
              paths.add(new File(path).getParent());
            }
            for (int i = 0; i < entries.length(); i++) {
              JSONObject entry = entries.optJSONObject(i);
              if (entry.optBoolean("directory")) {
                labels.add(entry.optString("name"));
                paths.add(entry.optString("path"));
              }
            }
            handler.post(
                () -> {
                  if (destroyed) return;
                  choice(
                      "选择文件夹：" + path,
                      labels.toArray(new String[0]),
                      -1,
                      at -> {
                        if (at == 0) {
                          if (path.equals("/storage")) {
                            message("请选择具体的电视或 USB 文件夹");
                            chooseLibraryFolder(path);
                            return;
                          }
                          JSONArray folders =
                              MusicData.array(prefs().getString("library_folders", "[]"));
                          for (int i = 0; i < folders.length(); i++)
                            if (path.equals(folders.optString(i))) {
                              message("文件夹已添加");
                              return;
                            }
                          folders.put(path);
                          prefs().edit().putString("library_folders", folders.toString()).apply();
                          render();
                        } else chooseLibraryFolder(paths.get(at));
                      });
                });
          } catch (Exception e) {
            handler.post(() -> info("无法读取文件夹", e.getMessage()));
          }
        });
  }

  private android.database.ContentObserver fileWatcher;

  private void updateFileWatcher() {
    if (fileWatcher != null) {
      getContentResolver().unregisterContentObserver(fileWatcher);
      fileWatcher = null;
    }
    if (!started || !prefs().getBoolean("watch_files", true)) return;
    fileWatcher =
        new android.database.ContentObserver(handler) {
          @Override
          public void onChange(boolean selfChange) {
            for (Page page : history)
              if (page.type.equals("library") && page.tab == 5) {
                page.loaded = false;
                page.generation++;
                page.loading = false;
              }
            if (current != null && current.type.equals("library") && current.tab == 5) {
              current.loaded = false;
              current.generation++;
              current.loading = false;
              render();
            }
          }
        };
    getContentResolver()
        .registerContentObserver(
            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, fileWatcher);
  }

  private void playbackSettings() {
    section("播放");
    LinearLayout card = settingsCard();
    chipSetting(
        card,
        "animation",
        "播放按钮动画",
        new String[] {"无", "均衡器"},
        prefs().getInt("play_animation", 0),
        n -> {
          prefs().edit().putInt("play_animation", n).apply();
          render();
          updatePlayback();
        },
        "播放音乐时播放按钮的视觉效果");
  }

  private LinearLayout settingsCard() {
    LinearLayout card = column(this);
    card.setBackground(fill(this, SURFACE, 12));
    body.addView(card, lp(-1, -2));
    return card;
  }

  private void settingsDivider(LinearLayout card) {
    View line = new View(this);
    line.setBackgroundColor(BORDER);
    LinearLayout.LayoutParams params = lp(-1, 1);
    params.leftMargin = d(16);
    params.rightMargin = d(16);
    card.addView(line, params);
  }

  private LinearLayout settingsRow(
      LinearLayout card, String symbol, String title, String description) {
    LinearLayout row = row(this);
    row.setPadding(d(16), d(14), d(16), d(14));
    row.setBackground(TvUi.focus(this, true));
    row.addView(TvUi.glyph(this, symbol, 23, TEXT), lp(30, 30));
    LinearLayout text = column(this);
    text.setPadding(d(12), 0, d(12), 0);
    text.addView(label(title, 18, TEXT));
    if (!description.isEmpty()) {
      TextView detail = label(description, 14, MUTED);
      detail.setPadding(0, d(5), 0, 0);
      text.addView(detail);
    }
    row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
    card.addView(row, lp(-1, -2));
    return row;
  }

  private void toggleSetting(
      LinearLayout card,
      String symbol,
      String title,
      String description,
      boolean checked,
      boolean available,
      Runnable action) {
    LinearLayout row = settingsRow(card, symbol, title, description);
    View toggle =
        new View(this) {
          private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

          @Override
          protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), r = h / 2;
            paint.setColor(checked ? ACCENT : SURFACE1);
            paint.setAlpha(available ? 255 : 150);
            canvas.drawRoundRect(0, 0, w, h, r, r, paint);
            paint.setColor(checked ? MANTLE : TEXT);
            paint.setAlpha(available ? 255 : 150);
            canvas.drawCircle(checked ? w - r : r, r, r - h / 8, paint);
          }
        };
    row.addView(toggle, lp(52, 32));
    row.setContentDescription(title + "，" + (available ? (checked ? "开启" : "关闭") : "暂不可用"));
    focus.add(row, FocusRouter.CONTENT, contentRow++, 0, "setting:" + title);
    row.setOnClickListener(v -> action.run());
  }

  private String appVersion() {
    try {
      return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
    } catch (Exception e) {
      return "";
    }
  }

  private void section(String title) {
    TextView heading = label(title, 17, MUTED);
    heading(heading);
    heading.setPadding(d(16), d(24), 0, d(10));
    body.addView(heading);
  }

  private void setting(String title, String value, Runnable action) {
    descriptiveSetting(title, "", value, action);
  }

  private void appearance() {
    section("界面缩放");
    descriptiveSetting(
        "字体和界面大小",
        "同步缩放字体和控件，在系统显示缩放的基础上立即生效。",
        prefs().getInt("ui_scale", 100) + "%",
        () ->
            choice(
                "字体和界面大小",
                new String[] {"80%", "90%", "100%", "110%", "125%", "150%"},
                -1,
                n -> {
                  prefs()
                      .edit()
                      .putInt("ui_scale", new int[] {80, 90, 100, 110, 125, 150}[n])
                      .apply();
                  reloadShell();
                }));
    section("外观");
    LinearLayout card = column(this);
    card.setPadding(d(16), 0, d(16), 0);
    card.setBackground(fill(this, SURFACE, 16));
    body.addView(card, lp(-1, -2));
    ArrayList<String> themeNames = new ArrayList<>();
    int selectedTheme = 0;
    JSONObject theme = TvUi.theme(this);
    for (int i = 0; i < TvUi.themes.length(); i++) {
      JSONObject t = TvUi.themes.optJSONObject(i);
      themeNames.add(t.optString("name"));
      if (t.optString("id").equals(theme.optString("id"))) selectedTheme = i;
    }
    chipSetting(
        card,
        "brush",
        "主题",
        themeNames.toArray(new String[0]),
        selectedTheme,
        n -> {
          JSONObject t = TvUi.themes.optJSONObject(n);
          prefs()
              .edit()
              .putString("theme", t.optString("id"))
              .putString("variant", t.optString("defaultVariant"))
              .putString("accent", t.optString("defaultAccent"))
              .apply();
          reloadShell();
        });
    View line = new View(this);
    line.setBackgroundColor(BORDER);
    card.addView(line, lp(-1, 1));
    JSONArray variants = theme.optJSONArray("variants");
    ArrayList<String> ids = new ArrayList<>(), names = new ArrayList<>();
    int chosen = 0;
    if (variants != null)
      for (int i = 0; i < variants.length(); i++) {
        JSONObject v = variants.optJSONObject(i);
        String id = v.optString("id");
        ids.add(id);
        names.add(id.equals("dark") ? "深色" : id.equals("light") ? "浅色" : v.optString("name"));
      }
    ids.add("system");
    names.add("跟随系统");
    String active = prefs().getString("variant", theme.optString("defaultVariant", "dark"));
    chosen = Math.max(0, ids.indexOf(active));
    chipSetting(
        card,
        "palette",
        "主题变体",
        names.toArray(new String[0]),
        chosen,
        n -> {
          prefs().edit().putString("variant", ids.get(n)).apply();
          reloadShell();
        });
    section("歌词");
    setting(
        "歌词字号",
        prefs().getInt("lyric_size", 36) + " sp",
        () ->
            choice(
                "歌词字号",
                new String[] {"26", "30", "36", "42"},
                -1,
                n -> {
                  prefs().edit().putInt("lyric_size", new int[] {26, 30, 36, 42}[n]).apply();
                  render();
                }));
    setting(
        "显示翻译",
        prefs().getBoolean("lyric_translation", true) ? "开启" : "关闭",
        () -> {
          prefs()
              .edit()
              .putBoolean("lyric_translation", !prefs().getBoolean("lyric_translation", true))
              .apply();
          render();
        });
    setting(
        "歌词时间偏移",
        prefs().getInt("lyric_offset", 0) + " ms",
        () ->
            rangeDialog(
                "歌词时间偏移（毫秒）",
                -5000,
                5000,
                prefs().getInt("lyric_offset", 0),
                100,
                n -> {
                  prefs().edit().putInt("lyric_offset", n).apply();
                  render();
                }));
    setting("遥控器帮助", "查看按键操作", this::remoteHelp);
  }

  private void chipSetting(
      LinearLayout card,
      String symbol,
      String title,
      String[] options,
      int selected,
      Selection action) {
    chipSetting(card, symbol, title, options, selected, action, "");
  }

  private void chipSetting(
      LinearLayout card,
      String symbol,
      String title,
      String[] options,
      int selected,
      Selection action,
      String description) {
    LinearLayout block = row(this);
    block.setGravity(Gravity.TOP);
    block.setPadding(0, d(18), 0, d(18));
    card.addView(block, lp(-1, -2));
    block.addView(TvUi.glyph(this, symbol, 23, TEXT), lp(30, 28));
    LinearLayout text = column(this);
    text.setPadding(d(10), 0, 0, 0);
    block.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
    text.addView(label(title, 18, TEXT), lp(-1, 28));
    if (!description.isEmpty()) {
      TextView detail = label(description, 14, MUTED);
      detail.setPadding(0, 0, 0, d(8));
      text.addView(detail, lp(-1, -2));
    }
    LinearLayout choices = row(this);
    text.addView(choices, lp(-1, 42));
    for (int i = 0; i < options.length; i++) {
      final int at = i;
      Button chip =
          action(options[i], () -> action.choose(at), contentRow, i, "chip:" + title + ":" + i);
      chip.setTextSize(14 * TvUi.SCALE);
      chip.setPadding(d(13), 0, d(13), 0);
      chip.setTextColor(i == selected ? MANTLE : TEXT);
      android.graphics.drawable.StateListDrawable states =
          new android.graphics.drawable.StateListDrawable();
      android.graphics.drawable.GradientDrawable focused =
          fill(this, i == selected ? ACCENT : SURFACE1, 30);
      focused.setStroke(d(1.5f), TEXT);
      states.addState(new int[] {android.R.attr.state_focused}, focused);
      states.addState(new int[] {}, fill(this, i == selected ? ACCENT : SURFACE1, 30));
      chip.setBackground(states);
      choices.addView(chip, lp(-2, 36));
      margin(chip, 8, 0);
    }
    contentRow++;
  }

  private void reloadShell() {
    snapshot();
    TvUi.apply(this);
    focus.clearAll();
    sidebarButtons.clear();
    paintedSong = "";
    buildShell();
    navigate(current, false);
  }

  private String settingIcon(String title) {
    switch (title) {
      case "选择首选语言":
        return "translate";
      case "字体和界面大小":
        return "tune";
      case "系统音量":
        return "volume_up";
      case "均衡器":
        return "graphic_eq";
      case "云端播放音质":
        return "graphic_eq";
      case "播放模式":
        return "repeat";
      case "睡眠定时":
        return "bedtime";
      case "停止播放并退出":
        return "stop";
      case "扫描本地音乐":
        return "library_music";
      case "浏览音乐文件":
        return "folder_open";
      case "清空本机最近播放":
        return "history";
      case "清空本机收藏":
        return "favorite";
      case "当前账号":
        return "person";
      case "扫码登录":
        return "qr_code";
      case "退出账号":
        return "logout";
      case "查看许可":
        return "balance";
      case "歌词字号":
        return "lyrics";
      case "显示翻译":
        return "translate";
      case "歌词时间偏移":
        return "history";
      case "遥控器帮助":
        return "info";
      default:
        throw new IllegalArgumentException("Missing explicit settings icon: " + title);
    }
  }

  private void descriptiveSetting(String title, String description, String value, Runnable action) {
    if (!current.query.isEmpty()
        && !(title + description + value)
            .toLowerCase(Locale.ROOT)
            .contains(current.query.toLowerCase(Locale.ROOT))) return;
    LinearLayout row = row(this);
    row.setPadding(d(16), d(12), d(16), d(12));
    row.setBackground(TvUi.focus(this, false));
    row.addView(TvUi.glyph(this, settingIcon(title), 23, TEXT), lp(30, 30));
    LinearLayout descriptionBox = column(this);
    descriptionBox.setPadding(d(12), 0, d(14), 0);
    row.addView(descriptionBox, new LinearLayout.LayoutParams(0, -2, 1));
    descriptionBox.addView(label(title, 18, TEXT));
    if (!description.isEmpty()) {
      TextView detail = label(description, 14, MUTED);
      detail.setPadding(0, d(5), 0, 0);
      descriptionBox.addView(detail);
    }
    LinearLayout valueBox = row(this);
    valueBox.setPadding(d(14), 0, d(10), 0);
    valueBox.setBackground(fill(this, SURFACE1, 9));
    TextView selected = label(value, 16, TEXT);
    selected.setSingleLine();
    valueBox.setMinimumWidth(d(160));
    valueBox.addView(selected, new LinearLayout.LayoutParams(-2, -2));
    valueBox.addView(TvUi.glyph(this, "arrow_drop_down", 23, TEXT), lp(24, 24));
    row.addView(valueBox, lp(-2, 36));
    body.addView(row, lp(-1, description.isEmpty() ? 68 : 74));
    margin(row, 0, 4);
    focus.add(row, FocusRouter.CONTENT, contentRow++, 0, "setting:" + title);
    row.setOnClickListener(v -> action.run());
    row.setContentDescription(title + "，" + value);
  }

  private void lyricSettings() {
    choice(
        "歌词设置",
        new String[] {"调整字号", "显示 / 隐藏翻译", "调整时间偏移"},
        -1,
        at -> {
          if (at == 0)
            choice(
                "歌词字号",
                new String[] {"26", "30", "36", "42"},
                -1,
                n -> {
                  prefs().edit().putInt("lyric_size", new int[] {26, 30, 36, 42}[n]).apply();
                  render();
                });
          else if (at == 1) {
            prefs()
                .edit()
                .putBoolean("lyric_translation", !prefs().getBoolean("lyric_translation", true))
                .apply();
            lastLyric = -2;
            updatePlayback();
          } else
            rangeDialog(
                "歌词时间偏移（毫秒）",
                -5000,
                5000,
                prefs().getInt("lyric_offset", 0),
                100,
                value -> {
                  prefs().edit().putInt("lyric_offset", value).apply();
                  lastLyric = -2;
                });
        });
  }

  private void equalizerDialog() {
    PlaybackService player = service();
    int count = player == null ? 0 : player.equalizerBands();
    if (count == 0) {
      info("均衡器暂不可用", "请先播放一首音乐再试。部分电视音频输出不提供均衡器控制。");
      return;
    }
    ArrayList<String> entries = new ArrayList<>();
    ArrayList<String> bands = new ArrayList<>();
    entries.add(prefs().getBoolean("eq_enabled", false) ? "关闭均衡器" : "开启均衡器");
    for (int i = 0; i < count; i++) {
      int hz = player.bandHz(i);
      String name = hz >= 1000 ? String.format(Locale.ROOT, "%.1f kHz", hz / 1000.0) : hz + " Hz";
      bands.add(name);
      entries.add(name + "   " + prefs().getInt("eq_band_" + i, 0) + " dB");
    }
    entries.add("恢复平直");
    choice(
        "均衡器",
        entries.toArray(new String[0]),
        -1,
        at -> {
          if (at == 0) {
            prefs()
                .edit()
                .putBoolean("eq_enabled", !prefs().getBoolean("eq_enabled", false))
                .apply();
            player.applyEqualizer();
            render();
          } else if (at == count + 1) {
            android.content.SharedPreferences.Editor edit = prefs().edit();
            for (int i = 0; i < count; i++) edit.putInt("eq_band_" + i, 0);
            edit.apply();
            player.applyEqualizer();
          } else {
            int band = at - 1;
            rangeDialog(
                bands.get(band) + "（dB）",
                -12,
                12,
                prefs().getInt("eq_band_" + band, 0),
                1,
                value -> {
                  prefs()
                      .edit()
                      .putInt("eq_band_" + band, value)
                      .putBoolean("eq_enabled", true)
                      .apply();
                  player.applyEqualizer();
                });
          }
        });
  }

  private void login() {
    final int token = qrVersion;
    Page target = current;
    Button refresh = action("刷新二维码", () -> render(), 0, 0, "login:refresh");
    tabs.addView(refresh, lp(136, 42));
    margin(refresh, 8, 0);
    Button help =
        action(
            "登录帮助",
            () -> info("扫码登录", "打开手机网易云音乐的扫一扫，扫描电视二维码，再在手机上确认。\n二维码过期时，选择「刷新二维码」。\n按返回键可取消本次登录。"),
            0,
            1,
            "login:help");
    tabs.addView(help, lp(114, 42));
    LinearLayout panel = row(this);
    panel.setGravity(Gravity.CENTER);
    panel.setPadding(d(26), d(20), d(26), d(20));
    stage.addView(panel, new FrameLayout.LayoutParams(-1, -1));
    ImageView image = cover(this, 232);
    panel.addView(image);
    image.setBackgroundColor(Color.WHITE);
    image.setImageDrawable(null);
    LinearLayout instructions = column(this);
    instructions.setPadding(d(34), 0, 0, 0);
    panel.addView(instructions, lp(390, -2));
    TextView title = label(accountId == 0 ? "登录网易云音乐" : "切换网易云账号", 28, TEXT);
    heading(title);
    instructions.addView(title);
    TextView how = label("1  打开手机网易云音乐的扫一扫\n\n2  扫描左侧二维码\n\n3  在手机上确认登录", 19, MUTED);
    how.setPadding(0, d(24), 0, d(20));
    instructions.addView(how);
    TextView state = label("正在获取二维码…", 17, ACCENT);
    instructions.addView(state);
    request(
        "qr",
        Backend.json(),
        (data, error) -> {
          if (token != qrVersion || current != target) return;
          if (error != null) {
            TvUi.setText(state, error);
            message("获取二维码失败，选择刷新重试");
            return;
          }
          JSONObject qr = (JSONObject) data;
          JSONArray rows = qr.optJSONArray("rows");
          if (rows == null) return;
          int n = rows.length(), scale = 8, size = (n + 8) * scale;
          Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
          bitmap.eraseColor(Color.WHITE);
          for (int y = 0; y < n; y++) {
            String line = rows.optString(y);
            for (int x = 0; x < n; x++)
              if (line.charAt(x) == '1')
                for (int yy = 0; yy < scale; yy++)
                  for (int xx = 0; xx < scale; xx++)
                    bitmap.setPixel((x + 4) * scale + xx, (y + 4) * scale + yy, Color.BLACK);
          }
          image.setImageBitmap(bitmap);
          TvUi.setText(state, "等待扫码…");
          pollLogin(qr.optString("key"), token, target, state, 0);
        });
  }

  private void pollLogin(String key, int token, Page target, TextView state, int failures) {
    handler.postDelayed(
        () -> {
          if (destroyed || token != qrVersion || current != target) return;
          request(
              "qr_check",
              Backend.json("key", key),
              (data, error) -> {
                if (token != qrVersion || current != target) return;
                if (error != null) {
                  TvUi.setText(state, error);
                  if (failures < 2) pollLogin(key, token, target, state, failures + 1);
                  else message("连接中断，选择刷新二维码重试");
                  return;
                }
                String result = ((JSONObject) data).optString("status");
                if (result.equals("expired")) {
                  TvUi.setText(state, "二维码已过期，请选择上方刷新");
                  return;
                }
                if (result.equals("authorized")) {
                  backend.saveSession();
                  TvUi.setText(state, "登录成功，正在读取账号…");
                  request(
                      "account",
                      Backend.json(),
                      (account, failure) -> {
                        if (failure != null) {
                          message(failure);
                          return;
                        }
                        JSONObject a = (JSONObject) account;
                        accountId = a.optLong("userId");
                        nickname = a.optString("nickname");
                        TvUi.setText(accountLabel, nickname);
                        backend.saveSession();
                        if (current == target) {
                          navigate(new Page("library", "我的音乐", "library"), true);
                          message("欢迎回来，" + nickname);
                        }
                      });
                  return;
                }
                TvUi.setText(state, result.equals("confirming") ? "已扫描，请在手机上确认" : "等待扫码…");
                pollLogin(key, token, target, state, 0);
              });
        },
        2200);
  }

  private interface Selection {
    void choose(int value);
  }

  private interface Input {
    void accept(String value);
  }

  private void showModal(AlertDialog dialog) {
    modalOrigin = getCurrentFocus();
    View origin = modalOrigin;
    Page source = current;
    modal = dialog;
    dialog.setOnDismissListener(
        d -> {
          if (modal == dialog) modal = null;
          if (!destroyed && current == source && origin != null && origin.isAttachedToWindow())
            origin.requestFocus();
        });
    dialog.setOnShowListener(
        d -> {
          for (int id :
              new int[] {
                AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL
              }) {
            Button b = dialog.getButton(id);
            if (b != null) {
              b.setTextSize(19);
              b.setTextColor(TEXT);
              b.setBackground(TvUi.focus(this, true));
              b.setMinHeight(d(52));
              b.setFocusableInTouchMode(true);
              b.setPadding(d(20), d(10), d(20), d(10));
            }
          }
          if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(fill(this, MANTLE, 18));
            dialog.getWindow().setLayout(d(640), -2);
          }
          ListView list = dialog.getListView();
          if (list != null) {
            list.setSelector(TvUi.focus(this, true));
            list.setDividerHeight(d(2));
            list.setPadding(d(12), d(6), d(12), d(6));
            list.requestFocus();
          } else {
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive != null) positive.requestFocus();
          }
        });
    dialog.show();
  }

  private void choice(String title, String[] options, int selected, Selection callback) {
    AlertDialog.Builder b =
        new AlertDialog.Builder(this).setTitle(title).setNegativeButton("返回", null);
    if (selected >= 0)
      b.setSingleChoiceItems(
          options,
          selected,
          (dialog, at) -> {
            dialog.dismiss();
            handler.post(() -> callback.choose(at));
          });
    else
      b.setItems(
          options,
          (dialog, at) -> {
            dialog.dismiss();
            handler.post(() -> callback.choose(at));
          });
    showModal(b.create());
  }

  private void confirm(String title, String message, Runnable action) {
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定", (d, w) -> handler.post(action))
            .create();
    showModal(dialog);
    // Destructive confirmations default to Cancel so an extra OK cannot confirm accidentally.
    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
  }

  private void info(String title, String message) {
    showModal(
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .create());
  }

  private void inputDialog(String title, String hint, String value, Input callback) {
    LinearLayout box = column(this);
    box.setPadding(d(24), d(16), d(24), d(12));
    TvInput input = new TvInput(this);
    input.setImeOptions(
        android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
    input.setTextSize(22);
    input.setTextColor(TEXT);
    input.setHintTextColor(MUTED);
    input.setSingleLine(true);
    input.setHint(hint);
    input.setText(value);
    input.setSelectAllOnFocus(true);
    input.setBackground(TvUi.focus(this, false));
    input.setPadding(d(16), d(12), d(16), d(12));
    box.addView(input, lp(-1, 60));
    TextView help = label("确定打开键盘 · 返回收起键盘后确定搜索 · 菜单键也可提交", 14, MUTED);
    help.setPadding(0, d(14), 0, 0);
    box.addView(help);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("搜索", null)
            .create();
    showModal(dialog);
    Button search = dialog.getButton(AlertDialog.BUTTON_POSITIVE),
        cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
    input.setNextFocusDownId(search.getId());
    search.setNextFocusUpId(input.getId());
    cancel.setNextFocusUpId(input.getId());
    search.setNextFocusLeftId(cancel.getId());
    cancel.setNextFocusRightId(search.getId());
    input.setId(View.generateViewId());
    search.setNextFocusUpId(input.getId());
    cancel.setNextFocusUpId(input.getId());
    Runnable submit =
        () -> {
          String query = input.getText().toString().trim();
          if (query.isEmpty() && !title.startsWith("筛选") && !title.equals("搜索设置")) {
            input.setError("请输入关键词");
            input.requestFocus();
            return;
          }
          ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
              .hideSoftInputFromWindow(input.getWindowToken(), 0);
          dialog.dismiss();
          handler.post(() -> callback.accept(query));
        };
    input.submit = submit;
    input.closed = () -> search.requestFocus();
    search.setOnClickListener(v -> submit.run());
    input.setOnEditorActionListener(
        (v, action, event) -> {
          if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
              || action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
              || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
            submit.run();
            return true;
          }
          return false;
        });
    input.setOnKeyListener(
        (v, key, event) -> {
          if ((key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER)
              && event.getAction() == KeyEvent.ACTION_UP) {
            input.openKeyboard();
            return true;
          }
          return false;
        });
    input.requestFocus();
  }

  private void rangeDialog(
      String title, int minimum, int maximum, int value, int step, Selection callback) {
    LinearLayout box = column(this);
    box.setPadding(d(26), d(16), d(26), d(16));
    TextView amount = label(Integer.toString(value), 25, ACCENT);
    amount.setGravity(Gravity.CENTER);
    box.addView(amount);
    SeekBar range = new SeekBar(this);
    range.setMax((maximum - minimum) / step);
    range.setProgress((value - minimum) / step);
    range.setKeyProgressIncrement(1);
    range.setPadding(d(16), d(12), d(16), d(12));
    range.setBackground(TvUi.focus(this, true));
    range.setThumbTintList(ColorStateList.valueOf(ACCENT));
    range.setProgressTintList(ColorStateList.valueOf(ACCENT));
    range.setContentDescription(title + "，左右键调整");
    box.addView(range, lp(-1, 64));
    TextView hint = label("左右键调整 · 下键选择确定 · 返回取消", 16, MUTED);
    hint.setGravity(Gravity.CENTER);
    box.addView(hint);
    range.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onStartTrackingTouch(SeekBar s) {}

          public void onStopTrackingTouch(SeekBar s) {}

          public void onProgressChanged(SeekBar s, int p, boolean user) {
            TvUi.setText(amount, Integer.toString(minimum + p * step));
          }
        });
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton(
                "确定", (d, w) -> callback.choose(minimum + range.getProgress() * step))
            .create();
    showModal(dialog);
    range.setFocusableInTouchMode(true);
    range.setId(View.generateViewId());
    Button yes = dialog.getButton(AlertDialog.BUTTON_POSITIVE),
        no = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
    range.setNextFocusDownId(yes.getId());
    yes.setNextFocusUpId(range.getId());
    no.setNextFocusUpId(range.getId());
    range.requestFocus();
  }

  private void volumeDialog() {
    android.media.AudioManager audio = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
    rangeDialog(
        "电视媒体音量",
        0,
        audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC),
        audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC),
        1,
        value -> audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, value, 0));
  }

  private void remoteHelp() {
    info(
        "遥控器操作",
        "上下左右：在侧栏、卡片、歌曲和播放栏之间移动\n"
            + "确定：打开或播放\n"
            + "长按确定 / 菜单：歌曲操作与队列管理\n"
            + "返回：关闭弹窗、返回上级并恢复焦点\n"
            + "进度条：左右快退 / 快进 10 秒，长按加速\n"
            + "歌词区：上下选择歌词，确定定位到所选句\n"
            + "播放键：播放 / 暂停；切歌键：上一首 / 下一首\n"
            + "上下键可离开进度条和歌词区，避免焦点被困住。");
  }

  private final Runnable attachService =
      new Runnable() {
        public void run() {
          if (destroyed || !started) return;
          if (service() != null) {
            if (observed != service()) {
              if (observed != null) observed.unobserve(playbackChanged);
              observed = service();
              observed.observe(playbackChanged);
              paintedSong = "";
              updatePlayback();
            }
          } else handler.postDelayed(this, 150);
        }
      };

  protected void onStart() {
    super.onStart();
    started = true;
    updateFileWatcher();
    handler.post(attachService);
  }

  protected void onStop() {
    started = false;
    updateFileWatcher();
    if (observed != null) {
      observed.unobserve(playbackChanged);
      observed = null;
    }
    handler.removeCallbacks(attachService);
    super.onStop();
  }

  private void updatePlayback() {
    if (destroyed || nowTitle == null) return;
    PlaybackService s = service();
    if (s == null) return;
    TvUi.setText(nowTitle, s.title());
    TvUi.setText(nowArtist, s.hasSong() ? MusicData.artist(s.song()) : "Netodia TV");
    TvUi.setText(playButton, s.playing() ? "pause" : "play_arrow");
    boolean animate = s.playing() && prefs().getInt("play_animation", 0) == 1;
    if (animate && !(playButton.getForeground() instanceof PlaybackBarsDrawable))
      playButton.setForeground(new PlaybackBarsDrawable(MANTLE));
    if (!animate && playButton.getForeground() != null) playButton.setForeground(null);
    if (animate) TvUi.setText(playButton, "");
    playButton.setContentDescription(s.loading ? "加载中" : s.playing() ? "暂停" : "播放");
    TvUi.setText(modeButton, s.mode() == 1 ? "repeat_one" : "repeat");
    shuffleButton.setTextColor(s.mode() == 2 ? ACCENT : TEXT);
    boolean favorite = s.hasSong() && MusicData.favorite(this, s.song());
    favoriteButton.setTypeface(favorite ? TvUi.filled : TvUi.symbols);
    if (playerFavoriteButton != null) {
      playerFavoriteButton.setTypeface(favorite ? TvUi.filled : TvUi.symbols);
      playerFavoriteButton.setTextColor(TEXT);
    }
    favoriteButton.setTextColor(favorite ? ACCENT : TEXT);
    TvUi.setText(favoriteButton, "favorite");
    TvUi.setText(clock, MusicData.time(s.position()));
    TvUi.setText(durationLabel, MusicData.time(s.duration()));
    TvUi.setText(nowAlbum, MusicData.album(s.song()).optString("name"));
    if (!progress.isPressed())
      progress.setProgress(s.duration() > 0 ? (int) (s.position() * 1000L / s.duration()) : 0);
    if (!s.error.isEmpty()) message(s.error);
    String song = MusicData.identity(s.song());
    if (!song.equals(paintedSong)) {
      paintedSong = song;
      Artwork.load(nowCover, MusicData.cover(s.song()));
      if (current != null && current.type.equals("player")) {
        snapshot();
        render();
        return;
      }
    }
    if (current != null && current.type.equals("player") && lyricLines != null) refreshLyrics(s);
  }

  public boolean dispatchKeyEvent(KeyEvent event) {
    if (modal != null && modal.isShowing()) return super.dispatchKeyEvent(event);
    if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);
    int key = event.getKeyCode();
    PlaybackService s = service();
    if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
      if (s != null) s.toggle();
      return true;
    }
    if (key == KeyEvent.KEYCODE_MEDIA_PLAY) {
      if (s != null) s.resume();
      return true;
    }
    if (key == KeyEvent.KEYCODE_MEDIA_PAUSE) {
      if (s != null) s.pause();
      return true;
    }
    if (key == KeyEvent.KEYCODE_MEDIA_NEXT) {
      if (s != null) s.next(false);
      return true;
    }
    if (key == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
      if (s != null) s.previous();
      return true;
    }
    if (key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || key == KeyEvent.KEYCODE_MEDIA_REWIND) {
      if (s != null) s.seek(s.position() + (key == KeyEvent.KEYCODE_MEDIA_REWIND ? -10000 : 10000));
      return true;
    }
    View focused = getCurrentFocus();
    String focusKey = focus.key(focused);
    if (key == KeyEvent.KEYCODE_MENU) {
      if (focused != null && focused.isLongClickable()) focused.performLongClick();
      else if (s != null && s.hasSong()) songMenu(s.song(), s.index());
      else remoteHelp();
      return true;
    }
    if (focused == progress && key == KeyEvent.KEYCODE_DPAD_DOWN) {
      focus.focus("player:循环模式");
      return true;
    }
    if (focused == progress && key == KeyEvent.KEYCODE_DPAD_UP) {
      focus.focus("player:播放 / 暂停");
      return true;
    }
    if (focused == progress
        && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
      if (s != null)
        s.seek(
            s.position()
                + (key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1)
                    * (event.getRepeatCount() > 5 ? 30000 : 10000));
      return true;
    }
    if (focusKey.equals("lyrics:panel")
        && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
      if (s != null && !s.lyrics.times.isEmpty()) {
        int active = s.lyrics.at(s.position() + prefs().getInt("lyric_offset", 0));
        int target =
            Math.max(
                0,
                Math.min(
                    s.lyrics.times.size() - 1,
                    active + (key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1)));
        s.seek((int) Math.max(0, s.lyrics.times.get(target) - prefs().getInt("lyric_offset", 0)));
      }
      return true;
    }
    Integer itemIndex = itemPositions.get(focusKey);
    if (key == KeyEvent.KEYCODE_DPAD_DOWN
        && itemIndex != null
        && itemIndex >= displayedItems - Math.max(2, gridColumns)) {
      int oldDisplayed = displayedItems;
      int lastLoadedRow =
          focusKey.startsWith("entity:")
              ? Math.max(0, (oldDisplayed - 1) / Math.max(1, gridColumns) * gridColumns)
              : oldDisplayed - 1;
      if (appendMore()) {
        if (current.loading && itemIndex >= lastLoadedRow) return true;
        return focus.move(getCurrentFocus(), View.FOCUS_DOWN);
      }
      if (current.loading && itemIndex >= lastLoadedRow) return true;
    }
    int direction =
        key == KeyEvent.KEYCODE_DPAD_UP
            ? View.FOCUS_UP
            : key == KeyEvent.KEYCODE_DPAD_DOWN
                ? View.FOCUS_DOWN
                : key == KeyEvent.KEYCODE_DPAD_LEFT
                    ? View.FOCUS_LEFT
                    : key == KeyEvent.KEYCODE_DPAD_RIGHT ? View.FOCUS_RIGHT : 0;
    if (direction != 0) return focus.move(focused, direction);
    return super.dispatchKeyEvent(event);
  }

  private void back() {
    if (modal != null && modal.isShowing()) {
      modal.dismiss();
      return;
    }
    if (!history.isEmpty()) {
      Page previous = history.pop();
      navigate(previous, false);
      return;
    }
    if (!current.type.equals("library")) {
      navigate(new Page("library", "我的音乐库", "library"), true);
      focus.focus("nav:library");
      return;
    }
    choice(
        "离开 Netodia？",
        new String[] {"继续聆听", "返回电视桌面，保持后台播放", "停止播放并退出"},
        -1,
        at -> {
          if (at == 1) finish();
          else if (at == 2) {
            stopService(new Intent(this, PlaybackService.class));
            finish();
          }
        });
  }

  public void onBackPressed() {
    back();
  }

  protected void onDestroy() {
    if (playerMenu != null && playerMenu.isShowing()) playerMenu.dismiss();
    if (queueSheet != null && queueSheet.isShowing()) queueSheet.dismiss();
    if (modal != null && modal.isShowing()) modal.dismiss();
    destroyed = true;
    qrVersion++;
    handler.removeCallbacksAndMessages(null);
    files.shutdown();
    if (observed != null) observed.unobserve(playbackChanged);
    super.onDestroy();
  }
}
