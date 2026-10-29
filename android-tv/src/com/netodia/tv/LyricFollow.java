package com.netodia.tv;

/** Entering lyrics follows immediately; manual browsing suspends follow for five seconds. */
final class LyricFollow {
  static final long IDLE_MS = 5000;
  private boolean focused;
  private long resumeAt;

  void reset() {
    focused = false;
    resumeAt = 0;
  }

  /** True only when entering from outside, never for automatic cue-to-cue focus changes. */
  boolean focus(boolean inLyrics, long now) {
    boolean entered = inLyrics && !focused;
    focused = inLyrics;
    if (entered) resumeAt = 0;
    return entered;
  }

  void touch(long now) {
    resumeAt = now + IDLE_MS;
  }

  boolean follows(long now) {
    return now >= resumeAt;
  }
}
