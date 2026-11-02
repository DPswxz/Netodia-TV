package com.netodia.tv;
import org.json.JSONObject;
import org.json.JSONArray;

public final class SavedAccountsTest {
  public static void main(String[] args) throws Exception {
    JSONObject initial = new JSONObject();
    JSONObject records = SavedAccounts.put(initial, 1, "First", "test-session-one", "device-one");
    records = SavedAccounts.put(records, 2, "Second", "test-session-two", "device-two");
    check(initial.length() == 0, "input snapshot is unchanged");
    records = SavedAccounts.put(records, 1, "Updated", "refreshed-one", "device-one");
    check(records.length() == 2, "refresh retains the other account");
    check(records.getJSONObject("2").getString("cookie").equals("test-session-two"), "credentials stay isolated");
    JSONArray ui = SavedAccounts.metadata(records);
    check(ui.length() == 2, "both accounts available");
    for (int i = 0; i < ui.length(); i++) {
      check(!ui.getJSONObject(i).has("cookie") && !ui.getJSONObject(i).has("device"), "UI has no session credentials");
    }
    records.remove("2");
    check(records.has("1") && SavedAccounts.metadata(records).length() == 1, "removing one keeps the current account");
    check(SavedAccounts.put(records, 3, "Invalid", "", "").length() == 1, "empty sessions are not saved");
    System.out.println("SavedAccountsTest passed");
  }
  static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
