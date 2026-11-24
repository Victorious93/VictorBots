package com.victorious.bots;

import java.util.*;
import org.json.*;

/**
 * Victorious Framework: client side of droidcommand-AI's COMPANION_COMMS v1 (docs/COMPANION_COMMS.md in that repo).
 * Pure encoding/decoding. VictorBots never decides an outcome: it records what droidcommand-AI reports, and only
 * {@code completed} becomes COMPLETED. {@code unknown} (the gateway could not tell) becomes "outcome unknown" and is
 * never resent. Approvals happen in droidcommand-AI; nothing here approves.
 */
public final class CommsCodec {
  public static final String ACTION = "ai.droidcommand.action.COMPANION_COMMS";
  public static final int MIN_VERSION = 1, MAX_VERSION = 1;
  public static final long TTL_MS = 120_000;

  public static String discover(String requestId) throws JSONException {
    checkId(requestId);
    return new JSONObject().put("v", MAX_VERSION).put("min_v", MIN_VERSION).put("task", "discover").put("request_id", requestId).toString();
  }

  public static String receipt(String requestId) throws JSONException {
    checkId(requestId);
    return new JSONObject().put("v", MAX_VERSION).put("task", "receipt").put("request_id", requestId).toString();
  }

  /**
   * @param nonce 32 lowercase hex characters, fresh for EVERY send (including re-sends of the same request id)
   * @param mode  what the bot is configured for ("approval", "auto"...). Only a claim: droidcommand-AI honours "auto"
   *              solely when the owner granted auto-reply for this bot, and VictorSMS re-checks it independently.
   */
  public static String call(String requestId, String nonce, long nowMs, String capability, Map<String, String> args, String botId, String mode, String reason) throws JSONException {
    checkId(requestId);
    if (nonce == null || !nonce.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Bad nonce.");
    if (capability == null || !capability.startsWith("communications.")) throw new IllegalArgumentException("Not a communications capability.");
    JSONObject a = new JSONObject();
    if (args != null) for (Map.Entry<String, String> e : args.entrySet()) a.put(e.getKey(), e.getValue());
    JSONObject o = new JSONObject().put("v", MAX_VERSION).put("min_v", MIN_VERSION).put("task", "call").put("request_id", requestId).put("nonce", nonce)
        .put("ts", nowMs).put("expires_at", nowMs + TTL_MS).put("capability", capability).put("args", a)
        .put("reason", reason == null ? "" : reason.substring(0, Math.min(200, reason.length())));
    if (botId != null || mode != null) {
      JSONObject actor = new JSONObject();
      if (botId != null) actor.put("bot_id", botId);
      if (mode != null) actor.put("mode", mode);
      o.put("actor", actor);
    }
    return o.toString();
  }

  private static void checkId(String id) {
    if (id == null || !id.matches("[A-Za-z0-9_-]{8,64}")) throw new IllegalArgumentException("Bad request id.");
  }

  public static final class Reply {
    public boolean parsed, ok;
    public String state = "", code = "", detail = "", requestId = "", approvalId = "", jobId = "", resultJson = "{}";
    public int minVersion = -1, maxVersion = -1;
    /** capability id to "available" or the reason it is unavailable. */
    public Map<String, String> capabilities = new LinkedHashMap<>();

    public boolean incompatible() {
      return "incompatible_version".equals(code);
    }

    /** True only when discovery said this capability can run right now. */
    public boolean supports(String capability) {
      return "available".equals(capabilities.get(capability));
    }
  }

  public static Reply parse(String raw) {
    Reply r = new Reply();
    if (raw == null || raw.length() > 700_000) return r;
    try {
      JSONObject o = new JSONObject(raw);
      if (o.optInt("v", -1) < 1 || !(o.opt("ok") instanceof Boolean)) return r;
      r.parsed = true;
      r.ok = o.getBoolean("ok");
      r.state = o.optString("state", "").toLowerCase(Locale.ROOT);
      r.code = o.optString("code", "");
      r.detail = Redact.snippet(o.optString("detail", ""), 300);
      r.requestId = o.optString("request_id", "");
      r.approvalId = o.optString("approval_id", "");
      r.jobId = o.optString("job_id", "");
      JSONObject v = o.optJSONObject("versions");
      if (v != null) { r.minVersion = v.optInt("min", -1); r.maxVersion = v.optInt("max", -1); }
      JSONObject res = o.optJSONObject("result");
      if (res != null) r.resultJson = Redact.text(res.toString());
      JSONArray caps = o.optJSONArray("capabilities");
      for (int i = 0; caps != null && i < caps.length(); i++) {
        JSONObject c = caps.getJSONObject(i);
        String id = c.optString("id");
        if (id.isEmpty()) continue;
        r.capabilities.put(id, "available".equals(c.optString("status")) ? "available" : c.optString("reason", "unavailable"));
      }
    } catch (JSONException e) {
      r.parsed = false;
    }
    return r;
  }

  /** The job state a reply justifies, or null if it justifies none. Anything not listed here changes nothing. */
  public static Jobs.State stateFor(Reply r) {
    if (!r.parsed) return null;
    switch (r.state) {
      case "completed": return r.ok ? Jobs.State.COMPLETED : null; // a contradictory reply is never a completion
      case "running": return r.ok ? Jobs.State.RUNNING : null;
      case "pending_approval": return Jobs.State.AWAITING_APPROVAL;
      case "failed":
      case "refused": return Jobs.State.FAILED;
      case "unknown": return Jobs.State.UNKNOWN;
      default: return null;
    }
  }

  /** Applies a reply through the same backend-report path as every other backend. Returns whether anything changed. */
  public static boolean apply(Jobs jobs, long jobId, Reply r) {
    Jobs.State s = stateFor(r);
    if (s == null) return false;
    if (s == Jobs.State.UNKNOWN) {
      Jobs.Job before = jobs.get(jobId);
      if (before == null || before.state.terminal() || before.state == Jobs.State.UNKNOWN) return false;
      jobs.markUnknown(jobId, r.detail.isEmpty() ? "droidcommand-AI could not confirm the outcome." : r.detail);
      return true;
    }
    Jobs.Remote remote = new Jobs.Remote();
    remote.id = r.requestId.isEmpty() ? "comms" : r.requestId;
    remote.state = s == Jobs.State.AWAITING_APPROVAL ? "awaiting_approval" : s.name().toLowerCase(Locale.ROOT);
    remote.error = s == Jobs.State.FAILED ? (r.detail.isEmpty() ? r.code : r.detail) : "";
    if (s == Jobs.State.COMPLETED) remote.resultJson = r.resultJson.length() > 8000 ? "{}" : r.resultJson;
    remote.finished = System.currentTimeMillis();
    return jobs.applyRemote(jobId, remote);
  }

  private CommsCodec() {}
}
