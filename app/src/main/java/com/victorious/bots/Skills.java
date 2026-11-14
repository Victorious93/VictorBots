package com.victorious.bots;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.*;

/**
 * Reusable named task instructions. A skill is only text added to the owner's message. Its declared
 * requirements are informational: they say what a workflow needs, they never grant anything, and they
 * never start a job.
 */
public final class Skills {
  public static final class Skill {
    public long id;
    public String uid, name, description, body;
    public int version;
    public List<String> requires = new ArrayList<>();
    public boolean builtin;
  }

  private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
  private static final Pattern TOKEN = Pattern.compile("(^|\\s)/([a-z0-9][a-z0-9-]{0,39})(?=\\s|$)");

  private static Skill from(JSONObject r) {
    Skill s = new Skill();
    s.id = r.optLong("id");
    s.uid = r.optString("uid");
    s.name = r.optString("name");
    s.description = r.optString("description");
    s.body = r.optString("body");
    s.version = r.optInt("version", 1);
    s.builtin = r.optInt("builtin") == 1;
    try {
      JSONArray a = new JSONArray(r.optString("requires", "[]"));
      for (int i = 0; i < a.length(); i++) s.requires.add(a.optString(i));
    } catch (JSONException ignored) {
    }
    return s;
  }

  public static List<Skill> all(Db db) {
    List<Skill> out = new ArrayList<>();
    for (JSONObject r : db.rows("SELECT * FROM skills ORDER BY builtin DESC,name")) out.add(from(r));
    return out;
  }

  public static Skill byName(Db db, String name) {
    JSONObject r = db.one("SELECT * FROM skills WHERE name=?", name);
    return r == null ? null : from(r);
  }

  public static long save(Db db, long id, String name, String description, String body, List<String> requires) {
    if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("Skill names use lowercase letters, digits and hyphens (for example repo-review).");
    Rules.length(description, 200, "Description");
    Rules.length(body, 8000, "Instructions");
    for (String r : requires) if (!KNOWN_REQUIREMENTS.contains(r)) throw new IllegalArgumentException("Unknown requirement: " + r);
    long now = System.currentTimeMillis();
    String req = new JSONArray(requires).toString();
    if (id == 0) {
      if (db.count("SELECT COUNT(*) n FROM skills WHERE name=?", name) > 0) throw new IllegalArgumentException("A skill with that name already exists.");
      return db.insert("INSERT INTO skills(uid,name,description,version,body,requires,builtin,created,updated) VALUES(?,?,?,?,?,?,0,?,?)", Ids.uid(), name, description, 1, body, req, now, now);
    }
    JSONObject cur = db.one("SELECT * FROM skills WHERE id=?", id);
    if (cur == null) throw new IllegalArgumentException("Skill no longer exists.");
    if (cur.optInt("builtin") == 1) throw new IllegalArgumentException("Bundled skills are read-only. Duplicate it to change it.");
    boolean changed = !cur.optString("body").equals(body) || !cur.optString("requires").equals(req) || !cur.optString("description").equals(description);
    db.update("UPDATE skills SET name=?,description=?,body=?,requires=?,version=version+?,updated=? WHERE id=?", name, description, body, req, changed ? 1 : 0, now, id);
    return id;
  }

  public static void delete(Db db, long id) {
    JSONObject cur = db.one("SELECT builtin FROM skills WHERE id=?", id);
    if (cur != null && cur.optInt("builtin") == 1) throw new IllegalArgumentException("Bundled skills cannot be deleted.");
    db.update("DELETE FROM skills WHERE id=?", id);
  }

  /** Capabilities a skill may declare. They name what a workflow needs; they are not permissions. */
  public static final Set<String> KNOWN_REQUIREMENTS =
      new HashSet<>(Arrays.asList("files.attach", "pc.repo.read", "pc.runner", "dca.assist", "images"));

  public static final class Expansion {
    public String text;
    public List<Skill> used = new ArrayList<>();
    public List<String> unmet = new ArrayList<>();
  }

  /**
   * Replaces /skill-name tokens in the owner's message with a labelled instruction block.
   * @param available requirement ids the current setup can actually meet; unmet ones are reported.
   */
  public static Expansion expand(Db db, String text, Set<String> available) {
    Expansion e = new Expansion();
    Matcher m = TOKEN.matcher(text);
    StringBuffer out = new StringBuffer();
    Set<String> seen = new HashSet<>();
    while (m.find()) {
      Skill s = byName(db, m.group(2));
      if (s == null || !seen.add(s.name) || seen.size() > 3) {
        m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
        continue;
      }
      e.used.add(s);
      for (String r : s.requires) if (!available.contains(r) && !e.unmet.contains(r)) e.unmet.add(r);
      m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + "[Owner-selected skill “" + s.name + "” v" + s.version + ": " + s.body.trim() + "]"));
    }
    m.appendTail(out);
    e.text = out.toString();
    return e;
  }

  public static void seed(Db db) {
    long now = System.currentTimeMillis();
    String[][] defs = {
      {"repo-review", "Review a change set or repository for defects, risks and missing tests", "Review the code or diff I provide. Group findings as Blocker / Major / Minor. For each: where it is, why it matters, a concrete fix. Separate what you can see in the supplied text from what you are assuming. Do not claim you ran tests or opened files I did not give you. End with the three highest-risk areas to test.", "[\"pc.repo.read\"]"},
      {"apk-crash-diagnosis", "Diagnose an Android crash from a log or stack trace", "Diagnose the Android crash in the attached log or pasted stack trace. State the exception, the failing frame in app code, the most likely cause, and how to confirm it (what to log or reproduce). Then propose a minimal fix. If the evidence is insufficient, list exactly what additional log lines or device details you need. Do not guess at causes the log does not support.", "[\"files.attach\"]"},
      {"release-prep", "Prepare a release checklist for an Android build", "Produce a release checklist for the project described: version/versionCode, signing identity and keystore handling (never ask me to paste secrets), build reproducibility, install/upgrade test on a clean and a populated device, permissions and manifest review, changelog, rollback plan. Mark each item Done / Not done / Unknown based only on what I told you; do not assert anything was verified.", "[\"pc.repo.read\"]"},
      {"project-plan", "Turn a goal into a short staged plan with verification steps", "Turn my goal into a staged plan: outcome, assumptions, 3-7 steps in dependency order, how each step is verified, risks, and the first concrete action. Keep it short. Ask at most two questions and only if the answer would change the plan.", "[]"},
    };
    for (String[] d : defs) {
      if (db.count("SELECT COUNT(*) n FROM skills WHERE name=?", d[0]) > 0) continue;
      db.insert("INSERT INTO skills(uid,name,description,version,body,requires,builtin,created,updated) VALUES(?,?,?,1,?,?,1,?,?)", Ids.uid(), d[0], d[1], d[2], d[3], now, now);
    }
  }

  private Skills() {}
}
