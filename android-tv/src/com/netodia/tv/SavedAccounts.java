package com.netodia.tv;

import org.json.JSONArray;
import org.json.JSONObject;

/** Local session records. UI projections deliberately omit every session credential. */
final class SavedAccounts {
  static JSONObject put(JSONObject records, long id, String nickname, String cookie, String device) throws Exception {
    JSONObject copy = new JSONObject(records.toString());
    if (id > 0 && !cookie.isEmpty()) {
      JSONObject account = new JSONObject();
      account.put("id", id).put("nickname", nickname).put("cookie", cookie).put("device", device);
      copy.put(Long.toString(id), account);
    }
    return copy;
  }

  static JSONArray metadata(JSONObject records) throws Exception {
    JSONArray result = new JSONArray();
    java.util.Iterator<String> keys = records.keys();
    while (keys.hasNext()) {
      JSONObject entry = records.optJSONObject(keys.next());
      if (entry == null || entry.optLong("id") <= 0 || entry.optString("cookie").isEmpty()) continue;
      result.put(new JSONObject().put("id", entry.optLong("id")).put("nickname", entry.optString("nickname")));
    }
    return result;
  }
}
