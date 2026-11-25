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
    if (args != null)
      for (Map.Entry<String, String> e : args.entrySet()) {
        if (e.getKey() == null || e.getKey().isEmpty()) throw new IllegalArgumentException("An argument has no name.");
        // JSONObject.put(name, null) silently removes the name; refuse instead of sending something other than asked.
        if (e.getValue() == null) throw new IllegalArgumentException("Argument '" + e.getKey() + "' has no value.");
        a.put(e.getKey(), e.getValue());
      }
    JSONObject o = new JSONObject().put("v", MAX_VERSION).put("min_v", MIN_VERSION).put("task", "call").put("request_id", requestId).put("nonce", nonce)
        .put("ts", nowMs).put("expires_at", nowMs + TTL_MS).put("capability", capability).put("args", a)
        .put("reason", clip(reason, 200));
    if (botId != null || mode != null) {
      JSONObject actor = new JSONObject();
      if (botId != null) actor.put("bot_id", botId);
      if (mode != null) actor.put("mode", mode);
      o.put("actor", actor);
    }
    return o.toString();
  }

  /** At most [max] UTF-16 units, never ending in half of a surrogate pair. */
  public static String clip(String s, int max) {
    if (s == null) return "";
    if (s.length() <= max) return s;
    int end = max;
    if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) end--;
    return s.substring(0, end);
  }

  /** A string field, or "" when it is absent, JSON null or not a string (org.json would turn null into "null"). */
  static String str(JSONObject o, String key) {
    Object v = o.opt(key);
    if (v == null || v == JSONObject.NULL) return "";
    return v instanceof String ? (String) v : String.valueOf(v);
  }

  private static void checkId(String id) {
    if (id == null || !id.matches("[A-Za-z0-9_-]{8,64}")) throw new IllegalArgumentException("Bad request id.");
  }

  public static final class Reply {
    public boolean parsed, ok;
    public String state = "", code = "", detail = "", requestId = "", approvalId = "", jobId = "", resultJson = "{}";
    public int minVersion = -1, maxVersion = -1;
    /**
     * Empty for a reply droidcommand-AI produced. {@code not_sent} or {@code no_reply} for an outcome this app decided
     * itself because there was no reply: {@code not_sent} means nothing left this app; {@code no_reply} means the request
     * was handed over and nothing came back, so it may have run.
     */
    public String transport = "";
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
      Object vRaw = o.opt("v");
      if (!(vRaw instanceof Number) || !(o.opt("ok") instanceof Boolean)) return r;
      int ver = ((Number) vRaw).intValue();
      if (ver < 1) return r;
      r.parsed = true;
      JSONObject v = o.optJSONObject("versions");
      if (v != null) { r.minVersion = v.optInt("min", -1); r.maxVersion = v.optInt("max", -1); }
      r.requestId = str(o, "request_id");
      if (ver < MIN_VERSION || ver > MAX_VERSION) {
        // A reply in a version this app does not speak is never read as if it were v1: it is a version mismatch.
        r.ok = false;
        r.state = "error";
        r.code = "incompatible_version";
        if (r.minVersion < 0) r.minVersion = ver;
        if (r.maxVersion < 0) r.maxVersion = ver;
        return r;
      }
      r.ok = o.getBoolean("ok");
      r.state = str(o, "state").toLowerCase(Locale.ROOT);
      r.code = str(o, "code");
      r.detail = Redact.snippet(str(o, "detail"), 300);
      r.approvalId = str(o, "approval_id");
      r.jobId = str(o, "job_id");
      JSONObject res = o.optJSONObject("result");
      if (res != null) r.resultJson = Redact.text(res.toString());
      JSONArray caps = o.optJSONArray("capabilities");
      for (int i = 0; caps != null && i < caps.length(); i++) {
        JSONObject c = caps.optJSONObject(i);
        if (c == null) continue; // one malformed entry does not discard the rest of the answer
        String id = str(c, "id");
        if (id.isEmpty()) continue;
        String reason = str(c, "reason");
        r.capabilities.put(id, "available".equals(str(c, "status")) ? "available" : reason.isEmpty() ? "unavailable" : reason);
      }
    } catch (JSONException e) {
      r.parsed = false;
    }
    return r;
  }

  /** Nothing left this app: the pinned certificate did not match, droidcommand-AI is absent, or the connection was refused. */
  public static Reply notSent(String detail) {
    Reply r = new Reply();
    r.transport = "not_sent";
    r.state = "failed";
    r.code = "not_sent";
    r.detail = Redact.snippet(detail == null ? "" : detail, 300);
    return r;
  }

  /** Handed over, nothing came back: it may or may not have run. Never to be resent automatically. */
  public static Reply noReply(String detail) {
    Reply r = new Reply();
    r.transport = "no_reply";
    r.state = "unknown";
    r.code = "outcome_unknown";
    r.detail = Redact.snippet(detail == null ? "" : detail, 300);
    return r;
  }

  /** The job state a reply justifies, or null if it justifies none. Anything not listed here changes nothing. */
  public static Jobs.State stateFor(Reply r) {
    if ("no_reply".equals(r.transport)) return Jobs.State.UNKNOWN;
    if ("not_sent".equals(r.transport)) return Jobs.State.DRAFT;
    if (!r.parsed) return null;
    switch (r.state) {
      case "completed": return r.ok ? Jobs.State.COMPLETED : null; // a contradictory reply is never a completion
      case "running": return r.ok ? Jobs.State.RUNNING : null;
      case "pending_approval": return r.ok ? Jobs.State.AWAITING_APPROVAL : null;
      case "failed":
      case "refused": return Jobs.State.FAILED;
      case "unknown": return Jobs.State.UNKNOWN;
      default: return null;
    }
  }

  /** Applies a reply through the same backend-report path as every other backend. Returns whether anything changed. */
  public static boolean apply(Jobs jobs, long jobId, String expectedRequestId, Reply r) {
    // A reply droidcommand-AI produced must name this job's request; outcomes this app decided itself (not_sent, no_reply) carry none.
    if (r.transport.isEmpty() && (expectedRequestId == null || !expectedRequestId.equals(r.requestId))) return false;
    Jobs.State s = stateFor(r);
    if (s == null) return false;
    if (s == Jobs.State.DRAFT) {
      // Nothing left this app, so the job goes back to a draft the owner can send again. Only a job being sent can go back.
      Jobs.Job before = jobs.get(jobId);
      if (before == null || before.state != Jobs.State.SUBMITTING) return false;
      jobs.markNotSent(jobId, r.detail);
      return true;
    }
    if (s == Jobs.State.UNKNOWN) {
      Jobs.Job before = jobs.get(jobId);
      if (before == null || before.state.terminal() || before.state == Jobs.State.UNKNOWN) return false;
      jobs.markUnknown(jobId, r.detail.isEmpty() ? "droidcommand-AI could not confirm the outcome." : r.detail);
      return true;
    }
    Jobs.Remote remote = new Jobs.Remote();
    remote.id = expectedRequestId;
    remote.state = s == Jobs.State.AWAITING_APPROVAL ? "awaiting_approval" : s.name().toLowerCase(Locale.ROOT);
    remote.error = s == Jobs.State.FAILED ? (r.detail.isEmpty() ? r.code : r.detail) : "";
    if (s == Jobs.State.COMPLETED) remote.resultJson = r.resultJson.length() > 8000 ? "{}" : r.resultJson;
    remote.finished = System.currentTimeMillis();
    return jobs.applyRemote(jobId, remote);
  }

  /** Plain words for the owner about what a discovery (or any) reply says. Never claims more than the reply did. */
  public static String describe(Reply r) {
    if ("not_sent".equals(r.transport)) return "Not connected: " + (r.detail.isEmpty() ? "the request could not be sent" : r.detail) + "\nNothing was sent.";
    if ("no_reply".equals(r.transport)) return "droidcommand-AI did not reply in time. If a request was sent it may have run; it was not repeated.\n" + r.detail;
    if (!r.parsed) return "droidcommand-AI sent a reply this app could not read. Nothing was changed.";
    if ("not_paired".equals(r.code)) return "droidcommand-AI has not allowed VictorBots yet. Open droidcommand-AI, go to Connected apps, and allow VictorBots. Nothing ran.\n" + r.detail;
    if (r.incompatible()) return "droidcommand-AI speaks messaging versions " + r.minVersion + " to " + r.maxVersion + "; this app speaks " + MIN_VERSION + " to " + MAX_VERSION + ". Update the older app.";
    if (!r.ok) return "droidcommand-AI refused (" + (r.code.isEmpty() ? r.state : r.code) + "): " + r.detail;
    if (r.capabilities.isEmpty()) return "droidcommand-AI answered: " + (r.state.isEmpty() ? "ok" : r.state) + ".";
    int up = 0;
    StringBuilder down = new StringBuilder();
    for (Map.Entry<String, String> e : r.capabilities.entrySet()) {
      if ("available".equals(e.getValue())) up++;
      else down.append("\n• ").append(e.getKey().replace("communications.", "")).append(": ").append(Redact.snippet(e.getValue(), 120));
    }
    return "Connected. " + up + " of " + r.capabilities.size() + " messaging capabilities are available right now."
        + (down.length() == 0 ? "" : "\nNot available:" + down)
        + "\nAvailability is what droidcommand-AI reports; nothing has been sent.";
  }

  /**
   * Plain words for the answer to {@code communications.list_conversations}. Titles are the owner's own saved conversations,
   * shown only on this phone. Says what was read and that nothing was sent or changed.
   */
  public static String describeConversations(Reply r) {
    if (r.transport.length() > 0 || !r.parsed || !r.ok || !"completed".equals(r.state)) {
      if ("not_granted".equals(r.code))
        return "droidcommand-AI has not allowed VictorBots to read messages. In DroidCommand open Communications, choose Allow an app, pick VictorBots and tick the read permissions. Nothing was read.";
      return describe(r);
    }
    try {
      JSONObject res = new JSONObject(r.resultJson);
      JSONArray list = res.optJSONArray("conversations");
      if (list == null) return "droidcommand-AI answered, but not with a conversation list. Nothing was changed.";
      if (list.length() == 0)
        return "No conversation is shared with the assistant yet. In VictorSMS open a saved conversation and switch on \"Let my assistant read this conversation\". Read only: nothing was sent or changed.";
      StringBuilder b = new StringBuilder(list.length() + " conversation" + (list.length() == 1 ? "" : "s") + " shared with the assistant:");
      for (int i = 0; i < list.length() && i < 20; i++) {
        JSONObject c = list.getJSONObject(i);
        String title = c.optString("title", "").trim();
        b.append("\n• ").append(title.isEmpty() ? "(no title)" : Redact.snippet(title, 60)).append(" · ").append(c.optLong("message_count", 0)).append(" messages");
      }
      return b.append("\nRead only: nothing was sent or changed.").toString();
    } catch (JSONException e) {
      return "droidcommand-AI answered, but the list could not be read. Nothing was changed.";
    }
  }

  private CommsCodec() {}
}
