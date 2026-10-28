package com.netodia.tv;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

final class MusicData {
  static String artist(JSONObject song) {
    JSONArray artists = song.optJSONArray("ar");
    if (artists == null) artists = song.optJSONArray("artists");
    StringBuilder result = new StringBuilder();
    if (artists != null)
      for (int i = 0; i < artists.length(); i++) {
        if (i > 0) result.append(" / ");
        JSONObject a = artists.optJSONObject(i);
        if (a != null) result.append(a.optString("name"));
      }
    return result.toString();
  }

  static JSONObject album(JSONObject song) {
    JSONObject a = song.optJSONObject("al");
    if (a == null) a = song.optJSONObject("album");
    return a == null ? new JSONObject() : a;
  }

  static String cover(JSONObject entity) {
    for (String key : new String[] {"coverImgUrl", "picUrl", "img1v1Url"}) {
      String url = entity.optString(key, "");
      if (!url.isEmpty() && !url.equals("null")) return url;
    }
    JSONObject a = entity.optJSONObject("al");
    return a == null ? "" : cover(a);
  }

  static String identity(JSONObject song) {
    return song.has("uri") ? song.optString("uri") : Long.toString(song.optLong("id"));
  }

  static String time(int ms) {
    int seconds = Math.max(0, ms / 1000);
    return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
  }

  static JSONArray array(String value) {
    try {
      return new JSONArray(value);
    } catch (Exception ignored) {
      return new JSONArray();
    }
  }

  static void remember(Context context, String key, JSONObject song, int limit) {
    SharedPreferences p = context.getSharedPreferences("music", 0);
    JSONArray old = array(p.getString(key, "[]"));
    JSONArray result = new JSONArray();
    result.put(song);
    for (int i = 0; i < old.length() && result.length() < limit; i++) {
      JSONObject item = old.optJSONObject(i);
      if (item != null && !identity(item).equals(identity(song))) result.put(item);
    }
    p.edit().putString(key, result.toString()).apply();
  }

  static JSONArray saved(Context c, String key) {
    return array(c.getSharedPreferences("music", 0).getString(key, "[]"));
  }

  static boolean favorite(Context c, JSONObject song) {
    JSONArray all = saved(c, "favorites");
    for (int i = 0; i < all.length(); i++)
      if (identity(all.optJSONObject(i)).equals(identity(song))) return true;
    return false;
  }

  static void favorite(Context c, JSONObject song, boolean value) {
    if (value) {
      remember(c, "favorites", song, 10000);
      return;
    }
    JSONArray all = saved(c, "favorites"), result = new JSONArray();
    for (int i = 0; i < all.length(); i++)
      if (!identity(all.optJSONObject(i)).equals(identity(song))) result.put(all.optJSONObject(i));
    c.getSharedPreferences("music", 0).edit().putString("favorites", result.toString()).apply();
  }

  static void rememberPlay(Context c, JSONObject song) {
    remember(c, "history", song, 200);
    android.content.SharedPreferences prefs = c.getSharedPreferences("music", 0);
    try {
      JSONObject counts = new JSONObject(prefs.getString("play_counts", "{}"));
      String id = identity(song);
      counts.put(id, counts.optInt(id) + 1);
      prefs.edit().putString("play_counts", counts.toString()).apply();
    } catch (Exception ignored) {
    }
  }

  static JSONArray mostPlayed(Context c) {
    JSONArray history = saved(c, "history");
    ArrayList<JSONObject> songs = new ArrayList<>();
    for (int i = 0; i < history.length(); i++) songs.add(history.optJSONObject(i));
    try {
      JSONObject counts =
          new JSONObject(c.getSharedPreferences("music", 0).getString("play_counts", "{}"));
      songs.sort((a, b) -> Integer.compare(counts.optInt(identity(b)), counts.optInt(identity(a))));
    } catch (Exception ignored) {
    }
    JSONArray result = new JSONArray();
    for (JSONObject song : songs) result.put(song);
    return result;
  }

  static final class Lyrics {
    final ArrayList<Long> times = new ArrayList<>();
    final ArrayList<String> lines = new ArrayList<>();
    final ArrayList<String> translations = new ArrayList<>();
    final ArrayList<String> romanizations = new ArrayList<>();
    String plain = "";

    Lyrics(String original, String translation) {
      this(original, translation, "");
    }

    Lyrics(String original, String translation, String romanization) {
      TreeMap<Long, String> romanized = parse(romanization);
      TreeMap<Long, String> parsed = parse(original), translated = parse(translation);
      times.addAll(parsed.keySet());
      lines.addAll(parsed.values());
      for (Long time : times) {
        translations.add(translated.getOrDefault(time, ""));
        romanizations.add(romanized.getOrDefault(time, ""));
      }
      if (parsed.isEmpty()) plain = original.replaceAll("\\[[^]]*\\]", "").trim();
    }

    private TreeMap<Long, String> parse(String input) {
      TreeMap<Long, String> out = new TreeMap<>();
      Pattern pattern = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\]");
      for (String line : input.split("\\n")) {
        Matcher m = pattern.matcher(line);
        String words = line.replaceAll("\\[[^]]*\\]", "").trim();
        while (m.find())
          out.put(
              Long.parseLong(m.group(1)) * 60000 + (long) (Double.parseDouble(m.group(2)) * 1000),
              words);
      }
      return out;
    }

    int at(long ms) {
      int lo = 0, hi = times.size() - 1, found = -1;
      while (lo <= hi) {
        int mid = (lo + hi) >>> 1;
        if (times.get(mid) <= ms) {
          found = mid;
          lo = mid + 1;
        } else hi = mid - 1;
      }
      return found;
    }
  }
}
