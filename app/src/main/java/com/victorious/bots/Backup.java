package com.victorious.bots;

import java.util.*;
import org.json.*;

/**
 * Versioned export and validated restore. Exports never contain provider keys, tokens, certificate
 * pins' private material, avatar images, local paths, job logs or usage. Imports are untrusted input:
 * they are size/shape validated, previewed, de-duplicated by stable ids, applied in one transaction and
 * integrity-checked. Imported data cannot change the owner's inference policy, credentials or default
 * provider, and imported routines arrive disabled.
 */
public final class Backup {
  public static final String FORMAT = "Victorious Framework VictorBots";
  public static final int VERSION = 2;
  public static final int MAX_BYTES = 20 * 1024 * 1024;
  static final int MAX_BOTS = 200, MAX_CHATS = 2000, MAX_MESSAGES = 100_000, MAX_MEMORIES = 5000, MAX_TASKS = 2000, MAX_SKILLS = 200, MAX_PROFILES = 50, MAX_ROUTINES = 200, MAX_BODY = 200_000;

  public enum OnConflict { SKIP, KEEP_BOTH }

  public static final class Preview {
    public int version;
    public final Map<String, int[]> counts = new LinkedHashMap<>(); // kind -> {new, same, conflict}
    public final List<String> warnings = new ArrayList<>(), errors = new ArrayList<>();

    int[] c(String k) {
      int[] v = counts.get(k);
      if (v == null) counts.put(k, v = new int[3]);
      return v;
    }

    public boolean ok() {
      return errors.isEmpty();
    }

    public String text() {
      StringBuilder b = new StringBuilder("Backup format version " + version + "\n");
      for (Map.Entry<String, int[]> e : counts.entrySet()) b.append("• ").append(e.getKey()).append(": ").append(e.getValue()[0]).append(" new, ").append(e.getValue()[1]).append(" already here, ").append(e.getValue()[2]).append(" differ\n");
      for (String w : warnings) b.append("⚠ ").append(w).append('\n');
      for (String w : errors) b.append("✗ ").append(w).append('\n');
      return b.toString();
    }
  }

  // ------------------------------------------------------------------ export
  public static JSONObject export(Db db) throws JSONException {
    JSONObject out = new JSONObject().put("format", FORMAT).put("version", VERSION).put("exported_at", System.currentTimeMillis());
    Map<Long, String> botUid = new HashMap<>(), chatUid = new HashMap<>(), profileName = new HashMap<>();
    JSONArray profiles = new JSONArray();
    for (JSONObject p : db.rows("SELECT * FROM profiles WHERE kind!='DROIDCOMMAND' ORDER BY id")) {
      profileName.put(p.optLong("id"), p.optString("name"));
      JSONObject caps = new JSONObject(p.optString("caps", "{}"));
      profiles.put(new JSONObject().put("name", p.optString("name")).put("kind", p.optString("kind")).put("base", p.optString("base")).put("model", p.optString("model")).put("pin", p.optString("pin")).put("caps", caps));
    }
    out.put("profiles", profiles);
    JSONArray bots = new JSONArray();
    for (JSONObject b : db.rows("SELECT * FROM bots ORDER BY id")) {
      botUid.put(b.optLong("id"), b.optString("uid"));
      JSONObject o = new JSONObject().put("uid", b.optString("uid")).put("name", b.optString("name")).put("description", b.optString("description")).put("instructions", b.optString("instructions")).put("avatar", b.optString("avatar"))
          .put("role_hint", b.optString("role_hint")).put("project", b.optString("project")).put("policy", b.optString("policy")).put("reasoning", b.optString("reasoning")).put("model", b.optString("model"));
      if (b.has("profile") && profileName.containsKey(b.optLong("profile"))) o.put("profile_name", profileName.get(b.optLong("profile")));
      bots.put(o);
    }
    out.put("bots", bots);
    JSONArray chats = new JSONArray();
    for (JSONObject c : db.rows("SELECT * FROM chats ORDER BY id")) {
      chatUid.put(c.optLong("id"), c.optString("uid"));
      JSONObject o = new JSONObject().put("uid", c.optString("uid")).put("title", c.optString("title")).put("created", c.optLong("created")).put("kind", c.optString("kind")).put("config", c.optString("config", "{}")).put("summary", c.optString("summary"));
      if (c.has("bot")) o.put("bot_uid", botUid.get(c.optLong("bot")));
      JSONArray mem = new JSONArray();
      for (JSONObject m : db.rows("SELECT bot,muted FROM chat_members WHERE chat=?", c.optLong("id"))) mem.put(new JSONObject().put("bot_uid", botUid.get(m.optLong("bot"))).put("muted", m.optInt("muted")));
      o.put("members", mem);
      chats.put(o);
    }
    out.put("chats", chats);
    JSONArray msgs = new JSONArray();
    for (JSONObject m : db.rows("SELECT * FROM messages WHERE status IN ('done','error') ORDER BY id")) {
      JSONObject o = new JSONObject().put("chat_uid", chatUid.get(m.optLong("chat"))).put("role", m.optString("role")).put("body", m.optString("body")).put("status", m.optString("status")).put("created", m.optLong("created")).put("kind", m.optString("kind", "text")).put("meta", m.optString("meta", "{}"));
      if (m.optLong("author") != 0) o.put("author_uid", botUid.get(m.optLong("author")));
      msgs.put(o);
    }
    out.put("messages", msgs);
    JSONArray mems = new JSONArray();
    for (JSONObject m : db.rows("SELECT * FROM memories WHERE status!='dismissed' ORDER BY id")) {
      JSONObject o = new JSONObject().put("uid", m.optString("uid")).put("scope", m.optString("scope")).put("project", m.optString("project")).put("body", m.optString("body")).put("origin", m.optString("origin")).put("status", m.optString("status"))
          .put("source_label", m.optString("source_label")).put("pinned", m.optInt("pinned")).put("created", m.optLong("created")).put("updated", m.optLong("updated"));
      if (m.has("bot")) o.put("bot_uid", botUid.get(m.optLong("bot")));
      mems.put(o);
    }
    out.put("memories", mems);
    JSONArray tasks = new JSONArray();
    for (JSONObject t : db.rows("SELECT * FROM tasks ORDER BY id")) tasks.put(new JSONObject().put("uid", t.optString("uid")).put("title", t.optString("title")).put("body", t.optString("body")).put("status", t.optString("status")).put("created", t.optLong("created")));
    out.put("tasks", tasks);
    JSONArray skills = new JSONArray();
    for (JSONObject s : db.rows("SELECT * FROM skills WHERE builtin=0 ORDER BY id")) skills.put(new JSONObject().put("uid", s.optString("uid")).put("name", s.optString("name")).put("description", s.optString("description")).put("version", s.optInt("version")).put("body", s.optString("body")).put("requires", s.optString("requires")));
    out.put("skills", skills);
    JSONArray routines = new JSONArray();
    for (JSONObject r : db.rows("SELECT * FROM routines ORDER BY id")) routines.put(new JSONObject().put("uid", r.optString("uid")).put("name", r.optString("name")).put("bot_uid", botUid.get(r.optLong("bot"))).put("target", r.optString("target")).put("instruction", r.optString("instruction")).put("tool", r.optString("tool")).put("args", r.optString("args")).put("tz", r.optString("tz")).put("schedule", r.optString("schedule")).put("overlap", r.optString("overlap")).put("missed", r.optString("missed")));
    out.put("routines", routines);
    out.put("excluded", new JSONArray(new String[] {"API keys and tokens", "avatar images", "job logs and artifacts", "usage ledger", "owner policy and default provider"}));
    return out;
  }

  // ------------------------------------------------------------------ validation
  private static String str(JSONObject o, String k, int max, boolean required, List<String> errs, String where) {
    Object v = o.opt(k);
    if (v == null || v == JSONObject.NULL) {
      if (required) errs.add(where + ": missing " + k);
      return "";
    }
    if (!(v instanceof String)) {
      errs.add(where + ": " + k + " must be text");
      return "";
    }
    String s = (String) v;
    if (s.length() > max) {
      errs.add(where + ": " + k + " is longer than " + max + " characters");
      return "";
    }
    if (s.indexOf('\u0000') >= 0) {
      errs.add(where + ": " + k + " contains a NUL character");
      return "";
    }
    return s;
  }

  private static boolean uidOk(String u) {
    return u.matches("[A-Za-z0-9_-]{1,40}");
  }

  private static JSONArray arr(JSONObject root, String k, int max, List<String> errs) {
    Object v = root.opt(k);
    if (v == null) return new JSONArray();
    if (!(v instanceof JSONArray)) {
      errs.add(k + " must be a list");
      return new JSONArray();
    }
    JSONArray a = (JSONArray) v;
    if (a.length() > max) {
      errs.add(k + " has " + a.length() + " entries; the limit is " + max);
      return new JSONArray();
    }
    return a;
  }

  /** Parses and validates. Returns null (with errors in {@code p}) when the file cannot be used. */
  public static JSONObject parse(String text, Preview p) {
    if (text == null || text.length() > MAX_BYTES) {
      p.errors.add("The file is larger than " + (MAX_BYTES / 1048576) + " MB.");
      return null;
    }
    JSONObject root;
    try {
      root = new JSONObject(text);
    } catch (JSONException e) {
      p.errors.add("This is not a valid JSON backup.");
      return null;
    }
    if (!FORMAT.equals(root.optString("format"))) {
      p.errors.add("This is not a VictorBots backup (wrong format marker).");
      return null;
    }
    p.version = root.optInt("version", -1);
    if (p.version < 1 || p.version > VERSION) {
      p.errors.add("Backup version " + p.version + " is not supported by this app (it reads 1 to " + VERSION + ").");
      return null;
    }
    return root;
  }

  // ------------------------------------------------------------------ preview / apply
  public static Preview preview(Db db, String text) {
    Preview p = new Preview();
    JSONObject root = parse(text, p);
    if (root == null) return p;
    run(db, root, p, OnConflict.SKIP, false);
    return p;
  }

  public static Preview apply(Db db, String text, OnConflict onConflict) {
    Preview p = new Preview();
    JSONObject root = parse(text, p);
    if (root == null) return p;
    Preview dry = new Preview();
    dry.version = p.version;
    run(db, root, dry, onConflict, false);
    if (!dry.ok()) return dry;
    try {
      db.tx(() -> {
        run(db, root, p, onConflict, true);
        if (!p.ok()) throw new IllegalStateException("validation");
        if (!db.rows("PRAGMA foreign_key_check").isEmpty()) throw new IllegalStateException("The restore would break database integrity, so nothing was changed.");
        return null;
      });
    } catch (IllegalStateException e) {
      if (p.ok()) p.errors.add(e.getMessage());
    }
    return p;
  }

  private static void run(Db db, JSONObject root, Preview p, OnConflict oc, boolean write) {
    List<String> e = p.errors;
    Map<String, Long> botByUid = new HashMap<>(), chatByUid = new HashMap<>();
    Map<String, Long> profileByName = new HashMap<>();
    for (JSONObject r : db.rows("SELECT id,name FROM profiles")) profileByName.put(r.optString("name"), r.optLong("id"));
    long now = System.currentTimeMillis();

    // profiles: endpoint settings only; never keys. Duplicates by name are left as they are.
    JSONArray profiles = arr(root, "profiles", MAX_PROFILES, e);
    for (int i = 0; i < profiles.length(); i++) {
      JSONObject o = profiles.optJSONObject(i);
      String w = "profile #" + (i + 1);
      if (o == null) { e.add(w + ": not an object"); continue; }
      String name = str(o, "name", 60, true, e, w), kind = str(o, "kind", 20, true, e, w), base = str(o, "base", 300, true, e, w), model = str(o, "model", 160, false, e, w), pin = str(o, "pin", 80, false, e, w);
      if (!kind.equals("CLOUD") && !kind.equals("PC")) { e.add(w + ": unsupported kind"); continue; }
      try { base = Hosts.base(base); if (!pin.isEmpty()) pin = Rules.pin(pin); } catch (IllegalArgumentException ex) { e.add(w + ": " + ex.getMessage()); continue; }
      JSONObject caps = new JSONObject();
      JSONObject in = o.optJSONObject("caps");
      if (in != null) for (String k : new String[] {"streaming", "tools", "images", "context_tokens", "reasoning_effort", "billing", "price_in_per_m", "price_out_per_m"}) if (in.has(k)) try { caps.put(k, in.get(k)); } catch (JSONException ignored) { }
      if (in != null && VictorLinux.PRESET.equals(in.optString("preset"))) {
        if (!kind.equals("PC") || pin.isEmpty()) { e.add(w + ": the VictorLinux preset needs a PC profile with a pinned certificate"); continue; }
        try {
          caps.put("preset", VictorLinux.PRESET);
          String ru = in.optString("runner_url");
          if (!ru.isEmpty()) {
            ru = Hosts.base(ru);
            caps.put("runner_url", ru);
            if (!Hosts.origin(ru).equals(Hosts.origin(base))) caps.put("runner_pin", Rules.pin(in.optString("runner_pin")));
          }
        } catch (IllegalArgumentException | JSONException ex) { e.add(w + ": " + ex.getMessage()); continue; }
      }
      if (profileByName.containsKey(name)) { p.c("provider profiles")[1]++; continue; }
      p.c("provider profiles")[0]++;
      if (write) {
        long id = db.insert("INSERT INTO profiles(name,kind,base,model,pin,caps,tested,created) VALUES(?,?,?,?,?,?,'{}',?)", name, kind, base, model, pin, caps.toString(), now);
        profileByName.put(name, id);
        p.warnings.add("Profile “" + name + "” was imported without its key. Enter the key again before use.");
      }
    }

    JSONArray bots = arr(root, "bots", MAX_BOTS, e);
    Map<String, String> botNames = new HashMap<>();
    for (int i = 0; i < bots.length(); i++) {
      JSONObject o = bots.optJSONObject(i);
      String w = "bot #" + (i + 1);
      if (o == null) { e.add(w + ": not an object"); continue; }
      String uid = str(o, "uid", 40, false, e, w), name = str(o, "name", 80, true, e, w), desc = str(o, "description", 240, false, e, w), instr = str(o, "instructions", Rules.MAX_INSTRUCTIONS, true, e, w), av = str(o, "avatar", 16, false, e, w);
      String hint = str(o, "role_hint", 300, false, e, w), project = str(o, "project", 60, false, e, w), policy = str(o, "policy", 20, false, e, w), reasoning = str(o, "reasoning", 20, false, e, w), model = str(o, "model", 160, false, e, w), pname = str(o, "profile_name", 60, false, e, w);
      if (!policy.isEmpty() && Policy.Mode.parse(policy, null) == null) { e.add(w + ": unknown policy value"); continue; }
      if (!e.isEmpty()) continue;
      JSONObject local = uid.isEmpty() ? db.one("SELECT * FROM bots WHERE name=? AND instructions=?", name, instr) : db.one("SELECT * FROM bots WHERE uid=?", uid);
      if (local != null) {
        boolean same = local.optString("name").equals(name) && local.optString("instructions").equals(instr) && local.optString("description").equals(desc);
        if (same) { p.c("bots")[1]++; botByUid.put(uid.isEmpty() ? name + "\u0000" + instr : uid, local.optLong("id")); continue; }
        p.c("bots")[2]++;
        if (oc == OnConflict.SKIP) { botByUid.put(uid, local.optLong("id")); continue; }
        uid = Ids.uid();
        name = name.length() > 70 ? name.substring(0, 70) + " (imported)" : name + " (imported)";
      } else p.c("bots")[0]++;
      if (uid.isEmpty() || !uidOk(uid)) uid = Ids.uid();
      if (write) {
        Long pid = pname.isEmpty() ? null : profileByName.get(pname);
        long id = db.insert("INSERT INTO bots(name,description,instructions,avatar,uid,profile,model,policy,project,reasoning,role_hint) VALUES(?,?,?,?,?,?,?,?,?,?,?)", name, desc, instr, av.isEmpty() ? "✦" : av, uid, pid, pid == null ? "" : model, policy, project, reasoning, hint);
        botByUid.put(str(o, "uid", 40, false, new ArrayList<String>(), w).isEmpty() ? name + "\u0000" + instr : str(o, "uid", 40, false, new ArrayList<String>(), w), id);
      }
    }
    if (!write) for (int i = 0; i < bots.length(); i++) { JSONObject o = bots.optJSONObject(i); if (o != null) botByUid.putIfAbsent(o.optString("uid"), -1L); }

    JSONArray chats = arr(root, "chats", MAX_CHATS, e);
    for (int i = 0; i < chats.length(); i++) {
      JSONObject o = chats.optJSONObject(i);
      String w = "chat #" + (i + 1);
      if (o == null) { e.add(w + ": not an object"); continue; }
      String uid = str(o, "uid", 40, false, e, w), title = str(o, "title", 200, true, e, w), kind = str(o, "kind", 10, false, e, w), config = str(o, "config", 4000, false, e, w), summary = str(o, "summary", 3000, false, e, w), botUid = str(o, "bot_uid", 40, false, e, w);
      if (!kind.isEmpty() && !kind.equals("dm") && !kind.equals("team")) { e.add(w + ": unknown kind"); continue; }
      if (!e.isEmpty()) continue;
      if (uid.isEmpty() || !uidOk(uid)) uid = "";
      if (!uid.isEmpty() && db.count("SELECT COUNT(*) n FROM chats WHERE uid=?", uid) > 0) { p.c("chats")[1]++; chatByUid.put(uid, -1L); continue; }
      p.c("chats")[0]++;
      if (!write) chatByUid.put(uid.isEmpty() ? "idx" + i : uid, 0L);
      if (write) {
        String nuid = uid.isEmpty() ? Ids.uid() : uid;
        Long bot = botUid.isEmpty() ? null : botByUid.get(botUid);
        if (kind.equals("dm") || kind.isEmpty()) { if (bot == null || bot < 0) { p.warnings.add("A chat refers to a bot that is not in the file; skipped."); p.c("chats")[0]--; continue; } }
        long id = db.insert("INSERT INTO chats(bot,title,created,kind,config,summary,uid) VALUES(?,?,?,?,?,?,?)", kind.equals("team") ? null : bot, title, o.optLong("created", now), kind.isEmpty() ? "dm" : kind, kind.equals("team") ? Teams.Config.parse(config).json().toString() : "{}", summary, nuid);
        chatByUid.put(uid.isEmpty() ? "idx" + i : uid, id);
        if (kind.equals("team")) {
          JSONArray mem = o.optJSONArray("members");
          if (mem != null) for (int k = 0; k < Math.min(mem.length(), 8); k++) { JSONObject m = mem.optJSONObject(k); Long b = m == null ? null : botByUid.get(m.optString("bot_uid")); if (b != null && b > 0) db.update("INSERT OR IGNORE INTO chat_members(chat,bot,muted) VALUES(?,?,?)", id, b, m.optInt("muted") == 1 ? 1 : 0); }
        }
      }
    }

    JSONArray msgs = arr(root, "messages", MAX_MESSAGES, e);
    int newMsgs = 0;
    for (int i = 0; i < msgs.length(); i++) {
      JSONObject o = msgs.optJSONObject(i);
      String w = "message #" + (i + 1);
      if (o == null) { e.add(w + ": not an object"); continue; }
      String cu = str(o, "chat_uid", 40, false, e, w), role = str(o, "role", 10, true, e, w), body = str(o, "body", MAX_BODY, true, e, w), status = str(o, "status", 10, false, e, w), kind = str(o, "kind", 10, false, e, w), au = str(o, "author_uid", 40, false, e, w), meta = str(o, "meta", 4000, false, e, w);
      if (!role.equals("user") && !role.equals("assistant") && !role.equals("system")) { e.add(w + ": unknown role"); continue; }
      if (!e.isEmpty()) continue;
      Long chat = chatByUid.get(cu);
      if (chat == null || chat < 0) continue; // belongs to a chat that already exists or was skipped
      newMsgs++;
      if (write) {
        Long author = au.isEmpty() ? null : botByUid.get(au);
        try { new JSONObject(meta.isEmpty() ? "{}" : meta); } catch (JSONException ex) { meta = "{}"; }
        db.insert("INSERT INTO messages(chat,role,body,status,created,author,meta,kind) VALUES(?,?,?,?,?,?,?,?)", chat, role, body, status.equals("error") ? "error" : "done", o.optLong("created", now), author == null || author < 0 ? 0L : author, meta.isEmpty() ? "{}" : meta, kind.equals("notice") ? "notice" : "text");
      }
    }
    if (newMsgs > 0 || !write) p.c("messages")[0] += newMsgs;

    JSONArray mems = arr(root, "memories", MAX_MEMORIES, e);
    for (int i = 0; i < mems.length(); i++) {
      JSONObject o = mems.optJSONObject(i);
      String w = "memory #" + (i + 1);
      if (o == null) { e.add(w + ": not an object"); continue; }
      String uid = str(o, "uid", 40, false, e, w), scope = str(o, "scope", 10, true, e, w), body = str(o, "body", Memory.MAX_BODY, true, e, w), project = str(o, "project", 60, false, e, w), status = str(o, "status", 12, false, e, w), label = str(o, "source_label", 120, false, e, w), bu = str(o, "bot_uid", 40, false, e, w);
      if (!scope.equals("bot") && !scope.equals("project")) { e.add(w + ": bad scope"); continue; }
      if (!e.isEmpty()) continue;
      if (!uid.isEmpty() && db.count("SELECT COUNT(*) n FROM memories WHERE uid=?", uid) > 0) { p.c("memories")[1]++; continue; }
      Long bot = bu.isEmpty() ? null : botByUid.get(bu);
      if (scope.equals("bot") && (bot == null || (write && bot < 0))) continue;
      p.c("memories")[0]++;
      if (write) {
        long id = db.insert("INSERT INTO memories(uid,scope,bot,project,body,origin,status,source_label,pinned,created,updated) VALUES(?,?,?,?,?,'import',?,?,?,?,?)", uidOk(uid) ? uid : Ids.uid(), scope, scope.equals("bot") ? bot : null, scope.equals("project") ? project : "", body, status.equals("suggested") ? "suggested" : "active", label, o.optInt("pinned") == 1 ? 1 : 0, o.optLong("created", now), now);
        db.update("DELETE FROM memory_fts WHERE docid=?", id);
        db.insert("INSERT INTO memory_fts(docid,body) VALUES(?,?)", id, body);
      }
    }

    JSONArray tasks = arr(root, "tasks", MAX_TASKS, e);
    for (int i = 0; i < tasks.length(); i++) {
      JSONObject o = tasks.optJSONObject(i);
      String w = "task #" + (i + 1);
      if (o == null) { e.add(w + ": not an object"); continue; }
      String uid = str(o, "uid", 40, false, e, w), title = str(o, "title", 200, true, e, w), body = str(o, "body", 12000, true, e, w), status = str(o, "status", 40, false, e, w);
      if (!e.isEmpty()) continue;
      boolean dup = (!uid.isEmpty() && db.count("SELECT COUNT(*) n FROM tasks WHERE uid=?", uid) > 0) || db.count("SELECT COUNT(*) n FROM tasks WHERE title=? AND body=?", title, body) > 0;
      if (dup) { p.c("tasks")[1]++; continue; }
      p.c("tasks")[0]++;
      if (write) db.insert("INSERT INTO tasks(title,body,status,created,uid) VALUES(?,?,?,?,?)", title, body, status.isEmpty() ? "Draft" : status, o.optLong("created", now), uidOk(uid) ? uid : Ids.uid());
    }

    JSONArray skills = arr(root, "skills", MAX_SKILLS, e);
    for (int i = 0; i < skills.length(); i++) {
      JSONObject o = skills.optJSONObject(i);
      String w = "skill #" + (i + 1);
      if (o == null) { e.add(w + ": not an object"); continue; }
      String name = str(o, "name", 40, true, e, w), desc = str(o, "description", 200, true, e, w), body = str(o, "body", 8000, true, e, w), req = str(o, "requires", 400, false, e, w);
      if (!e.isEmpty()) continue;
      List<String> reqs = new ArrayList<>();
      try { JSONArray ra = new JSONArray(req.isEmpty() ? "[]" : req); for (int k = 0; k < ra.length(); k++) if (Skills.KNOWN_REQUIREMENTS.contains(ra.optString(k))) reqs.add(ra.optString(k)); } catch (JSONException ex) { e.add(w + ": bad requirements"); continue; }
      JSONObject local = db.one("SELECT * FROM skills WHERE name=?", name);
      if (local != null) { if (local.optString("body").equals(body)) p.c("skills")[1]++; else p.c("skills")[2]++; continue; }
      p.c("skills")[0]++;
      if (write) {
        try { Skills.save(db, 0, name, desc, body, reqs); } catch (IllegalArgumentException ex) { e.add(w + ": " + ex.getMessage()); }
      }
    }

    JSONArray routines = arr(root, "routines", MAX_ROUTINES, e);
    for (int i = 0; i < routines.length(); i++) {
      JSONObject o = routines.optJSONObject(i);
      String w = "routine #" + (i + 1);
      if (o == null) { e.add(w + ": not an object"); continue; }
      String uid = str(o, "uid", 40, false, e, w), name = str(o, "name", 80, true, e, w), bu = str(o, "bot_uid", 40, true, e, w), target = str(o, "target", 10, true, e, w), instr = str(o, "instruction", 4000, false, e, w), tool = str(o, "tool", 60, false, e, w), args = str(o, "args", 4000, false, e, w), tz = str(o, "tz", 60, true, e, w), sched = str(o, "schedule", 400, true, e, w);
      if (!e.isEmpty()) continue;
      if (!uid.isEmpty() && db.count("SELECT COUNT(*) n FROM routines WHERE uid=?", uid) > 0) { p.c("routines")[1]++; continue; }
      try { Routines.validate(target, tz, sched, args); } catch (IllegalArgumentException ex) { e.add(w + ": " + ex.getMessage()); continue; }
      Long bot = botByUid.get(bu);
      if (bot == null || (write && bot < 0)) { p.warnings.add("Routine “" + name + "” refers to a bot that is not in the file; skipped."); continue; }
      p.c("routines")[0]++;
      if (write) db.insert("INSERT INTO routines(uid,name,bot,target,instruction,tool,args,tz,schedule,enabled,overlap,missed,created) VALUES(?,?,?,?,?,?,?,?,?,0,?,?,?)", uidOk(uid) ? uid : Ids.uid(), name, bot, target, instr, tool, args.isEmpty() ? "{}" : args, tz, sched, o.optString("overlap").equals("queue") ? "queue" : "skip", o.optString("missed").equals("run_once") ? "run_once" : "skip", now);
    }
    if (!routines.isNull(0) && routines.length() > 0) p.warnings.add("Imported routines are switched off. Review and enable them yourself.");
  }

  private Backup() {}
}
