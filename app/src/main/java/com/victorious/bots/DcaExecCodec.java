package com.victorious.bots;

import java.util.*;
import org.json.*;

/**
 * Pure encoding/decoding for droidcommand-AI's COMPANION_EXEC v1 (a separate contract from the
 * text-only COMPANION_ASSIST). VictorBots never decides an outcome: it only records the receipt
 * DroidCommand returns. The owner's Allow tap happens inside DroidCommand; nothing here approves.
 */
public final class DcaExecCodec {
  public static final String ACTION = "ai.droidcommand.action.COMPANION_EXEC";
  public static final int VERSION = 1;
  public static final long TTL_MS = 120_000;

  public static String discover(String requestId) throws JSONException {
    return new JSONObject().put("v", VERSION).put("task", "discover").put("request_id", requestId).toString();
  }

  public static String receipt(String requestId) throws JSONException {
    return new JSONObject().put("v", VERSION).put("task", "receipt").put("request_id", requestId).toString();
  }

  public static String execute(String requestId, String capability, JSONObject args, long nowMs, String reason) throws JSONException {
    if (!requestId.matches("[A-Za-z0-9_-]{8,64}")) throw new IllegalArgumentException("Bad request id.");
    return new JSONObject().put("v", VERSION).put("task", "execute").put("request_id", requestId).put("capability", capability).put("args", args == null ? new JSONObject() : args).put("expires_at", nowMs + TTL_MS).put("reason", reason == null ? "" : reason.substring(0, Math.min(200, reason.length()))).toString();
  }

  public static final class Reply {
    public boolean ok, known;
    public String error = "", outcome = "", detail = "", output = "", requestId = "";
    public List<String> capabilities = new ArrayList<>();
  }

  public static Reply parse(String raw) {
    Reply r = new Reply();
    if (raw == null || raw.length() > 600000) {
      r.error = "Missing or oversized DroidCommand reply.";
      return r;
    }
    try {
      JSONObject o = new JSONObject(raw);
      if (o.optInt("v", -1) != VERSION || !(o.opt("ok") instanceof Boolean)) {
        r.error = "Unsupported DroidCommand reply.";
        return r;
      }
      r.ok = o.getBoolean("ok");
      if (!r.ok) {
        r.error = Redact.snippet(o.optString("error", "DroidCommand refused the request."), 300);
        return r;
      }
      r.known = o.optBoolean("known", o.has("outcome"));
      r.outcome = o.optString("outcome", "").toLowerCase(Locale.ROOT);
      r.detail = Redact.snippet(o.optString("detail"), 300);
      r.output = Redact.text(o.optString("output"));
      r.requestId = o.optString("request_id");
      JSONArray caps = o.optJSONArray("capabilities");
      if (caps != null) for (int i = 0; i < caps.length(); i++) r.capabilities.add(caps.getJSONObject(i).optString("id"));
    } catch (JSONException e) {
      r.ok = false;
      r.error = "DroidCommand sent a malformed reply.";
    }
    return r;
  }

  /**
   * Maps a DroidCommand receipt to a job state. Only "executed" becomes COMPLETED; every other
   * outcome is terminal and honest. Unknown strings leave the job unchanged (null).
   */
  public static Jobs.State stateFor(String outcome) {
    switch (outcome) {
      case "executed": return Jobs.State.COMPLETED;
      case "denied":
      case "expired": return Jobs.State.CANCELLED;
      case "failed":
      case "refused": return Jobs.State.FAILED;
      default: return null;
    }
  }

  /** Applies a receipt through the same backend-report path the PC runner uses. */
  public static boolean apply(Jobs jobs, long jobId, Reply r) {
    Jobs.State s = stateFor(r.outcome);
    if (!r.ok || !r.known || s == null) return false;
    Jobs.Remote remote = new Jobs.Remote();
    remote.id = r.requestId.isEmpty() ? "dca" : r.requestId;
    remote.state = s.name().toLowerCase(Locale.ROOT);
    remote.error = s == Jobs.State.COMPLETED ? "" : r.detail;
    if (s == Jobs.State.COMPLETED) {
      try { remote.resultJson = new JSONObject().put("output", r.output.length() > 8000 ? r.output.substring(0, 8000) : r.output).toString(); } catch (JSONException ignored) { }
    }
    remote.finished = System.currentTimeMillis();
    return jobs.applyRemote(jobId, remote);
  }

  private DcaExecCodec() {}
}
