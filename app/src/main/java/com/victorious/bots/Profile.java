package com.victorious.bots;

import org.json.JSONArray;
import org.json.JSONObject;

/** A named provider connection. Capabilities are kept as three separate truths. */
public final class Profile {
  public final long id;
  public final String name, base, model, pin;
  public final Policy.Kind kind;
  /** What the owner turned on. */
  public final JSONObject caps;
  /** What was learned: server advertisements and live-test outcomes. */
  public final JSONObject tested;

  public Profile(long id, String name, Policy.Kind kind, String base, String model, String pin, JSONObject caps, JSONObject tested) {
    this.id = id;
    this.name = name;
    this.kind = kind;
    this.base = base;
    this.model = model;
    this.pin = pin;
    this.caps = caps;
    this.tested = tested;
  }

  public static Profile from(JSONObject r) {
    Policy.Kind k;
    try {
      k = Policy.Kind.valueOf(r.optString("kind"));
    } catch (IllegalArgumentException e) {
      k = Policy.Kind.CLOUD;
    }
    JSONObject caps, tested;
    try {
      caps = new JSONObject(r.optString("caps", "{}"));
    } catch (org.json.JSONException e) {
      caps = new JSONObject();
    }
    try {
      tested = new JSONObject(r.optString("tested", "{}"));
    } catch (org.json.JSONException e) {
      tested = new JSONObject();
    }
    return new Profile(r.optLong("id"), r.optString("name"), k, r.optString("base"), r.optString("model"), r.optString("pin"), caps, tested);
  }

  public boolean streaming() {
    return caps.optBoolean("streaming", true);
  }

  public boolean images() {
    return caps.optBoolean("images", false);
  }

  public boolean toolsEnabled() {
    return caps.optBoolean("tools", false);
  }

  public String reasoning() {
    return caps.optString("reasoning_effort", "");
  }

  /** Owner-configured context window in tokens; 0 when not set. */
  public int configuredContext() {
    return caps.optInt("context_tokens", 0);
  }

  public int advertisedContext() {
    JSONObject adv = tested.optJSONObject("advertised");
    return adv == null ? 0 : (int) Math.min(adv.optLong("context_length", 0), Integer.MAX_VALUE);
  }

  /** Context used for budgeting: configured, else a conservative assumption (never the advertised max). */
  public int budgetTokens() {
    int c = configuredContext();
    return c > 0 ? c : 4096;
  }

  public String budgetBasis() {
    return configuredContext() > 0 ? "limit you configured" : "assumed 4096-token window; set the real limit";
  }

  /** Characters per token learned from this model's own reported usage, else a conservative 3.0. */
  public double charsPerToken() {
    JSONObject c = tested.optJSONObject("cpt");
    if (c != null && c.optInt("n") >= 1 && c.optDouble("v") > 1.0) return c.optDouble("v");
    return 3.0;
  }

  public String tokenBasis() {
    JSONObject c = tested.optJSONObject("cpt");
    return c != null && c.optInt("n") >= 1
        ? "calibrated from " + c.optInt("n") + " reported replies"
        : "uncalibrated ~3 chars/token";
  }

  /** PC profiles must carry a bearer token; otherwise they are refused rather than called anonymously. */
  public static boolean requiresKey(Policy.Kind k) {
    return k == Policy.Kind.PC;
  }

  public Provider.Cfg cfg(String key) {
    Provider.Cfg c = new Provider.Cfg();
    c.base = base;
    c.key = key == null ? "" : key;
    c.model = model;
    c.pin = pin;
    c.reasoning = reasoning();
    c.images = images();
    c.readMs = kind == Policy.Kind.PC ? 180000 : 90000;
    return c;
  }

  public String describe() {
    return name + " · " + (kind == Policy.Kind.DROIDCOMMAND ? "DroidCommand AI" : kind == Policy.Kind.PC ? "PC" : "Cloud");
  }

  public static JSONArray arr(String... s) {
    JSONArray a = new JSONArray();
    for (String x : s) a.put(x);
    return a;
  }
}
