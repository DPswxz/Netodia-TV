package com.netodia.tv;

import android.app.*;
import android.content.*;
import android.media.*;
import android.media.audiofx.Equalizer;
import android.media.session.*;
import android.net.Uri;
import android.os.*;
import java.util.ArrayList;
import java.util.Random;
import org.json.*;

/** The Android replacement for desktop audio output, tray, media keys and queue lifetime. */
public class PlaybackService extends Service {
  public static PlaybackService instance;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ArrayList<Runnable> listeners = new ArrayList<>();
  private final Random random = new Random();
  private MediaPlayer player;
  private MediaSession session;
  private AudioManager audio;
  private AudioFocusRequest focus;
  private Equalizer equalizer;
  private Backend backend;
  private SharedPreferences prefs;
  private JSONArray queue = new JSONArray();
  private int index = -1, generation, resumePosition;
  private boolean readyPlayer, resumeOnGain, playWhenReady = true;
  public boolean loading, trial;
  public String error = "";
  public MusicData.Lyrics lyrics = new MusicData.Lyrics("", "");
  public long sleepDeadline;
  public boolean stopAtEnd;
  private int ticks;
  public int queueRevision;
  private final java.util.concurrent.ExecutorService localIo =
      java.util.concurrent.Executors.newSingleThreadExecutor();

  public void onCreate() {
    super.onCreate();
    instance = this;
    prefs = getSharedPreferences("music", 0);
    backend = Backend.get(this);
    queue = MusicData.array(prefs.getString("queue", "[]"));
    index =
        queue.length() == 0
            ? -1
            : Math.max(0, Math.min(queue.length() - 1, prefs.getInt("queue_index", 0)));
    resumePosition = prefs.getInt("position", 0);
    audio = (AudioManager) getSystemService(AUDIO_SERVICE);
    AudioAttributes attributes =
        new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();
    focus =
        new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(
                change -> {
                  if (change == AudioManager.AUDIOFOCUS_GAIN) {
                    if (readyPlayer) player.setVolume(1, 1);
                    if (resumeOnGain) {
                      resumeOnGain = false;
                      resume();
                    }
                  } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    if (readyPlayer) player.setVolume(.2f, .2f);
                  } else {
                    boolean recover =
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && (playing() || loading);
                    pause();
                    resumeOnGain = recover;
                    if (change == AudioManager.AUDIOFOCUS_LOSS)
                      audio.abandonAudioFocusRequest(focus);
                  }
                })
            .build();
    session = new MediaSession(this, "Netodia TV");
    session.setCallback(
        new MediaSession.Callback() {
          public void onPlay() {
            resume();
          }

          public void onPause() {
            pause();
          }

          public void onSkipToNext() {
            next(false);
          }

          public void onSkipToPrevious() {
            previous();
          }

          public void onSeekTo(long ms) {
            seek((int) ms);
          }

          public void onStop() {
            pause();
          }
        });
    session.setFlags(
        MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
    session.setSessionActivity(
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    session.setActive(true);
    ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
        .createNotificationChannel(
            new NotificationChannel("playback", "音乐播放", NotificationManager.IMPORTANCE_LOW));
    publish(true);
    main.post(tick);
  }

  public void observe(Runnable listener) {
    if (!listeners.contains(listener)) listeners.add(listener);
  }

  public void unobserve(Runnable listener) {
    listeners.remove(listener);
  }

  private void changed() {
    for (Runnable listener : new ArrayList<>(listeners)) listener.run();
  }

  public JSONArray queue() {
    return queue;
  }

  public int index() {
    return index;
  }

  public JSONObject song() {
    JSONObject s = queue.optJSONObject(index);
    return s == null ? new JSONObject() : s;
  }

  public boolean hasSong() {
    return index >= 0 && index < queue.length();
  }

  public String title() {
    return hasSong() ? song().optString("name", "未知歌曲") : "选择音乐，开始聆听";
  }

  public int mode() {
    return prefs.getInt("mode", 0);
  }

  public String modeLabel() {
    return new String[] {"列表循环", "单曲循环", "随机播放"}[mode()];
  }

  public void setMode(int mode) {
    prefs.edit().putInt("mode", Math.max(0, Math.min(mode, 2))).apply();
    changed();
  }

  public void cycleMode() {
    prefs.edit().putInt("mode", (mode() + 1) % 3).apply();
    changed();
  }

  private String roamingMode = "";
  private int roamingGeneration;

  public void startRoaming(String mode) {
    if (!mode.equals("fm") && !mode.equals("heart") && !mode.equals("similar")) return;
    final int token = ++roamingGeneration;
    long seed = hasSong() ? song().optLong("id") : 0;
    loading = true;
    error = "";
    changed();
    backend.request(
        mode,
        Backend.json("id", seed),
        (data, failure) -> {
          if (token != roamingGeneration || instance != this) return;
          loading = false;
          if (failure != null || !(data instanceof JSONArray) || ((JSONArray) data).length() == 0) {
            error = failure != null ? failure : "暂无可播放的漫游歌曲，请先收藏音乐或稍后重试";
            changed();
            return;
          }
          setMode(0);
          setQueue((JSONArray) data, 0);
          roamingMode = mode;
        });
  }

  public void setQueue(JSONArray songs, int start) {
    roamingMode = "";
    roamingGeneration++;
    queueRevision++;
    if (songs.length() == 0) return;
    queue = MusicData.array(songs.toString());
    index = Math.max(0, Math.min(start, queue.length() - 1));
    resumePosition = 0;
    save();
    playIndex(index);
  }

  public void enqueue(JSONObject song, boolean next) {
    queueRevision++;
    JSONArray result = new JSONArray();
    int insert = next && index >= 0 ? index + 1 : queue.length();
    for (int i = 0; i <= queue.length(); i++) {
      if (i == insert) result.put(song);
      if (i < queue.length()) result.put(queue.optJSONObject(i));
    }
    queue = result;
    if (index < 0) index = 0;
    save();
    changed();
  }

  public void remove(int at) {
    queueRevision++;
    if (at < 0 || at >= queue.length()) return;
    boolean current = at == index;
    JSONArray result = new JSONArray();
    for (int i = 0; i < queue.length(); i++) if (i != at) result.put(queue.optJSONObject(i));
    queue = result;
    if (queue.length() == 0) {
      clear();
      return;
    }
    if (at < index) index--;
    else if (index >= queue.length()) index = queue.length() - 1;
    if (current) {
      resumePosition = 0;
      playIndex(index);
    } else {
      save();
      changed();
    }
  }

  public void move(int from, int to) {
    queueRevision++;
    if (from < 0 || to < 0 || from >= queue.length() || to >= queue.length() || from == to) return;
    ArrayList<JSONObject> list = new ArrayList<>();
    for (int i = 0; i < queue.length(); i++) list.add(queue.optJSONObject(i));
    int currentIndex = index;
    JSONObject moving = list.remove(from);
    list.add(to, moving);
    queue = new JSONArray();
    for (int i = 0; i < list.size(); i++) {
      queue.put(list.get(i));
    }
    if (currentIndex == from) index = to;
    else if (from < currentIndex && to >= currentIndex) index--;
    else if (from > currentIndex && to <= currentIndex) index++;
    save();
    changed();
  }

  public void clear() {
    queueRevision++;
    generation++;
    releasePlayer();
    queue = new JSONArray();
    index = -1;
    resumePosition = 0;
    loading = false;
    lyrics = new MusicData.Lyrics("", "");
    save();
    publish(true);
    changed();
  }

  public void playIndex(int at) {
    if (at < 0 || at >= queue.length()) return;
    index = at;
    int token = ++generation;
    releasePlayer();
    loading = true;
    playWhenReady = true;
    trial = false;
    error = "";
    lyrics = new MusicData.Lyrics("", "");
    save();
    publish(true);
    changed();
    JSONObject selected = song();
    String uri = selected.optString("uri", "");
    if (!uri.isEmpty()) {
      prepare(uri, token);
      localIo.execute(
          () -> {
            String text = LocalMusic.sidecar(selected);
            main.post(
                () -> {
                  if (token == generation) {
                    lyrics = new MusicData.Lyrics(text, "");
                    changed();
                  }
                });
          });
    } else {
      backend.request(
          "stream",
          Backend.json(
              "id", selected.optLong("id"), "quality", prefs.getString("quality", "exhigh")),
          (data, failure) -> {
            if (token != generation) return;
            if (failure != null) {
              fail(failure);
              return;
            }
            JSONObject ticket = (JSONObject) data;
            trial = ticket.optBoolean("trial");
            prepare(ticket.optString("url"), token);
          });
      backend.request(
          "lyrics",
          Backend.json("id", selected.optLong("id")),
          (data, failure) -> {
            if (token == generation && failure == null) {
              JSONObject l = (JSONObject) data;
              lyrics =
                  new MusicData.Lyrics(
                      l.optString("original"),
                      l.optString("translation"),
                      l.optString("romanization"));
              changed();
            }
          });
    }
  }

  private void prepare(String uri, int token) {
    try {
      player = new MediaPlayer();
      player.setAudioAttributes(
          new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
              .build());
      player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
      player.setOnPreparedListener(
          p -> {
            if (token != generation) return;
            loading = false;
            readyPlayer = true;
            applyEqualizer();
            if (resumePosition > 0) {
              p.seekTo(resumePosition);
              resumePosition = 0;
            }
            if (playWhenReady) {
              if (audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                p.start();
                applySpeed();
              } else error = "其他应用正在使用音频，请按播放重试";
            }
            if (playing()) MusicData.rememberPlay(this, song());
            publish(true);
            changed();
          });
      player.setOnCompletionListener(
          p -> {
            if (token == generation) {
              if (stopAtEnd) {
                stopAtEnd = false;
                pause();
                return;
              }
              if (mode() == 1) {
                resumePosition = 0;
                playIndex(index);
              } else next(true);
            }
          });
      player.setOnErrorListener(
          (p, what, extra) -> {
            if (token == generation) fail("播放失败，请重试或切换下一首（" + what + " / " + extra + "）");
            return true;
          });
      if (uri.startsWith("content:") || uri.startsWith("file:"))
        player.setDataSource(this, Uri.parse(uri));
      else player.setDataSource(uri);
      player.prepareAsync();
    } catch (Exception e) {
      fail("无法打开这首歌曲，请检查文件或网络");
    }
  }

  private void fail(String message) {
    loading = false;
    readyPlayer = false;
    error = message;
    releasePlayer();
    publish(true);
    changed();
  }

  public void next(boolean automatic) {
    if (queue.length() == 0) return;
    if (!roamingMode.isEmpty() && index == queue.length() - 1) {
      startRoaming(roamingMode);
      return;
    }
    int next =
        mode() == 2 && queue.length() > 1
            ? (index + 1 + random.nextInt(queue.length() - 1)) % queue.length()
            : (index + 1) % queue.length();
    resumePosition = 0;
    playIndex(next);
  }

  public void previous() {
    if (position() > 3000) {
      seek(0);
      return;
    }
    if (queue.length() > 0) {
      resumePosition = 0;
      playIndex((index - 1 + queue.length()) % queue.length());
    }
  }

  public boolean playing() {
    try {
      return readyPlayer && player != null && player.isPlaying();
    } catch (IllegalStateException e) {
      return false;
    }
  }

  public int position() {
    try {
      return readyPlayer ? player.getCurrentPosition() : resumePosition;
    } catch (IllegalStateException e) {
      return resumePosition;
    }
  }

  public int duration() {
    try {
      return readyPlayer ? Math.max(0, player.getDuration()) : (int) song().optLong("dt", 0);
    } catch (IllegalStateException e) {
      return 0;
    }
  }

  public void pause() {
    playWhenReady = false;
    resumeOnGain = false;
    if (playing()) player.pause();
    save();
    publish(true);
    changed();
  }

  public void resume() {
    if (!hasSong()) return;
    playWhenReady = true;
    if (loading) {
      changed();
      return;
    }
    if (!readyPlayer) {
      playIndex(index);
      return;
    }
    try {
      if (audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
        player.start();
        applySpeed();
      }
    } catch (IllegalStateException e) {
      error = "播放状态异常，请重新选择歌曲";
    }
    publish(true);
    changed();
  }

  public void toggle() {
    if (playing() || (loading && playWhenReady)) pause();
    else resume();
  }

  public void seek(int position) {
    int target = Math.max(0, Math.min(position, Math.max(0, duration() - 1)));
    if (readyPlayer) {
      try {
        player.seekTo(target);
      } catch (IllegalStateException ignored) {
      }
    } else resumePosition = target;
    publish(false);
    changed();
  }

  public void sleep(int minutes) {
    stopAtEnd = false;
    sleepDeadline = minutes <= 0 ? 0 : SystemClock.elapsedRealtime() + minutes * 60000L;
    changed();
  }

  public float speed() {
    return prefs.getFloat("speed", 1f);
  }

  public void speed(float value) {
    float old = speed();
    prefs.edit().putFloat("speed", value).apply();
    if (!applySpeed()) prefs.edit().putFloat("speed", old).apply();
    publish(true);
    changed();
  }

  private boolean applySpeed() {
    if (!readyPlayer) return true;
    boolean playing = playing();
    try {
      player.setPlaybackParams(new PlaybackParams().setPitch(1f).setSpeed(speed()));
      if (!playing) player.pause();
      return true;
    } catch (RuntimeException e) {
      error = "当前音频输出不支持此播放速度";
      return false;
    }
  }

  public void sleepAtEnd() {
    sleepDeadline = 0;
    stopAtEnd = true;
    changed();
  }

  public void applyEqualizer() {
    if (!readyPlayer) return;
    try {
      if (equalizer == null) equalizer = new Equalizer(0, player.getAudioSessionId());
      short[] range = equalizer.getBandLevelRange();
      for (short band = 0; band < equalizer.getNumberOfBands(); band++) {
        int db = prefs.getInt("eq_band_" + band, 0) * 100;
        equalizer.setBandLevel(band, (short) Math.max(range[0], Math.min(range[1], db)));
      }
      equalizer.setEnabled(prefs.getBoolean("eq_enabled", false));
    } catch (RuntimeException e) {
      if (prefs.getBoolean("eq_enabled", false)) error = "此电视的音频输出不支持均衡器";
    }
  }

  public int equalizerBands() {
    return equalizer == null ? 0 : equalizer.getNumberOfBands();
  }

  public int bandHz(int band) {
    return equalizer == null ? 0 : equalizer.getCenterFreq((short) band) / 1000;
  }

  private void save() {
    prefs
        .edit()
        .putString("queue", queue.toString())
        .putInt("queue_index", index)
        .putInt("position", position())
        .apply();
  }

  private void releasePlayer() {
    readyPlayer = false;
    if (equalizer != null) {
      equalizer.release();
      equalizer = null;
    }
    if (player != null) {
      player.release();
      player = null;
    }
  }

  private void publish(boolean notification) {
    boolean playing = playing();
    long actions =
        PlaybackState.ACTION_PLAY
            | PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_PLAY_PAUSE
            | PlaybackState.ACTION_SEEK_TO
            | PlaybackState.ACTION_SKIP_TO_NEXT
            | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
    session.setPlaybackState(
        new PlaybackState.Builder()
            .setActions(actions)
            .setState(
                loading
                    ? PlaybackState.STATE_BUFFERING
                    : hasSong()
                        ? (playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED)
                        : PlaybackState.STATE_NONE,
                position(),
                playing ? speed() : 0)
            .build());
    if (!notification) return;
    session.setMetadata(
        new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title())
            .putString(MediaMetadata.METADATA_KEY_ARTIST, MusicData.artist(song()))
            .putString(MediaMetadata.METADATA_KEY_ALBUM, MusicData.album(song()).optString("name"))
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration())
            .build());
    Intent open = new Intent(this, MainActivity.class);
    PendingIntent pending =
        PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification.Builder n =
        new Notification.Builder(this, "playback")
            .setContentTitle(title())
            .setContentText(loading ? "正在加载…" : playing ? MusicData.artist(song()) : "已暂停")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pending)
            .setOngoing(playing || loading)
            .setStyle(
                new Notification.MediaStyle()
                    .setMediaSession(session.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));
    n.addAction(android.R.drawable.ic_media_previous, "上一首", command("previous"));
    n.addAction(
        playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
        playing ? "暂停" : "播放",
        command("toggle"));
    n.addAction(android.R.drawable.ic_media_next, "下一首", command("next"));
    startForeground(1, n.build());
  }

  private PendingIntent command(String action) {
    return PendingIntent.getService(
        this,
        action.hashCode(),
        new Intent(this, PlaybackService.class).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  private final Runnable tick =
      new Runnable() {
        public void run() {
          if (sleepDeadline > 0 && SystemClock.elapsedRealtime() >= sleepDeadline) {
            sleepDeadline = 0;
            pause();
          }
          if (playing()) {
            publish(false);
            if (++ticks % 5 == 0) save();
          }
          changed();
          main.postDelayed(this, 500);
        }
      };

  public int onStartCommand(Intent intent, int flags, int id) {
    if (intent != null) {
      String action = intent.getAction();
      if ("toggle".equals(action)) toggle();
      else if ("next".equals(action)) next(false);
      else if ("previous".equals(action)) previous();
    }
    return START_NOT_STICKY;
  }

  public IBinder onBind(Intent intent) {
    return null;
  }

  public void onDestroy() {
    save();
    generation++;
    main.removeCallbacksAndMessages(null);
    listeners.clear();
    localIo.shutdown();
    releasePlayer();
    audio.abandonAudioFocusRequest(focus);
    session.release();
    instance = null;
    super.onDestroy();
  }
}
