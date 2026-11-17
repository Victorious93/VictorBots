package com.victorious.bots;

import java.util.*;
import org.json.*;

/**
 * Connection checks, kept as separate facts. "Saved", "host reachable", "credentials accepted",
 * "model listed" and "a real reply came back" are different claims and each is reported on its own.
 * Probes carry no chat content. Only {@link #liveTest} sends text, and only when the owner asks.
 */
public final class Diagnostics {
  public enum State {
    OK,
    FAILED,
    UNKNOWN,
    BLOCKED,
    NOT_RUN
  }

  public static final class Step {
    public final String name;
    public State state = State.NOT_RUN;
    public String detail = "Not checked yet.";

    Step(String n) {
      name = n;
    }

    void set(State s, String d) {
      state = s;
      detail = d;
    }
  }

  public static final class Report {
    public final Step saved = new Step("Configuration saved"),
        reachable = new Step("Host reachable"),
        auth = new Step("Credentials accepted"),
        model = new Step("Model available"),
        reply = new Step("Real reply received");
    public String destination = "";
    public String requestedModel = "", reportedModel = "";
    public Long inTokens, outTokens;
    public long latencyMs = -1, firstTokenMs = -1;
    public List<String> models = new ArrayList<>();
    public String note = "";

    public List<Step> steps() {
      return Arrays.asList(saved, reachable, auth, model, reply);
    }

    public String usageLine() {
      if (latencyMs < 0) return "";
      return "Tokens in/out: "
          + (inTokens == null ? "unknown" : inTokens)
          + " / "
          + (outTokens == null ? "unknown" : outTokens)
          + " (as reported by the server)  ·  total time "
          + latencyMs
          + " ms  ·  first token "
          + (firstTokenMs < 0 ? "not measured (not streamed)" : firstTokenMs + " ms");
    }
  }

  private final Db db;
  private final Profiles profiles;
  private final Secrets secrets;
  private final Gateway gateway;

  public Diagnostics(Db db, Profiles profiles, Secrets secrets, Gateway gateway) {
    this.db = db;
    this.profiles = profiles;
    this.secrets = secrets;
    this.gateway = gateway;
  }

  /** No network: only what is configured. */
  public Report passive(Profile p) {
    Report r = new Report();
    r.destination = destination(p);
    r.requestedModel = p.model;
    if (p.kind == Policy.Kind.DROIDCOMMAND) {
      r.saved.set(State.OK, "Built-in profile. DroidCommand AI owns its own model, credentials and consent.");
      r.reachable.set(State.UNKNOWN, "VictorBots cannot probe DroidCommand; only a consented reply proves the link.");
      r.auth.set(State.UNKNOWN, "Authorisation is DroidCommand's pairing and Allow tap.");
      r.model.set(State.UNKNOWN, "Not reported through COMPANION_ASSIST.");
      return r;
    }
    try {
      Hosts.base(p.base);
      boolean keyOk = !Profile.requiresKey(p.kind) || secrets.has(p.id);
      if (!keyOk) r.saved.set(State.FAILED, "Saved, but this PC profile has no access token. It will not be called anonymously.");
      else if (Profile.requiresKey(p.kind) && !secrets.usable(p.id, p.binding()))
        r.saved.set(State.FAILED, "Saved, but the stored token no longer matches this profile's address or certificate pin (or uses an older format). Edit the profile and enter it again.");
      else r.saved.set(State.OK, "Endpoint saved" + (secrets.has(p.id) ? ", key stored encrypted." : ", no key stored."));
    } catch (IllegalArgumentException e) {
      r.saved.set(State.FAILED, e.getMessage());
    }
    return r;
  }

  public static String destination(Profile p) {
    if (p.kind == Policy.Kind.DROIDCOMMAND) return "DroidCommand AI on this device (it decides where text goes)";
    return Hosts.host(p.base) + " (" + (p.kind == Policy.Kind.PC ? "your PC" : "cloud provider") + ")";
  }

  /** Reachability, credentials, model listing and (if present) Ollama capability discovery. No chat text. */
  public Report probe(Profile p) {
    Report r = passive(p);
    if (p.kind == Policy.Kind.DROIDCOMMAND || r.saved.state == State.FAILED && !r.saved.detail.contains("access token")) {
      return r;
    }
    Gateway.Plan plan = gateway.plan(0, p.id, Policy.Purpose.DIAGNOSTIC, true, "");
    if (!plan.decision.allowed) {
      for (Step s : Arrays.asList(r.reachable, r.auth, r.model, r.reply)) s.set(State.BLOCKED, plan.decision.reason);
      return r;
    }
    String key;
    try {
      key = secrets.read(p.id, p.binding());
    } catch (SecurityException e) {
      r.auth.set(State.FAILED, "The stored key does not match this profile's endpoint or certificate pin; enter it again.");
      return r;
    } catch (Exception e) {
      r.auth.set(State.FAILED, "The stored key could not be decrypted; enter it again.");
      return r;
    }
    if (Profile.requiresKey(p.kind) && key.isEmpty()) {
      r.reachable.set(State.NOT_RUN, "Not attempted: no access token.");
      return r;
    }
    Provider.Cfg cfg = p.cfg(key);
    cfg.connectMs = 8000;
    cfg.readMs = 15000;
    try {
      List<String> ids = Provider.models(cfg);
      r.models = ids;
      gateway.logProbe(p, Policy.Purpose.DIAGNOSTIC, true, "");
      r.reachable.set(State.OK, "TLS connected and the server answered.");
      r.auth.set(State.OK, key.isEmpty() ? "The server answered without credentials (none were sent)." : "The server accepted the credentials for the model list. (Chat may still use different limits.)");
      if (p.model.isEmpty()) r.model.set(State.UNKNOWN, ids.size() + " models listed. Choose one.");
      else if (ids.contains(p.model) || containsNormalized(ids, p.model)) r.model.set(State.OK, "“" + p.model + "” is in the server's model list.");
      else r.model.set(State.FAILED, "“" + p.model + "” is not in the server's model list (" + ids.size() + " listed).");
      profiles.recordTested(p.id, "models", new JSONObject().put("ok", true).put("count", ids.size()).put("at", System.currentTimeMillis()));
    } catch (ProviderError e) {
      gateway.logProbe(p, Policy.Purpose.DIAGNOSTIC, false, e.category.name());
      classify(r, e);
      try {
        profiles.recordTested(p.id, "models", new JSONObject().put("ok", false).put("count", 0).put("at", System.currentTimeMillis()));
      } catch (JSONException ignored) {
      }
    } catch (JSONException e) {
      r.note = "Could not record results.";
    }
    if (r.reachable.state == State.OK) discoverOllama(p, cfg, r);
    return r;
  }

  private static boolean containsNormalized(List<String> ids, String m) {
    for (String id : ids) if (id.equalsIgnoreCase(m) || id.equalsIgnoreCase(m + ":latest")) return true;
    return false;
  }

  private void discoverOllama(Profile p, Provider.Cfg cfg, Report r) {
    JSONObject info = Provider.ollamaInfo(cfg);
    if (info == null) {
      r.note = "This server does not report capabilities or context size; they stay configured/unknown.";
      return;
    }
    try {
      profiles.recordTested(p.id, "advertised", info.put("at", System.currentTimeMillis()));
      r.note = "Ollama " + info.optString("version") + " reports capabilities and the model's maximum context.";
    } catch (JSONException ignored) {
    }
  }

  private void classify(Report r, ProviderError e) {
    switch (e.category) {
      case UNREACHABLE:
      case TIMEOUT:
        r.reachable.set(State.FAILED, e.getMessage());
        break;
      case TLS:
        r.reachable.set(State.FAILED, e.getMessage());
        break;
      case AUTH:
        r.reachable.set(State.OK, "The server answered.");
        r.auth.set(State.FAILED, e.getMessage());
        break;
      case NOT_FOUND:
      case UNSUPPORTED:
        r.reachable.set(State.OK, "The server answered.");
        r.auth.set(State.UNKNOWN, "This server has no model list, so credentials are only proven by a real reply.");
        r.model.set(State.UNKNOWN, "Model discovery is not supported here. Type the model id manually.");
        break;
      default:
        r.reachable.set(State.OK, "The server answered (HTTP " + e.httpStatus + ").");
        r.auth.set(State.UNKNOWN, e.getMessage());
        r.model.set(State.UNKNOWN, "Could not read a model list.");
    }
  }

  /** Owner-initiated: sends one tiny prompt. Use the dialog text from {@link #liveTestWarning}. */
  public Report liveTest(Profile p, boolean stream) {
    Report r = probe(p);
    if (r.reachable.state == State.BLOCKED) return r;
    r.reply.set(State.NOT_RUN, "Not run.");
    if (r.saved.state == State.FAILED) return r;
    Gateway.Req q = new Gateway.Req();
    q.purpose = Policy.Purpose.DIAGNOSTIC;
    q.profileOverride = p.id;
    q.stream = stream;
    q.ownerConfirmed = true;
    q.turns = Arrays.asList(new Rules.Turn("system", "You are a connection test. Reply with the single word: ok"), new Rules.Turn("user", "ping"));
    try {
      Gateway.Result g = gateway.run(q);
      Provider.Reply rep = g.reply;
      r.requestedModel = g.plan.model;
      r.reportedModel = rep.modelReported;
      r.inTokens = rep.usage.in;
      r.outTokens = rep.usage.out;
      r.latencyMs = rep.latencyMs;
      r.firstTokenMs = rep.firstTokenMs;
      r.reply.set(State.OK, "Received " + rep.text.length() + " characters from " + Hosts.host(p.base) + ".");
      if (r.auth.state != State.OK) r.auth.set(State.OK, "The server accepted the credentials for a real chat request.");
      if (r.model.state != State.OK) r.model.set(State.OK, "The server answered a chat request for “" + g.plan.model + "”.");
      if (r.reachable.state != State.OK) r.reachable.set(State.OK, "A real request completed.");
      profiles.recordTested(p.id, "text", Profiles.result(true, "model " + (rep.modelReported.isEmpty() ? g.plan.model : rep.modelReported)));
      if (stream && rep.streamed) profiles.recordTested(p.id, "streaming", Profiles.result(true, rep.firstTokenMs >= 0 ? "first token " + rep.firstTokenMs + " ms" : ""));
      if (!p.reasoning().isEmpty()) profiles.recordTested(p.id, "reasoning", Profiles.result(true, "request with reasoning_effort was accepted"));
    } catch (Gateway.Denied d) {
      r.reply.set(State.BLOCKED, d.getMessage());
    } catch (ProviderError e) {
      String detail = e.category.label + ": " + e.getMessage();
      r.reply.set(State.FAILED, detail);
      try {
        profiles.recordTested(p.id, "text", Profiles.result(false, e.category.label));
        if (stream) profiles.recordTested(p.id, "streaming", Profiles.result(false, e.category.label));
        if (!p.reasoning().isEmpty() && e.category == ProviderError.Category.UNSUPPORTED)
          profiles.recordTested(p.id, "reasoning", Profiles.result(false, "rejected by the server"));
      } catch (RuntimeException ignored) {
      }
      if (e.category == ProviderError.Category.MODEL_UNAVAILABLE) r.model.set(State.FAILED, e.getMessage());
      if (e.category == ProviderError.Category.AUTH) r.auth.set(State.FAILED, e.getMessage());
    }
    return r;
  }

  /** Text for the confirmation dialog shown before a live test. */
  public static String liveTestWarning(Profile p, Policy.Mode mode) {
    return "This sends one short test message (about 25 tokens plus a one-word answer) to "
        + destination(p)
        + " using model “"
        + (p.model.isEmpty() ? "(none set)" : p.model)
        + "”.\n\n"
        + (p.kind == Policy.Kind.CLOUD ? "Your provider may bill this request. VictorBots does not know your plan, so it cannot say what it will cost.\n\n" : "")
        + "Active policy: "
        + mode.label
        + ".\nNo chat history, memory or files are sent.";
  }
}
