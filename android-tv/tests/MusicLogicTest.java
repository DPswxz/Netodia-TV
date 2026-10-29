package com.netodia.tv;

/** Host JVM regression checks for timestamp parsing, duplicate stamps and UTF-8 sidecar lyrics. */
public final class MusicLogicTest {
  private static void check(boolean value, String reason) {
    if (!value) throw new AssertionError(reason);
  }

  public static void main(String[] args) {
    MusicData.Lyrics lyrics =
        new MusicData.Lyrics(
            "[ar:artist]\n[00:10.50][00:20.500]重复的一行\n[00:01.20]开场\n[01:00]结尾",
            "[00:10.500]translation\n[00:01.200]intro");
    check(lyrics.times.size() == 4, "multiple timestamps must produce separate cues");
    check(lyrics.times.get(0) == 1200, "fractional seconds must parse accurately");
    check(lyrics.at(1199) == -1, "no line should be active before its timestamp");
    check(lyrics.at(1200) == 0, "timestamp boundary must activate its line");
    check(lyrics.at(20499) == 1, "binary lookup should keep the previous line");
    check(lyrics.at(20500) == 2, "duplicate line's second timestamp must be seekable");
    check(lyrics.translations.get(1).equals("translation"), "translation must match timestamps");
    check(
        lyrics.translations.get(2).isEmpty(),
        "missing translation must not borrow a different cue");
    MusicData.Lyrics roman =
        new MusicData.Lyrics("[00:01]原词\n[00:02]下一句", "[00:01]translation", "[00:01]gen shi");
    check(
        roman.romanizations.get(0).equals("gen shi"),
        "romanization must align to original timestamps");
    check(
        roman.romanizations.get(1).isEmpty(),
        "missing romanization must not borrow another timestamp");
    MusicData.Lyrics plain = new MusicData.Lyrics("纯音乐", "");
    check(plain.lines.isEmpty() && plain.plain.equals("纯音乐"), "untimed lyrics must stay readable");
    check(MusicData.time(288999).equals("4:48"), "duration must truncate, not round up");
    check(LocalMusic.audio("音乐.FLAC"), "audio extension detection must ignore case");
    check(!LocalMusic.audio("cover.jpg"), "folder browser must not try playing artwork");
    LyricFollow follow = new LyricFollow();
    check(follow.follows(0), "lyrics initially follow playback");
    follow.focus(true, 100);
    check(!follow.follows(99999), "browsing lyrics must suspend following");
    check(follow.focus(false, 200), "leaving lyrics starts delayed follow");
    check(!follow.follows(3199), "must not follow before three seconds");
    follow.focus(false, 1000);
    check(follow.follows(3200), "other controls must not restart the exit timer");
    follow.focus(true, 3300);
    check(!follow.follows(10000), "re-entering lyrics cancels pending return");
    follow.focus(false, 10001);
    follow.touch(12000);
    check(!follow.follows(14999) && follow.follows(15000), "pointer browsing postpones return");
    follow.reset();
    check(follow.follows(0), "new pages must not retain old browsing state");
    check(PageWindow.clamp(5, 0, 4) == 0, "empty data has no negative page");
    check(PageWindow.clamp(10, 122, 5) == 10, "middle pages stay stable");
    check(PageWindow.clamp(99, 122, 5) == 24, "final partial page stays accessible");
    check(PageWindow.needsFetch(2, 12, 30, true), "fetch before showing an incomplete cached page");
    check(!PageWindow.needsFetch(1, 12, 30, true), "complete cached pages need no request");
    check(!PageWindow.needsFetch(2, 12, 30, false), "last partial page never fetches again");
    for (int count = 1; count < 100; count++)
      for (int size = 1; size < 15; size++) {
        int last = PageWindow.clamp(1000, count, size);
        int covered = 0;
        for (int page = 0; page <= last; page++) covered += Math.min(size, count - page * size);
        check(covered == count, "paging must not skip or duplicate any item");
      }
    System.out.println(
        "PASS: lyric timestamps, translation alignment, seek boundaries and audio filtering");
  }
}
