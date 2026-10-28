package com.netodia.tv;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import org.json.*;

/** Android MediaStore and a remote-friendly folder browser replace desktop file dialogs. */
final class LocalMusic {
  static JSONArray library(Context c) throws Exception {
    JSONArray tracks = new JSONArray();
    JSONArray roots =
        MusicData.array(c.getSharedPreferences("music", 0).getString("library_folders", "[]"));
    String[] columns = {"_id", "title", "artist", "album", "album_id", "duration", "_data"};
    try (Cursor cursor =
        c.getContentResolver()
            .query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                columns,
                null,
                null,
                "title COLLATE NOCASE ASC")) {
      if (cursor != null)
        while (cursor.moveToNext()) {
          String filePath = cursor.getString(6);
          if (roots.length() > 0) {
            boolean included = false;
            for (int i = 0; i < roots.length(); i++)
              if (filePath != null && filePath.startsWith(roots.optString(i) + "/")) {
                included = true;
                break;
              }
            if (!included) continue;
          }
          long id = cursor.getLong(0), album = cursor.getLong(4);
          JSONObject song =
              Backend.json(
                  "id",
                  -id,
                  "name",
                  cursor.getString(1),
                  "uri",
                  ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                      .toString(),
                  "dt",
                  cursor.getLong(5),
                  "ar",
                  new JSONArray().put(Backend.json("id", 0, "name", cursor.getString(2))),
                  "al",
                  Backend.json(
                      "id",
                      album,
                      "name",
                      cursor.getString(3),
                      "picUrl",
                      "content://media/external/audio/albumart/" + album));
          if (!cursor.isNull(6)) song.put("path", cursor.getString(6));
          tracks.put(song);
        }
    }
    return tracks;
  }

  static String sidecar(JSONObject song) {
    String path = song.optString("path", "");
    if (path.isEmpty()) return "";
    int dot = path.lastIndexOf('.');
    if (dot < 0) return "";
    for (String extension : new String[] {".lrc", ".LRC"}) {
      File file = new File(path.substring(0, dot) + extension);
      if (!file.isFile() || file.length() > 1024 * 1024) continue;
      try {
        byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        if (text.indexOf('\ufffd') >= 0)
          text = new String(bytes, java.nio.charset.Charset.forName("GB18030"));
        return text.replace("\ufeff", "");
      } catch (Exception ignored) {
      }
    }
    return "";
  }

  static boolean audio(String name) {
    return name.toLowerCase(Locale.ROOT)
        .matches(".*\\.(mp3|flac|m4a|aac|ogg|opus|wav|aiff|aif|wma|alac)$");
  }

  static JSONArray folder(String path) throws Exception {
    File folder = new File(path).getCanonicalFile();
    if (!folder.getPath().equals("/storage") && !folder.getPath().startsWith("/storage/"))
      throw new Exception("请选择电视存储或 USB 存储");
    File[] entries =
        folder.listFiles(f -> !f.isHidden() && (f.isDirectory() || audio(f.getName())));
    if (entries == null) throw new Exception("无法读取此目录，请检查存储权限");
    Arrays.sort(
        entries,
        Comparator.<File, Boolean>comparing(f -> !f.isDirectory())
            .thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));
    JSONArray out = new JSONArray();
    for (File f : entries)
      out.put(
          Backend.json(
              "name",
              f.getName(),
              "path",
              f.getAbsolutePath(),
              "directory",
              f.isDirectory(),
              "uri",
              Uri.fromFile(f).toString(),
              "id",
              -Math.abs((long) f.getAbsolutePath().hashCode()),
              "ar",
              new JSONArray().put(Backend.json("name", "本地音乐")),
              "al",
              Backend.json("name", folder.getName()),
              "dt",
              0));
    return out;
  }
}
