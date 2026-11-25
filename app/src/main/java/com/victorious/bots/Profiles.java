package com.victorious.bots;

import java.util.*;
import org.json.*;

/** Persistence and validation for provider profiles, plus the owner's default selection. */
public final class Profiles {
  public static final String DEFAULT_KEY = "default_profile", GLOBAL_POLICY = "policy_global", FALLBACK_KEY = "fallback_profile";
  private final Db db;
  private final KV kv;
  private final Secrets secrets;

  public Profiles(Db db, KV kv, Secrets secrets) {
    this.db = db;
    this.kv = kv;
    this.secrets = secrets;
  }

  public List<Profile> all() {
    List<Profile> out = new ArrayList<>();
    for (JSONObject r : db.rows("SELECT * FROM profiles ORDER BY id")) out.add(Profile.from(r));
    return out;
  }

  public Profile get(long id) {
    JSONObject r = db.one("SELECT * FROM profiles WHERE id=?", id);
    return r == null ? null : Profile.from(r);
  }

  public Profile byKind(Policy.Kind k) {
    JSONObject r = db.one("SELECT * FROM profiles WHERE kind=? ORDER BY id LIMIT 1", k.name());
    return r == null ? null : Profile.from(r);
  }

  public Profile defaultProfile() {
    long id = kv.num(DEFAULT_KEY, 0);
    Profile p = id == 0 ? null : get(id);
    if (p != null) return p;
    List<Profile> all = all();
    return all.isEmpty() ? null : all.get(0);
  }

  public void setDefault(long id) {
    if (get(id) == null) throw new IllegalArgumentException("Unknown profile.");
    kv.put(DEFAULT_KEY, "" + id);
  }

  /**
   * The PC profile used for jobs, routines and connectors: the first PC profile that has a runner,
   * else the first PC profile (so the UI can say its runner is not configured). With only ordinary PC
   * profiles this is exactly {@link #byKind}.
   */
  public Profile runnerProfile() {
    Profile first = null;
    for (Profile p : all()) {
      if (p.kind != Policy.Kind.PC) continue;
      if (first == null) first = p;
      if (p.runner() != null) return p;
    }
    return first;
  }

  /** A runner is configured and its token is stored under the current binding. */
  public boolean runnerReady(Profile p) {
    Profile.Runner r = p.runner();
    return r != null && secrets.usable(r.secretId, r.binding);
  }

  public Policy.Mode globalMode() {
    return Policy.Mode.parse(kv.get(GLOBAL_POLICY, Policy.Mode.ALLOW_CLOUD.id), Policy.Mode.ALLOW_CLOUD);
  }

  public void setGlobalMode(Policy.Mode m) {
    if (!m.usable) throw new IllegalArgumentException(m.explanation);
    kv.put(GLOBAL_POLICY, m.id);
  }

  /** Validates and stores a profile. A changed endpoint origin drops the stored key for that profile. */
  public long save(long id, String name, Policy.Kind kind, String baseRaw, String model, String pin, JSONObject caps) {
    Rules.length(name, 60, "Profile name");
    if (model != null && model.length() > 160) throw new IllegalArgumentException("Model identifier is too long.");
    String base = "";
    String pinNorm = "";
    if (kind != Policy.Kind.DROIDCOMMAND) {
      base = Hosts.base(baseRaw);
      if (pin != null && !pin.trim().isEmpty()) pinNorm = Rules.pin(pin);
      if (kind == Policy.Kind.CLOUD && !pinNorm.isEmpty())
        throw new IllegalArgumentException("Certificate pinning is for private PC certificates. Cloud providers use the platform trust store.");
      if (caps != null && VictorLinux.PRESET.equals(caps.optString("preset")) && (kind != Policy.Kind.PC || pinNorm.isEmpty()))
        throw new IllegalArgumentException("A VictorLinux profile must be a PC profile with a pinned certificate.");
    }
    JSONObject old = id == 0 ? null : db.one("SELECT * FROM profiles WHERE id=?", id);
    String other = "SELECT COUNT(*) n FROM profiles WHERE name=? COLLATE NOCASE AND id!=?";
    if (db.count(other, name.trim(), id) > 0) throw new IllegalArgumentException("A profile with that name already exists.");
    String capsJson = caps == null ? "{}" : caps.toString();
    long rid = id;
    if (id == 0) {
      rid = db.insert("INSERT INTO profiles(name,kind,base,model,pin,caps,tested,created) VALUES(?,?,?,?,?,?,'{}',?)", name.trim(), kind.name(), base, model == null ? "" : model.trim(), pinNorm, capsJson, System.currentTimeMillis());
    } else {
      if (old == null) throw new IllegalArgumentException("Profile no longer exists.");
      if (!old.optString("kind").equals(kind.name())) throw new IllegalArgumentException("A profile's type cannot be changed. Create a new profile.");
      String oldBase = old.optString("base");
      boolean originChanged = !oldBase.isEmpty() && !base.isEmpty() && !Hosts.origin(oldBase).equals(Hosts.origin(base));
      // A PC key is bound to origin + pin: a new pin is a new certificate identity, so the old key must not follow it.
      boolean bindingChanged = originChanged || (!oldBase.isEmpty() && !base.isEmpty() && !Profile.binding(kind, oldBase, old.optString("pin")).equals(Profile.binding(kind, base, pinNorm)));
      // Server identity changed: previously learned facts and the old key no longer apply.
      String tested = originChanged ? "{}" : old.optString("tested", "{}");
      Profile before = Profile.from(old);
      db.update("UPDATE profiles SET name=?,base=?,model=?,pin=?,caps=?,tested=? WHERE id=?", name.trim(), base, model == null ? "" : model.trim(), pinNorm, capsJson, tested, id);
      if (bindingChanged) secrets.remove(id);
      Profile.Runner was = before.runner(), now = get(id).runner();
      if (was != null && was.separate && (now == null || !now.separate || !now.binding.equals(was.binding))) secrets.remove(was.secretId);
    }
    return rid;
  }

  /**
   * Saves the optional VictorLinux preset: a PC profile that must have a token and a certificate pin.
   * Everything is validated before anything is written. A blank token keeps the stored one only when
   * it is still readable for the new endpoint+pin; otherwise the save is refused.
   *
   * @return the profile id
   */
  public long saveVictorLinux(long id, String name, String hostRaw, String portRaw, String model, String pinRaw, String token, String runnerUrlRaw, String runnerPinRaw, String runnerToken, JSONObject caps) {
    JSONObject old = id == 0 ? null : db.one("SELECT * FROM profiles WHERE id=?", id);
    if (id != 0 && old == null) throw new IllegalArgumentException("Profile no longer exists.");
    if (old != null && !(old.optString("kind").equals(Policy.Kind.PC.name()) && new Profile(0, "", Policy.Kind.PC, "", "", "", parseCaps(old.optString("caps")), new JSONObject()).isVictorLinux()))
      throw new IllegalArgumentException("This profile was not created from the VictorLinux preset.");
    String base = VictorLinux.baseUrl(hostRaw, portRaw);
    String pinNorm = pinRaw == null || pinRaw.trim().isEmpty() ? "" : Rules.pin(pinRaw);
    String binding = Profile.binding(Policy.Kind.PC, base, pinNorm);
    boolean haveToken = token != null && !token.isEmpty() || (id != 0 && secrets.usable(id, binding));
    if (!haveToken && id != 0 && secrets.has(id))
      throw new IllegalArgumentException("The address or certificate pin changed (or the stored token uses an older format), so the stored token is not reused. Enter the access token again.");
    String runnerBase = runnerUrlRaw == null || runnerUrlRaw.trim().isEmpty() ? "" : Hosts.base(runnerUrlRaw);
    String runnerPinNorm = runnerPinRaw == null || runnerPinRaw.trim().isEmpty() ? "" : Rules.pin(runnerPinRaw);
    boolean separate = !runnerBase.isEmpty() && !Hosts.origin(runnerBase).equals(Hosts.origin(base));
    String runnerBinding = separate ? Hosts.origin(runnerBase) + (runnerPinNorm.isEmpty() ? "" : "|pin=" + runnerPinNorm) : "";
    boolean haveRunnerToken = runnerToken != null && !runnerToken.isEmpty() || (id != 0 && separate && secrets.usable(-id, runnerBinding));
    VictorLinux.Spec spec = VictorLinux.validate(hostRaw, portRaw, model, pinRaw, haveToken, runnerUrlRaw, runnerPinRaw, haveRunnerToken);
    JSONObject nc = new JSONObject();
    try {
      if (caps != null) for (java.util.Iterator<String> it = caps.keys(); it.hasNext(); ) {
        String k = it.next();
        nc.put(k, caps.get(k));
      }
      nc.remove("runner_url");
      nc.remove("runner_pin");
      nc.put("preset", VictorLinux.PRESET);
      if (!spec.runnerUrl.isEmpty()) nc.put("runner_url", spec.runnerUrl);
      if (!spec.runnerPin.isEmpty()) nc.put("runner_pin", spec.runnerPin);
      if (!nc.has("billing")) nc.put("billing", "self_hosted");
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    long rid = save(id, name, Policy.Kind.PC, spec.base, spec.model, spec.pin, nc);
    try {
      if (token != null && !token.isEmpty()) secrets.save(rid, binding, token);
      if (separate && runnerToken != null && !runnerToken.isEmpty()) secrets.save(-rid, runnerBinding, runnerToken);
    } catch (Exception e) {
      if (id == 0) delete(rid);
      throw new IllegalStateException("The token could not be stored securely, so the profile was not kept: " + e.getMessage());
    }
    return rid;
  }

  private static JSONObject parseCaps(String s) {
    try {
      return new JSONObject(s == null || s.isEmpty() ? "{}" : s);
    } catch (JSONException e) {
      return new JSONObject();
    }
  }

  public void delete(long id) {
    Profile p = get(id);
    if (p == null) return;
    if (p.kind == Policy.Kind.DROIDCOMMAND) throw new IllegalArgumentException("The DroidCommand profile is built in.");
    db.tx(() -> {
      db.update("UPDATE bots SET profile=NULL,model='' WHERE profile=?", id);
      db.update("UPDATE chats SET profile=NULL WHERE profile=?", id);
      db.update("DELETE FROM profiles WHERE id=?", id);
      return null;
    });
    secrets.remove(id);
    secrets.remove(-id);
    if (kv.num(DEFAULT_KEY, 0) == id) kv.put(DEFAULT_KEY, "0");
    if (kv.num(FALLBACK_KEY, 0) == id) kv.put(FALLBACK_KEY, "0");
  }

  /** Records a learned fact (advertisement or test result) without touching what the owner configured. */
  public void recordTested(long id, String key, JSONObject value) {
    Profile p = get(id);
    if (p == null) return;
    try {
      p.tested.put(key, value);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    db.update("UPDATE profiles SET tested=? WHERE id=?", p.tested.toString(), id);
  }

  public static JSONObject result(boolean ok, String note) {
    try {
      return new JSONObject().put("ok", ok).put("at", System.currentTimeMillis()).put("note", note == null ? "" : note);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Folds one measured (chars, reported prompt tokens) pair into the profile's chars-per-token. */
  public void calibrate(long id, int promptChars, long reportedPromptTokens) {
    if (reportedPromptTokens < 8 || promptChars < 32) return;
    Profile p = get(id);
    if (p == null) return;
    double sample = promptChars / (double) reportedPromptTokens;
    if (sample < 1.0 || sample > 12.0) return;
    JSONObject c = p.tested.optJSONObject("cpt");
    int n = c == null ? 0 : c.optInt("n");
    double v = c == null ? sample : (c.optDouble("v") * Math.min(n, 19) + sample) / (Math.min(n, 19) + 1);
    try {
      recordTested(id, "cpt", new JSONObject().put("v", v).put("n", n + 1));
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }
}
