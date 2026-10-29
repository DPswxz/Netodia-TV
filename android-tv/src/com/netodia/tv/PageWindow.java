package com.netodia.tv;

/** Cached API batches are independent of the number of items that fit a TV page. */
final class PageWindow {
  static int clamp(int page, int count, int size) {
    return Math.max(0, Math.min(page, Math.max(0, (count - 1) / Math.max(1, size))));
  }

  static boolean needsFetch(int page, int size, int count, boolean more) {
    return more && (long) (page + 1) * size > count;
  }
}
