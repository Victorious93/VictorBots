package com.victorious.bots;

import java.util.List;
import org.json.JSONObject;

/**
 * The only path from VictorBots to an inference backend. Chat, routing, memory extraction, summaries,
 * skills, titles, routines, team turns, diagnostics and fallback all call {@link #run}; nothing else
 * constructs a network request for model text. Policy is read from owner settings here and nowhere
 * else, then every call (allowed or refused) is recorded in the usage ledger with its destination.
 */
public final class Gateway {
  public interface Transport {
    Provider.Reply chat(Provider.Cfg cfg, List<Rules.Turn> turns, boolean stream, Provider.Sink sink, Provider.Cancel cancel)
        throws ProviderError;
  }

  public static final Transport LIVE = Provider::chat;

  /** Thrown before any network activity when policy, configuration or credentials forbid the call. */
  public static final class Denied extends RuntimeException {
    public Denied(String m) {
      super(m);
    }
  }

  public static final class Req {
    public Policy.Purpose purpose = Policy.Purpose.CHAT;
    public long botId;
    /** Use this profile instead of the bot's (only for explicit owner-chosen fallback). */
    public long profileOverride;
    public List<Rules.Turn> turns;
    public boolean stream;
    public Provider.Sink sink;
    public Provider.Cancel cancel;
    /** True only after the owner accepted a dialog naming this destination for this one request. */
    public boolean ownerConfirmed;
    public String modelOverride = "";
  }

  public static final class Plan {
    public Profile profile;
    public String model;
    public Policy.Mode mode;
    public Policy.Decision decision;
    public String describe() {
      return profile == null ? "no provider configured" : profile.describe() + " · " + (model.isEmpty() ? "no model set" : model);
    }
  }

  public static final class Result {
    public Provider.Reply reply;
    public Plan plan;
  }

  private final Db db;
  private final Profiles profiles;
  private final Secrets secrets;
  private final Transport transport;

  public Gateway(Db db, Profiles profiles, Secrets secrets, Transport transport) {
    this.db = db;
    this.profiles = profiles;
    this.secrets = secrets;
    this.transport = transport;
  }

  /** Resolves destination and policy without any network activity. Safe to call for UI previews. */
  public Plan plan(long botId, long profileOverride, Policy.Purpose purpose, boolean ownerConfirmed, String modelOverride) {
    Plan p = new Plan();
    JSONObject bot = botId == 0 ? null : db.one("SELECT profile,model,policy FROM bots WHERE id=?", botId);
    long pid = profileOverride;
    if (pid == 0 && bot != null) pid = bot.optLong("profile", 0);
    p.profile = pid == 0 ? null : profiles.get(pid);
    if (p.profile == null) p.profile = profiles.defaultProfile();
    String model = modelOverride == null ? "" : modelOverride;
    if (model.isEmpty() && bot != null && profileOverride == 0 && p.profile != null && bot.optLong("profile", 0) == p.profile.id)
      model = bot.optString("model", "");
    if (model.isEmpty() && p.profile != null) model = p.profile.model;
    p.model = model;
    p.mode = Policy.effective(profiles.globalMode(), bot == null ? "" : bot.optString("policy", ""));
    p.decision = p.profile == null
        ? new Policy.Decision(false, "No provider profile is configured.")
        : Policy.decide(p.mode, p.profile.kind, purpose, ownerConfirmed);
    return p;
  }

  public Result run(Req r) throws ProviderError {
    Plan plan = plan(r.botId, r.profileOverride, r.purpose, r.ownerConfirmed, r.modelOverride);
    if (!plan.decision.allowed) {
      log(r, plan, null, 0, 0, "policy: " + plan.decision.reason, false);
      throw new Denied(plan.decision.reason);
    }
    Profile p = plan.profile;
    if (p.kind == Policy.Kind.DROIDCOMMAND) {
      log(r, plan, null, 0, 0, "needs on-screen consent", false);
      throw new Denied("DroidCommand answers only after you tap Allow in DroidCommand, so it cannot be used for unattended or background work.");
    }
    if (plan.model.isEmpty()) throw new Denied("No model is set for " + p.name + ". Choose or type a model in the profile or on the bot.");
    String origin = Hosts.origin(p.base);
    String key;
    try {
      key = secrets.read(p.id, origin);
    } catch (SecurityException e) {
      log(r, plan, null, 0, 0, "key bound to a different endpoint", false);
      throw new Denied("The stored key belongs to a different endpoint. Enter the key again for this endpoint.");
    } catch (Exception e) {
      log(r, plan, null, 0, 0, "key unreadable", false);
      throw new Denied("The stored key could not be decrypted. Enter it again.");
    }
    if (Profile.requiresKey(p.kind) && key.isEmpty()) {
      log(r, plan, null, 0, 0, "PC profile has no token", false);
      throw new Denied("PC profile “" + p.name + "” has no access token. VictorBots will not call a PC endpoint anonymously.");
    }
    Provider.Cfg cfg = p.cfg(key);
    cfg.model = plan.model;
    int chars = 0;
    for (Rules.Turn t : r.turns) chars += t.content.length();
    long t0 = System.currentTimeMillis();
    try {
      Provider.Reply rep = transport.chat(cfg, r.turns, r.stream && p.streaming(), r.sink, r.cancel);
      log(r, plan, rep, chars, System.currentTimeMillis() - t0, "", true);
      if (rep.usage.in != null) profiles.calibrate(p.id, chars, rep.usage.in);
      Result res = new Result();
      res.reply = rep;
      res.plan = plan;
      return res;
    } catch (ProviderError e) {
      log(r, plan, null, chars, System.currentTimeMillis() - t0, e.category.name(), false);
      throw e;
    }
  }

  private void log(Req r, Plan plan, Provider.Reply rep, int chars, long ms, String error, boolean ok) {
    Profile p = plan.profile;
    String host = p == null || p.base.isEmpty() ? (p == null ? "-" : "droidcommand") : Hosts.host(p.base);
    db.insert(
        "INSERT INTO usage(ts,bot,profile,purpose,host,kind,requested_model,reported_model,in_tokens,out_tokens,latency_ms,ttft_ms,ok,error) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        System.currentTimeMillis(),
        r.botId == 0 ? null : r.botId,
        p == null ? null : p.id,
        r.purpose.name(),
        host,
        p == null ? "NONE" : p.kind.name(),
        plan.model == null ? "" : plan.model,
        rep == null ? "" : rep.modelReported,
        rep == null ? null : rep.usage.in,
        rep == null ? null : rep.usage.out,
        rep == null ? null : (Object) rep.latencyMs,
        rep == null || rep.firstTokenMs < 0 ? null : (Object) rep.firstTokenMs,
        ok ? 1 : 0,
        error);
  }

  /** Records a content-free probe (reachability / model list) in the same ledger. */
  public void logProbe(Profile p, Policy.Purpose purpose, boolean ok, String error) {
    db.insert(
        "INSERT INTO usage(ts,bot,profile,purpose,host,kind,requested_model,reported_model,ok,error) VALUES(?,?,?,?,?,?,?,?,?,?)",
        System.currentTimeMillis(), null, p.id, purpose.name(), p.base.isEmpty() ? "-" : Hosts.host(p.base), p.kind.name(), p.model, "", ok ? 1 : 0, error);
  }

  /** Records a DroidCommand request (made through the consent screen) in the same ledger. */
  public void logDroidCommand(long botId, Policy.Purpose purpose, boolean ok, String error) {
    db.insert(
        "INSERT INTO usage(ts,bot,profile,purpose,host,kind,requested_model,reported_model,ok,error) VALUES(?,?,?,?,?,?,?,?,?,?)",
        System.currentTimeMillis(),
        botId == 0 ? null : botId,
        null,
        purpose.name(),
        "droidcommand",
        Policy.Kind.DROIDCOMMAND.name(),
        "",
        "",
        ok ? 1 : 0,
        error);
  }
}
