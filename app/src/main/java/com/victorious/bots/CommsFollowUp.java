package com.victorious.bots;

import java.util.Map;
import java.util.TreeMap;

/**
 * Victorious Framework: collects the outcome of a request droidcommand-AI did not finish in its first answer. COMPANION_COMMS
 * answers each request once; "pending_approval" means re-send the SAME request id and arguments with a fresh nonce once the
 * owner decided, and "running" means ask for the receipt. Without this, tapping again started an unrelated new request and the
 * first one's outcome was never seen. Pure Java; nothing here retries on its own: each step is one owner tap.
 */
public final class CommsFollowUp {
  private String key, requestId, state = "";

  /** One owner-initiated step: a new call, the same call again (pending approval), or a receipt (running). */
  public CommsCodec.Reply ask(CommsClient client, String capability, Map<String, String> args, String reason) {
    String k = capability + "|" + new TreeMap<>(args == null ? new TreeMap<String, String>() : args);
    String id;
    boolean receipt;
    synchronized (this) {
      boolean same = k.equals(key);
      receipt = same && "running".equals(state);
      id = same && ("pending_approval".equals(state) || receipt) ? requestId : client.newRequestId();
    }
    CommsCodec.Reply r = receipt ? client.receipt(id) : client.call(id, capability, args, null, null, reason);
    synchronized (this) {
      if ("not_sent".equals(r.transport)) {
        // Nothing left this app (busy, not installed...): whatever was waiting is still waiting, unchanged.
      } else if (r.transport.isEmpty() && r.parsed && r.ok && ("pending_approval".equals(r.state) || "running".equals(r.state))) {
        key = k;
        requestId = id;
        state = r.state;
      } else {
        key = null;
        requestId = null;
        state = "";
      }
    }
    return r;
  }

  /** The request id still waiting for an outcome, or null. */
  public synchronized String waitingId() {
    return state.isEmpty() ? null : requestId;
  }
}
