package com.netodia.tv;

import org.json.JSONArray;
import org.json.JSONObject;

/** Cross-theme appearance must retain light/dark intent and explicit compatible variants. */
public final class ThemeChoiceTest {
  private static JSONObject theme(String light, String dark) {
    return new JSONObject()
        .put("defaultVariant", dark)
        .put(
            "variants",
            new JSONArray()
                .put(new JSONObject().put("id", dark).put("base", 0x202020))
                .put(new JSONObject().put("id", light).put("base", 0xf4f4f4)));
  }

  private static void check(boolean value, String reason) {
    if (!value) throw new AssertionError(reason);
  }

  public static void main(String[] args) {
    JSONObject windows = theme("light", "dark"), catppuccin = theme("latte", "mocha");
    check(
        TvUi.matchVariant(windows, catppuccin, "light").equals("latte"),
        "light must not become mocha");
    check(
        TvUi.matchVariant(catppuccin, windows, "latte").equals("light"), "latte must remain light");
    check(TvUi.matchVariant(windows, catppuccin, "dark").equals("mocha"), "dark must remain dark");
    check(TvUi.matchVariant(catppuccin, windows, "mocha").equals("dark"), "mocha must remain dark");
    check(
        TvUi.matchVariant(windows, catppuccin, "system").equals("system"),
        "system selection must survive");
    check(
        TvUi.matchVariant(windows, windows, "light").equals("light"),
        "compatible exact variant must survive");
    System.out.println("PASS: independent light/dark/system choices across themes");
  }
}
