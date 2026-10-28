package com.netodia.tv;

import android.graphics.Rect;
import android.view.View;
import java.util.ArrayList;

/** Explicit logical rows prevent focus escaping to unrelated artwork or jumping across lists. */
final class FocusRouter {
  static final int SIDE = 0, CONTENT = 1, PLAYER = 2;

  static final class Node {
    final View view;
    final int zone, row, col;
    final String key;

    Node(View v, int z, int r, int c, String k) {
      view = v;
      zone = z;
      row = r;
      col = c;
      key = k;
    }
  }

  private final ArrayList<Node> nodes = new ArrayList<>();
  private String lastContent = "", selectedSide = "nav:library", selectedTab = "tab:0";

  void add(View view, int zone, int row, int col, String key) {
    view.setId(View.generateViewId());
    view.setFocusable(true);
    view.setFocusableInTouchMode(true);
    TvUi.eagerTouchFocus(view);
    nodes.add(new Node(view, zone, row, col, key));
    view.setOnFocusChangeListener(
        (v, has) -> {
          if (has && zone == CONTENT) lastContent = key;
        });
  }

  void removePrefix(String prefix) {
    nodes.removeIf(n -> n.key.startsWith(prefix));
  }

  void clearAll() {
    nodes.clear();
    lastContent = "";
  }

  void clearContent() {
    lastContent = "";
    nodes.removeIf(n -> n.zone == CONTENT);
  }

  void selectTab(int tab) {
    selectedTab = "tab:" + tab;
  }

  void selectSide(String key) {
    selectedSide = key;
  }

  String key(View view) {
    Node n = find(view);
    return n == null ? "" : n.key;
  }

  private Node find(View v) {
    for (Node n : nodes) if (n.view == v) return n;
    return null;
  }

  private boolean usable(Node n) {
    return n.view.isAttachedToWindow() && n.view.isShown() && n.view.isEnabled();
  }

  boolean focus(String key) {
    for (Node n : nodes)
      if (n.key.equals(key) && usable(n)) {
        n.view.requestFocus();
        return true;
      }
    return false;
  }

  private Node first(int zone) {
    for (Node n : nodes) if (n.zone == zone && usable(n)) return n;
    return null;
  }

  private Node rememberedContent() {
    for (Node n : nodes) if (n.key.equals(lastContent) && usable(n)) return n;
    for (Node n : nodes) if (n.key.equals(selectedTab) && usable(n)) return n;
    for (Node n : nodes) if (n.zone == CONTENT && n.row >= 1 && usable(n)) return n;
    for (Node n : nodes) if (n.zone == CONTENT && n.row == 0 && usable(n)) return n;
    return first(CONTENT);
  }

  boolean move(View focused, int direction) {
    Node from = find(focused), to = null;
    if (from == null) {
      to = first(CONTENT);
      if (to == null) to = first(SIDE);
    } else if (from.zone == SIDE) {
      if (direction == View.FOCUS_RIGHT) to = rememberedContent();
      else if (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN)
        to = vertical(from, direction, SIDE);
    } else if (from.zone == CONTENT) {
      if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) {
        int delta = direction == View.FOCUS_LEFT ? -1 : 1;
        for (Node n : nodes)
          if (n.zone == CONTENT && n.row == from.row && n.col == from.col + delta && usable(n)) {
            to = n;
            break;
          }
        if (to == null && delta < 0) {
          focus(selectedSide);
          return true;
        }
      } else {
        to = vertical(from, direction, CONTENT);
        if (to == null && direction == View.FOCUS_DOWN) to = first(PLAYER);
      }
    } else {
      if (direction == View.FOCUS_UP && from.row == 0) to = rememberedContent();
      else if (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN)
        to = vertical(from, direction, PLAYER);
      else {
        int delta = direction == View.FOCUS_LEFT ? -1 : 1;
        for (Node n : nodes)
          if (n.zone == PLAYER && n.row == from.row && n.col == from.col + delta && usable(n)) {
            to = n;
            break;
          }
      }
    }
    if (to != null) {
      to.view.requestFocus();
      to.view.requestRectangleOnScreen(
          new Rect(0, 0, to.view.getWidth(), to.view.getHeight()), false);
    }
    return true; // Clamp at the edge; never lose focus to a noninteractive view.
  }

  private Node vertical(Node from, int direction, int zone) {
    int sign = direction == View.FOCUS_UP ? -1 : 1;
    Node best = null;
    long score = Long.MAX_VALUE;
    for (Node n : nodes) {
      if (n.zone != zone || !usable(n) || (n.row - from.row) * sign <= 0) continue;
      long candidate = Math.abs(n.row - from.row) * 10000L + Math.abs(n.col - from.col);
      if (candidate < score) {
        score = candidate;
        best = n;
      }
    }
    if (zone == CONTENT && direction == View.FOCUS_UP && best != null && best.row == 0) {
      for (Node node : nodes) if (node.key.equals(selectedTab) && usable(node)) return node;
    }
    return best;
  }
}
