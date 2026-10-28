package com.netodia.tv;

/** Follow resumes three seconds after focus leaves lyrics, independent of cue changes. */
final class LyricFollow {
  private boolean browsing;
  private long resumeAt;

  void reset() {
    browsing = false;
    resumeAt = 0;
  }

  boolean focus(boolean inLyrics, long now) {
    if (browsing == inLyrics) return false;
    browsing = inLyrics;
    resumeAt = inLyrics ? Long.MAX_VALUE : now + 3000;
    return !inLyrics;
  }

  void touch(long now) {
    if (!browsing) resumeAt = now + 3000;
  }

  boolean follows(long now) {
    return !browsing && now >= resumeAt;
  }
}
