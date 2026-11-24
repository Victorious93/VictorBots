package com.victorious.bots;

import java.security.SecureRandom;
import java.util.Map;

/**
 * Victorious Framework: calls droidcommand-AI's COMPANION_COMMS v1 through an injected {@link Transport}. Pure Java, so the
 * rules below are tested without a phone. The Android transport is {@link MessengerComms}.
 *
 * <ul>
 *   <li>One send per call. This class never retries and never re-sends: a request that may have run is reported as
 *       {@code no_reply} (outcome unknown), not repeated.
 *   <li>A fresh nonce for EVERY send, including a second call that reuses a request id.
 *   <li>It decides nothing: approvals, grants and the real outcome belong to droidcommand-AI. This only encodes, sends and
 *       decodes, and says plainly when nothing was sent versus when there was no answer.
 * </ul>
 */
public final class CommsClient {
  /** Hands one request to droidcommand-AI and returns its raw reply. */
  public interface Transport {
    /**
     * @throws NotSentException when nothing left this app (certificate mismatch, not installed, refused connection)
     * @throws NoReplyException when the request was handed over and no reply came
     */
    String exchange(String requestJson, long timeoutMs) throws Exception;
  }

  public static final class NotSentException extends Exception {
    public NotSentException(String message) {
      super(message);
    }

    public NotSentException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static final class NoReplyException extends Exception {
    public NoReplyException(String message) {
      super(message);
    }
  }

  public interface Clock {
    long now();
  }

  /** Long enough for droidcommand-AI to ask a paired app a question first (its own bridge timeout is 10 s). */
  public static final long TIMEOUT_MS = 30_000;

  private final Transport transport;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public CommsClient(Transport transport) {
    this(transport, System::currentTimeMillis);
  }

  public CommsClient(Transport transport, Clock clock) {
    this.transport = transport;
    this.clock = clock;
  }

  /** What droidcommand-AI can do right now, as it reports it. Sends nothing to anyone. */
  public CommsCodec.Reply discover() {
    String id = newRequestId();
    try {
      return run(id, CommsCodec.discover(id));
    } catch (Exception e) {
      return CommsCodec.notSent("The request could not be built.");
    }
  }

  /** The recorded outcome of an earlier request id. Read-only. */
  public CommsCodec.Reply receipt(String requestId) {
    try {
      return run(requestId, CommsCodec.receipt(requestId));
    } catch (Exception e) {
      return CommsCodec.notSent("The request could not be built.");
    }
  }

  /** One call. [mode] is only a claim; droidcommand-AI honours "auto" solely for an owner-granted bot. */
  public CommsCodec.Reply call(String requestId, String capability, Map<String, String> args, String botId, String mode, String reason) {
    String json;
    try {
      json = CommsCodec.call(requestId, newNonce(), clock.now(), capability, args, botId, mode, reason);
    } catch (Exception e) {
      // Refused before anything was built or sent: a bad id, a bad capability name.
      return CommsCodec.notSent(e.getMessage());
    }
    return run(requestId, json);
  }

  private CommsCodec.Reply run(String expectedId, String json) {
    String raw;
    try {
      raw = transport.exchange(json, TIMEOUT_MS);
    } catch (NotSentException e) {
      return CommsCodec.notSent(e.getMessage());
    } catch (Exception e) {
      // Anything else happened after the request may have been handed over.
      return CommsCodec.noReply(e instanceof NoReplyException ? e.getMessage() : "The connection failed after the request was sent.");
    }
    CommsCodec.Reply r = CommsCodec.parse(raw);
    if (r.parsed && !r.requestId.isEmpty() && !r.requestId.equals(expectedId)) {
      // Someone else's answer is never applied to this request.
      return new CommsCodec.Reply();
    }
    return r;
  }

  String newRequestId() {
    byte[] b = new byte[12];
    random.nextBytes(b);
    return "vb-" + hex(b);
  }

  String newNonce() {
    byte[] b = new byte[16];
    random.nextBytes(b);
    return hex(b);
  }

  private static String hex(byte[] b) {
    StringBuilder s = new StringBuilder(b.length * 2);
    for (byte v : b) s.append(String.format(java.util.Locale.ROOT, "%02x", v));
    return s.toString();
  }
}
