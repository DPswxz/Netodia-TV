package com.netodia.tv;

/** Host JVM regression checks for timestamp parsing, duplicate stamps and UTF-8 sidecar lyrics. */
public final class MusicLogicTest {
  private static void check(boolean value, String reason) {
    if (!value) throw new AssertionError(reason);
  }
  public static void main(String[] args) {
    MusicData.Lyrics lyrics = new MusicData.Lyrics(
        "[ar:artist]\n[00:10.50][00:20.500]重复的一行\n[00:01.20]开场\n[01:00]结尾",
        "[00:10.500]translation\n[00:01.200]intro");
    check(lyrics.times.size() == 4, "multiple timestamps must produce separate cues");
    check(lyrics.times.get(0) == 1200, "fractional seconds must parse accurately");
    check(lyrics.at(1199) == -1, "no line should be active before its timestamp");
    check(lyrics.at(1200) == 0, "timestamp boundary must activate its line");
    check(lyrics.at(20499) == 1, "binary lookup should keep the previous line");
    check(lyrics.at(20500) == 2, "duplicate line's second timestamp must be seekable");
    check(lyrics.translations.get(1).equals("translation"), "translation must match timestamps");
    check(lyrics.translations.get(2).isEmpty(), "missing translation must not borrow a different cue");
    MusicData.Lyrics roman = new MusicData.Lyrics("[00:01]原词\n[00:02]下一句","[00:01]translation","[00:01]gen shi");
    check(roman.romanizations.get(0).equals("gen shi"),"romanization must align to original timestamps");
    check(roman.romanizations.get(1).isEmpty(),"missing romanization must not borrow another timestamp");
    MusicData.Lyrics plain = new MusicData.Lyrics("纯音乐", "");
    check(plain.lines.isEmpty() && plain.plain.equals("纯音乐"), "untimed lyrics must stay readable");
    check(MusicData.time(288999).equals("4:48"), "duration must truncate, not round up");
    check(LocalMusic.audio("音乐.FLAC"), "audio extension detection must ignore case");
    check(!LocalMusic.audio("cover.jpg"), "folder browser must not try playing artwork");
    System.out.println("PASS: lyric timestamps, translation alignment, seek boundaries and audio filtering");
  }
}
