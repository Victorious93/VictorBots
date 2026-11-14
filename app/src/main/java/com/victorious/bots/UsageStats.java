package com.victorious.bots;

import java.util.*;
import org.json.JSONObject;

/** Reads the usage ledger for display. Cost is only ever an owner-rate estimate, clearly labelled. */
public final class UsageStats {
  public static final class Row {
    public String when, purpose, host, kind, requested, reported, error;
    public Long in, out, latency, ttft;
    public boolean ok;

    public String line() {
      return (ok ? "✓ " : "✗ ")
          + purpose.toLowerCase(Locale.ROOT)
          + " → "
          + host
          + "\n   model "
          + (requested.isEmpty() ? "—" : requested)
          + (reported.isEmpty() || reported.equals(requested) ? "" : " (server reported " + reported + ")")
          + "\n   tokens in/out "
          + (in == null ? "unknown" : in)
          + "/"
          + (out == null ? "unknown" : out)
          + (latency == null ? "" : "  ·  " + latency + " ms")
          + (ttft == null ? "" : "  ·  first token " + ttft + " ms")
          + (ok ? "" : "\n   " + error);
    }
  }

  public static List<Row> recent(Db db, int limit) {
    List<Row> out = new ArrayList<>();
    for (JSONObject r : db.rows("SELECT * FROM usage ORDER BY id DESC LIMIT " + Math.max(1, Math.min(limit, 200)))) {
      Row x = new Row();
      x.when = new java.text.SimpleDateFormat("MMM d HH:mm", Locale.ROOT).format(new Date(r.optLong("ts")));
      x.purpose = r.optString("purpose");
      x.host = r.optString("host");
      x.kind = r.optString("kind");
      x.requested = r.optString("requested_model");
      x.reported = r.optString("reported_model");
      x.error = r.optString("error");
      x.ok = r.optInt("ok") == 1;
      x.in = r.has("in_tokens") ? r.optLong("in_tokens") : null;
      x.out = r.has("out_tokens") ? r.optLong("out_tokens") : null;
      x.latency = r.has("latency_ms") ? r.optLong("latency_ms") : null;
      x.ttft = r.has("ttft_ms") ? r.optLong("ttft_ms") : null;
      out.add(x);
    }
    return out;
  }

  /** Billing basis the owner sets per profile; VictorBots has no price list of its own. */
  public static String costLabel(Profile p, Long in, Long out) {
    String billing = p.caps.optString("billing", p.kind == Policy.Kind.PC ? "self_hosted" : "metered");
    if (billing.equals("self_hosted")) return "No per-token charge from a provider (your own hardware).";
    double pin = p.caps.optDouble("price_in_per_m", Double.NaN), pout = p.caps.optDouble("price_out_per_m", Double.NaN);
    if (Double.isNaN(pin) || Double.isNaN(pout)) return "Cost: not estimated. Enter your provider's per-million-token rates in the profile to get an estimate.";
    if (in == null || out == null) return "Cost: cannot be estimated because token usage was not reported.";
    double c = in / 1e6 * pin + out / 1e6 * pout;
    String amt = String.format(Locale.ROOT, "%.6f", c);
    if (billing.equals("subscription"))
      return "API-equivalent estimate ≈ " + amt + " using your entered rates. This is NOT a charge: you pay a subscription, not per token.";
    return "Estimated cost ≈ " + amt + " using the per-million-token rates you entered. Your provider's invoice is authoritative.";
  }

  public static String totals(Db db) {
    JSONObject t = db.one("SELECT COUNT(*) n, SUM(CASE WHEN ok=1 THEN 1 ELSE 0 END) okn, SUM(in_tokens) i, SUM(out_tokens) o, SUM(CASE WHEN in_tokens IS NULL AND ok=1 THEN 1 ELSE 0 END) unk FROM usage");
    return t.optLong("n") + " requests recorded (" + t.optLong("okn") + " succeeded). Reported tokens: in " + t.optLong("i") + ", out " + t.optLong("o") + ". " + t.optLong("unk") + " successful requests did not report usage and are not counted in these totals.";
  }

  private UsageStats() {}
}
