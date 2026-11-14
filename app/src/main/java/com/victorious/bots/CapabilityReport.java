package com.victorious.bots;

import java.text.DateFormat;
import java.util.*;
import org.json.JSONObject;

/**
 * Presents each capability as configured / advertised / tested so nothing is claimed from a single
 * source. "Tested" only ever comes from a live test the owner ran; "advertised" only from what the
 * server itself reported; "configured" is the owner's switch.
 */
public final class CapabilityReport {
  public static final class Row {
    public final String name, configured, advertised, tested;
    public final boolean usable;

    Row(String n, String c, String a, String t, boolean u) {
      name = n;
      configured = c;
      advertised = a;
      tested = t;
      usable = u;
    }

    public String line() {
      return name + "\n   configured: " + configured + "\n   advertised: " + advertised + "\n   tested: " + tested;
    }
  }

  private static String testLine(JSONObject t, String key) {
    JSONObject r = t == null ? null : t.optJSONObject(key);
    if (r == null) return "not tested";
    String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(r.optLong("at")));
    return (r.optBoolean("ok") ? "passed " : "failed ") + when + (r.optString("note").isEmpty() ? "" : " (" + r.optString("note") + ")");
  }

  public static List<Row> build(Profile p) {
    List<Row> rows = new ArrayList<>();
    JSONObject adv = p.tested.optJSONObject("advertised");
    org.json.JSONArray advCaps = adv == null ? null : adv.optJSONArray("capabilities");
    java.util.Set<String> a = new java.util.HashSet<>();
    if (advCaps != null) for (int i = 0; i < advCaps.length(); i++) a.add(advCaps.optString(i));
    boolean advertisesAnything = advCaps != null;

    rows.add(new Row("Text chat", "always", "assumed for any chat endpoint", testLine(p.tested, "text"), true));
    rows.add(new Row("Streaming", p.streaming() ? "on" : "off", "not advertised by OpenAI-style servers", testLine(p.tested, "streaming"), true));
    rows.add(
        new Row(
            "Tool calls",
            p.toolsEnabled() ? "on" : "off",
            advertisesAnything ? (a.contains("tools") ? "yes (server reports tools)" : "not listed by server") : "not reported by this server",
            testLine(p.tested, "tools") + (p.toolsEnabled() ? "" : "; VictorBots never executes tool calls itself"),
            p.toolsEnabled()));
    rows.add(
        new Row(
            "Image input",
            p.images() ? "on" : "off",
            advertisesAnything ? (a.contains("vision") ? "yes (server reports vision)" : "not listed by server") : "not reported by this server",
            testLine(p.tested, "images"),
            p.images()));
    int cfg = p.configuredContext(), advc = p.advertisedContext();
    rows.add(
        new Row(
            "Context window",
            cfg > 0 ? cfg + " tokens" : "not set (assuming 4096)",
            advc > 0 ? advc + " tokens model maximum — the server's runtime setting may be smaller" : "not reported",
            "not verifiable from outside; reported prompt tokens show real use",
            true));
    rows.add(
        new Row(
            "Reasoning effort",
            p.reasoning().isEmpty() ? "not sent" : "sends reasoning_effort=" + p.reasoning(),
            "not reported by this server",
            testLine(p.tested, "reasoning") + (p.tested.optJSONObject("reasoning") != null ? "; acceptance does not prove effect" : ""),
            !p.reasoning().isEmpty()));
    JSONObject models = p.tested.optJSONObject("models");
    rows.add(
        new Row(
            "Model discovery",
            "—",
            models == null ? "not run" : models.optBoolean("ok") ? models.optInt("count") + " models listed" : "server did not list models; enter the id manually",
            models == null ? "not tested" : "ran " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(models.optLong("at"))),
            true));
    return rows;
  }

  private CapabilityReport() {}
}
