package com.netodia.tv;

public final class LyricFollowTest {
  private static void check(boolean result, String message) {
    if (!result) throw new AssertionError(message);
  }
  public static void main(String[] args) {
    LyricFollow follow = new LyricFollow();
    check(follow.focus(true, 100), "entry must snap to active cue");
    check(follow.follows(100), "focused lyrics follow until manual input");
    follow.touch(1000);
    check(!follow.follows(5999), "five-second idle boundary");
    check(!follow.focus(true, 2000), "moving between cues is not re-entry");
    check(follow.follows(6000), "resume while focus remains in lyrics");
    follow.touch(7000);
    follow.touch(9000);
    check(!follow.follows(13999) && follow.follows(14000), "each manual event resets idle");
    follow.touch(15000);
    follow.focus(false, 16000);
    check(!follow.follows(19999) && follow.follows(20000), "leaving must not extend manual delay");
    follow.touch(21000);
    follow.focus(true, 22000);
    check(follow.follows(22000), "re-entry overrides stale browsing delay");
    follow.reset();
    check(follow.follows(0), "new song resets timer");
    System.out.println("Lyric follow tests passed");
  }
}
