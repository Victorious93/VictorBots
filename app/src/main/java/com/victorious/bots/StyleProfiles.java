package com.victorious.bots;

import java.util.*;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Victorious Framework: writing-style profiles owned by VictorBots. A profile is statistics plus a few short examples of
 * the owner's OWN messages, never model weights. Every analysis creates a new immutable version; rollback copies an old
 * version forward, so history is never rewritten. Learned preferences and approved examples are separate rows that the
 * owner can list and remove, and effective guidance is always recomputed from what is currently active.
 */
public final class StyleProfiles {
  public enum Action { APPROVE, REJECT, EDIT, REGENERATE, SAVE_AS_EXAMPLE }

  public enum Quick { SHORTER, LESS_FORMAL, FEWER_EMOJIS, PRESERVE_SPELLING, SPLIT_REPLIES }

  private static final Set<String> SCOPES = new HashSet<>(Arrays.asList("global", "contact", "group"));
  private static final Set<String> PREF_KINDS = new HashSet<>(Arrays.asList("prefer", "avoid", "shorter", "less_formal", "fewer_emojis", "preserve_spelling", "split_replies"));
  private static final Pattern URL = Pattern.compile("(?i)(https?://|www\\.)\\S+");
  private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
  private static final Pattern DIGITS = Pattern.compile("\\d[\\d\\s().-]{5,}\\d");
  static final int MAX_VERSIONS = 100;
  static final int MAX_IMPORTED_EXAMPLES = 50;
  private static final Set<String> CONFIDENCE = new HashSet<>(Arrays.asList("low", "medium", "high"));
  /** The numeric statistics StyleAnalyzer writes. Anything else in a stored or imported profile is ignored. */
  private static final Set<String> NUMERIC = new HashSet<>(Arrays.asList("count", "avgChars", "medianChars", "p90Chars", "avgWords", "shortRatio", "multilineRatio",
      "startsLowercaseRatio", "endsPeriodRatio", "endsQuestionRatio", "endsExclamationRatio", "ellipsisRatio", "doublePunctuationRatio", "allCapsWordRatio",
      "lowercaseIRatio", "emojiPerMessage", "emojiMessageRatio", "slangPerMessage", "apostropheOmitRatio", "humorRate", "humor", "sarcasmMarkers", "formality",
      "burstRatio", "avgBurstSize"));
  private static final Pattern WORD = Pattern.compile("[\\p{L}'’]{3,30}");
  private static final Pattern PHRASE = Pattern.compile("[\\p{L}'’]+( [\\p{L}'’]+){1,2}");
  /** A preferred or avoided expression: letters, marks, spaces and light punctuation only. */
  private static final Pattern EXPRESSION = Pattern.compile("[\\p{L}\\p{M}\\p{N}'’ !?.,\\-]+");
  static final int MAX_IMPORTED_EXPRESSION = 40, MAX_IMPORTED_EXPRESSION_WORDS = 5;
  static final int MAX_TEXT = 300;

  private final Db db;

  public StyleProfiles(Db db) {
    this.db = db;
  }

  // ---- profiles ----

  public long create(String name, String scope, List<String> contacts, String botUid) {
    Rules.length(name, 80, "Profile name");
    if (name.trim().isEmpty()) throw new IllegalArgumentException("A profile needs a name.");
    if (!SCOPES.contains(scope)) throw new IllegalArgumentException("Scope must be global, contact or group.");
    long now = System.currentTimeMillis();
    return db.insert("INSERT INTO style_profiles(uid,name,scope,contacts,bot_uid,created,updated) VALUES(?,?,?,?,?,?,?)",
        Ids.uid(), name.trim(), scope, jsonList(contacts, 200, 80).toString(), botUid == null ? "" : botUid, now, now);
  }

  public JSONObject get(long id) {
    return db.one("SELECT * FROM style_profiles WHERE id=?", id);
  }

  public List<JSONObject> list() {
    return db.rows("SELECT * FROM style_profiles ORDER BY name COLLATE NOCASE, id");
  }

  public void setStrength(long id, int strength) {
    if (strength < 0 || strength > 100) throw new IllegalArgumentException("Personalization strength is 0 to 100.");
    db.update("UPDATE style_profiles SET strength=?,updated=? WHERE id=?", strength, System.currentTimeMillis(), id);
  }

  public void setPreserveSpelling(long id, boolean on) {
    db.update("UPDATE style_profiles SET preserve_spelling=?,updated=? WHERE id=?", on, System.currentTimeMillis(), id);
  }

  public void setLearnFromEdits(long id, boolean on) {
    db.update("UPDATE style_profiles SET learn_from_edits=?,updated=? WHERE id=?", on, System.currentTimeMillis(), id);
  }

  public void assignContacts(long id, List<String> contacts) {
    db.update("UPDATE style_profiles SET contacts=?,updated=? WHERE id=?", jsonList(contacts, 200, 80).toString(), System.currentTimeMillis(), id);
  }

  public void delete(long id) {
    db.tx(() -> {
      JSONObject p = get(id);
      // A communications bot that used this profile falls back to no profile instead of pointing at nothing.
      if (p != null) db.update("UPDATE comm_bots SET profile_uid='' WHERE profile_uid=?", p.optString("uid"));
      db.update("DELETE FROM style_versions WHERE profile=?", id);
      db.update("DELETE FROM style_prefs WHERE profile=?", id);
      db.update("DELETE FROM style_examples WHERE profile=?", id);
      db.update("DELETE FROM style_profiles WHERE id=?", id);
      return null;
    });
  }

  public long duplicate(long id, String newName) {
    return db.tx(() -> {
      JSONObject p = need(id);
      long copy = create(newName, p.optString("scope", "global"), new ArrayList<String>(), "");
      db.update("UPDATE style_profiles SET strength=?,preserve_spelling=?,learn_from_edits=? WHERE id=?", p.optLong("strength"), p.optLong("preserve_spelling"), p.optLong("learn_from_edits"), copy);
      JSONObject cur = version(id, (int) p.optLong("current_version"));
      if (cur != null) insertVersion(copy, 1, cur.optString("stats"), cur.optString("examples"), cur.optLong("source_count"), "[]", cur.optString("confidence"), "Copied from " + p.optString("name"));
      db.update("UPDATE style_profiles SET current_version=? WHERE id=?", cur == null ? 0 : 1, copy);
      for (JSONObject pr : prefs(id)) db.insert("INSERT INTO style_prefs(profile,kind,text,created) VALUES(?,?,?,?)", copy, pr.optString("kind"), pr.optString("text"), System.currentTimeMillis());
      for (JSONObject ex : examples(id)) db.insert("INSERT INTO style_examples(profile,text,source,created) VALUES(?,?,?,?)", copy, ex.optString("text"), ex.optString("source"), System.currentTimeMillis());
      return copy;
    });
  }

  /** Clears the analysis, learned preferences and approved examples. A new empty version marks the reset in history. */
  public void reset(long id) {
    db.tx(() -> {
      need(id);
      db.update("DELETE FROM style_prefs WHERE profile=?", id);
      db.update("DELETE FROM style_examples WHERE profile=?", id);
      addVersion(id, new StyleAnalyzer.Result(), new ArrayList<String>(), "Reset by the owner");
      return null;
    });
  }

  // ---- versions ----

  public int addVersion(long id, StyleAnalyzer.Result r, List<String> sources, String note) {
    return db.tx(() -> {
      need(id);
      int v = (int) db.count("SELECT COALESCE(MAX(version),0)+1 n FROM style_versions WHERE profile=?", id);
      JSONArray ex = new JSONArray();
      for (String e : r.examples) ex.put(e);
      insertVersion(id, v, r.stats.toString(), ex.toString(), r.count, jsonList(sources, 50, 80).toString(), r.confidence, note == null ? "" : note);
      db.update("UPDATE style_profiles SET current_version=?,updated=? WHERE id=?", v, System.currentTimeMillis(), id);
      prune(id, v);
      return v;
    });
  }

  /** Keeps at most MAX_VERSIONS versions, never the current one. */
  private void prune(long id, int current) {
    db.update("DELETE FROM style_versions WHERE profile=? AND version<=? AND version<>?", id, current - MAX_VERSIONS, current);
  }

  private void insertVersion(long id, int v, String stats, String examples, long count, String sources, String conf, String note) {
    db.insert("INSERT INTO style_versions(profile,version,stats,examples,source_count,sources,confidence,note,created) VALUES(?,?,?,?,?,?,?,?,?)",
        id, v, stats, examples, count, sources, conf, Redact.snippet(note, 200), System.currentTimeMillis());
  }

  public JSONObject version(long id, int v) {
    return db.one("SELECT * FROM style_versions WHERE profile=? AND version=?", id, v);
  }

  public List<JSONObject> versions(long id) {
    return db.rows("SELECT version,source_count,confidence,note,created FROM style_versions WHERE profile=? ORDER BY version DESC", id);
  }

  /** Restores an older version by copying it forward as a NEW version, so nothing in the history is lost. */
  public int rollbackTo(long id, int v) {
    return db.tx(() -> {
      JSONObject old = version(id, v);
      if (old == null) throw new IllegalArgumentException("That version no longer exists.");
      int nv = (int) db.count("SELECT COALESCE(MAX(version),0)+1 n FROM style_versions WHERE profile=?", id);
      insertVersion(id, nv, old.optString("stats"), old.optString("examples"), old.optLong("source_count"), old.optString("sources", "[]"), old.optString("confidence"), "Restored version " + v);
      db.update("UPDATE style_profiles SET current_version=?,updated=? WHERE id=?", nv, System.currentTimeMillis(), id);
      prune(id, nv);
      return nv;
    });
  }

  /** Numeric changes and added/removed list entries between two versions. */
  public JSONObject compare(long id, int a, int b) {
    try {
      JSONObject va = version(id, a), vb = version(id, b);
      if (va == null || vb == null) throw new IllegalArgumentException("Both versions must exist.");
      JSONObject sa = new JSONObject(va.optString("stats", "{}")), sb = new JSONObject(vb.optString("stats", "{}"));
      JSONObject deltas = new JSONObject(), lists = new JSONObject();
      Set<String> keys = new TreeSet<>();
      Iterator<String> it = sa.keys();
      while (it.hasNext()) keys.add(it.next());
      it = sb.keys();
      while (it.hasNext()) keys.add(it.next());
      for (String k : keys) {
        Object x = sa.opt(k), y = sb.opt(k);
        if (x instanceof Number && y instanceof Number) {
          double d = ((Number) y).doubleValue() - ((Number) x).doubleValue();
          if (Math.abs(d) >= 0.0005) deltas.put(k, Math.round(d * 1000.0) / 1000.0);
        } else if ((x == null || x instanceof JSONArray) && (y == null || y instanceof JSONArray) && (x != null || y != null)) {
          Set<String> xs = strings((JSONArray) x), ys = strings((JSONArray) y);
          JSONArray added = new JSONArray(), removed = new JSONArray();
          for (String s : ys) if (!xs.contains(s)) added.put(s);
          for (String s : xs) if (!ys.contains(s)) removed.put(s);
          if (added.length() + removed.length() > 0) lists.put(k, new JSONObject().put("added", added).put("removed", removed));
        }
      }
      return new JSONObject().put("from", a).put("to", b).put("deltas", deltas).put("lists", lists);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  // ---- corrections and learned preferences ----

  /**
   * Records what the owner did with a generated message. Only the owner's own words can become an example:
   * approving AI text unedited, rejecting or regenerating it teaches nothing, so the model cannot reinforce its own style.
   * An edit becomes an example only when the owner turned on learning from edits or saved it explicitly.
   * @return the new example id, or null when nothing was learned
   */
  public Long record(long id, String generated, String finalText, Action action) {
    JSONObject p = need(id);
    String text = finalText == null ? "" : finalText.trim();
    switch (action) {
      case SAVE_AS_EXAMPLE:
        return addExample(id, text, "saved");
      case EDIT:
        if (!text.isEmpty() && !text.equals(generated == null ? "" : generated.trim()) && p.optLong("learn_from_edits") == 1 && acceptable(text)) return addExample(id, text, "edit");
        return null;
      default:
        return null;
    }
  }

  public long addExample(long id, String text, String source) {
    need(id);
    String t = text == null ? "" : text.trim();
    if (t.isEmpty()) throw new IllegalArgumentException("An example cannot be empty.");
    if (!acceptable(t)) throw new IllegalArgumentException("Examples must be short and may not contain links, email addresses or phone numbers.");
    long dup = db.count("SELECT COUNT(*) n FROM style_examples WHERE profile=? AND text=?", id, t);
    if (dup > 0) return db.one("SELECT id FROM style_examples WHERE profile=? AND text=?", id, t).optLong("id");
    return db.insert("INSERT INTO style_examples(profile,text,source,created) VALUES(?,?,?,?)", id, t, source, System.currentTimeMillis());
  }

  public long prefer(long id, String expression) {
    return addPref(id, "prefer", expression);
  }

  public long avoid(long id, String expression) {
    return addPref(id, "avoid", expression);
  }

  public long quick(long id, Quick q) {
    String kind = q.name().toLowerCase(Locale.ROOT);
    return db.tx(() -> {
      JSONObject existing = db.one("SELECT id FROM style_prefs WHERE profile=? AND kind=?", id, kind);
      if (existing != null) return existing.optLong("id");
      return addPref(id, kind, "");
    });
  }

  private long addPref(long id, String kind, String text) {
    need(id);
    if (!PREF_KINDS.contains(kind)) throw new IllegalArgumentException("Unknown preference.");
    String t = text == null ? "" : text.trim();
    if ((kind.equals("prefer") || kind.equals("avoid")) && (t.isEmpty() || t.length() > 80)) throw new IllegalArgumentException("An expression must be 1 to 80 characters.");
    if ((kind.equals("prefer") || kind.equals("avoid")) && !expressionOk(t)) throw new IllegalArgumentException("An expression may hold letters, spaces and simple punctuation only, and no links, email addresses or number codes.");
    return db.insert("INSERT INTO style_prefs(profile,kind,text,created) VALUES(?,?,?,?)", id, kind, t, System.currentTimeMillis());
  }

  public List<JSONObject> prefs(long id) {
    return db.rows("SELECT id,kind,text,created FROM style_prefs WHERE profile=? ORDER BY id", id);
  }

  public void removePref(long prefId) {
    db.update("DELETE FROM style_prefs WHERE id=?", prefId);
  }

  public List<JSONObject> examples(long id) {
    return db.rows("SELECT id,text,source,created FROM style_examples WHERE profile=? ORDER BY id", id);
  }

  public void removeExample(long exampleId) {
    db.update("DELETE FROM style_examples WHERE id=?", exampleId);
  }

  // ---- guidance for droidcommand-AI ----

  /** The `style` argument for communications.generate_reply: built only from what is currently active. */
  public JSONObject guidance(long id) {
    try {
      JSONObject p = need(id);
      JSONObject cur = version(id, (int) p.optLong("current_version"));
      // Stored statistics are re-checked here as well, so a profile saved before this check can never carry free text.
      JSONObject st = cleanStats(cur == null ? new JSONObject() : new JSONObject(cur.optString("stats", "{}")));
      Set<String> kinds = new HashSet<>();
      List<String> avoid = new ArrayList<>(), prefer = new ArrayList<>();
      for (JSONObject pr : prefs(id)) {
        kinds.add(pr.optString("kind"));
        String text = pr.optString("text");
        if (pr.optString("kind").equals("avoid") && expressionOk(text)) avoid.add(text);
        if (pr.optString("kind").equals("prefer") && expressionOk(text)) prefer.add("\"" + text + "\"");
      }
      List<String> g = new ArrayList<>();
      double avgWords = st.optDouble("avgWords", -1);
      if (kinds.contains("shorter")) g.add("Keep messages very short.");
      else if (avgWords >= 0) g.add("A typical message is about " + Math.max(1, Math.round(avgWords)) + " words.");
      if (st.optDouble("startsLowercaseRatio", 0) >= 0.7) g.add("Usually starts messages in lowercase.");
      if (st.has("endsPeriodRatio") && st.optDouble("endsPeriodRatio", 1) < 0.2) g.add("Rarely ends a message with a period.");
      if (kinds.contains("fewer_emojis")) g.add("Use emoji sparingly.");
      else if (st.optDouble("emojiPerMessage", 0) >= 0.3) g.add("Uses emoji fairly often" + listSuffix(st.optJSONArray("topEmoji"), 3) + ".");
      JSONArray slang = st.optJSONArray("topSlang");
      if (slang != null && slang.length() > 0) g.add("Common slang: " + join(strings(slang), 6) + ".");
      if (kinds.contains("split_replies") || st.optDouble("burstRatio", 0) >= 0.3) g.add("Often sends several short messages in a row rather than one long one.");
      double formality = st.optDouble("formality", -1);
      if (formality >= 0) {
        if (kinds.contains("less_formal")) formality = Math.max(0, formality - 15);
        g.add(formality < 35 ? "Very casual tone." : formality > 65 ? "Fairly formal tone." : "Relaxed, neutral tone.");
      } else if (kinds.contains("less_formal")) g.add("Casual tone.");
      JSONArray signOffs = st.optJSONArray("signOffs");
      if (signOffs != null && signOffs.length() > 0) g.add("Common sign-offs: " + join(strings(signOffs), 3) + ".");
      if (!prefer.isEmpty()) g.add("Likes to say: " + join(prefer, 6) + ".");
      String guidance = String.join(" ", g);
      List<String> examples = new ArrayList<>();
      for (JSONObject ex : examples(id)) if (acceptable(ex.optString("text"))) examples.add(ex.optString("text"));
      if (cur != null) for (String ex : strings(new JSONArray(cur.optString("examples", "[]")))) if (acceptable(ex)) examples.add(ex);
      List<String> unique = new ArrayList<>(new LinkedHashSet<>(examples));
      return new JSONObject()
          .put("strength", p.optLong("strength"))
          .put("guidance", guidance.length() > 1500 ? guidance.substring(0, 1500) : guidance)
          .put("examples", new JSONArray(unique.subList(0, Math.min(10, unique.size()))))
          .put("avoid", new JSONArray(avoid.subList(0, Math.min(12, avoid.size()))))
          .put("preserve_spelling", p.optLong("preserve_spelling") == 1 || kinds.contains("preserve_spelling"));
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  // ---- export / import ----

  /** A shareable copy: statistics, approved examples and preferences. No contacts, bot or ids. */
  public String export(long id) {
    try {
      JSONObject p = need(id);
      JSONObject cur = version(id, (int) p.optLong("current_version"));
      JSONArray prefs = new JSONArray();
      for (JSONObject pr : prefs(id)) prefs.put(new JSONObject().put("kind", pr.optString("kind")).put("text", pr.optString("text")));
      JSONArray ex = new JSONArray();
      for (JSONObject e : examples(id)) ex.put(e.optString("text"));
      return new JSONObject().put("format", "victorbots-style-profile").put("v", 1).put("name", p.optString("name")).put("scope", p.optString("scope"))
          .put("strength", p.optLong("strength")).put("preserveSpelling", p.optLong("preserve_spelling") == 1).put("learnFromEdits", p.optLong("learn_from_edits") == 1)
          .put("stats", shareableStats(cur == null ? new JSONObject() : new JSONObject(cur.optString("stats", "{}"))))
          .put("analysisExamples", cur == null ? new JSONArray() : new JSONArray(cur.optString("examples", "[]")))
          .put("confidence", cur == null ? "low" : cur.optString("confidence", "low")).put("approvedExamples", ex).put("prefs", prefs).toString();
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  public long importProfile(String json) {
    if (json == null || json.length() > 200_000) throw new IllegalArgumentException("That is not a VictorBots profile file.");
    final JSONObject o;
    try {
      o = new JSONObject(json);
    } catch (JSONException e) {
      throw new IllegalArgumentException("That is not a VictorBots profile file.");
    }
    final int strength = checkImport(o);
    return db.tx(() -> {
      long id = create(o.optString("name", "Imported profile").length() == 0 ? "Imported profile" : o.optString("name"), SCOPES.contains(o.optString("scope")) ? o.optString("scope") : "global", new ArrayList<String>(), "");
      setStrength(id, strength);
      setPreserveSpelling(id, o.optBoolean("preserveSpelling", true));
      setLearnFromEdits(id, o.optBoolean("learnFromEdits", false));
      StyleAnalyzer.Result r = new StyleAnalyzer.Result();
      JSONObject stats = o.optJSONObject("stats");
      // Only the known numeric statistics and word lists that pass the same checks as analysis survive an import.
      r.stats = cleanStats(stats);
      r.confidence = CONFIDENCE.contains(o.optString("confidence")) ? o.optString("confidence") : "low";
      for (String e : strings(o.optJSONArray("analysisExamples"))) if (acceptable(e)) r.examples.add(e);
      if (r.examples.size() > 8) r.examples = new ArrayList<>(r.examples.subList(0, 8));
      r.count = r.stats.optInt("count", 0);
      addVersion(id, r, new ArrayList<String>(), "Imported from a profile file");
      int added = 0;
      for (String e : strings(o.optJSONArray("approvedExamples"))) {
        if (added >= MAX_IMPORTED_EXAMPLES) break;
        if (acceptable(e)) { addExample(id, e, "import"); added++; }
      }
      JSONArray prefs = o.optJSONArray("prefs");
      for (int i = 0; prefs != null && i < Math.min(prefs.length(), 50); i++) {
        JSONObject pr = prefs.optJSONObject(i);
        if (pr == null || !PREF_KINDS.contains(pr.optString("kind"))) continue;
        String text = pr.optString("text").trim();
        // A file someone else made may only carry short expressions, never sentences that read like instructions.
        if (!text.isEmpty() && (text.length() > MAX_IMPORTED_EXPRESSION || text.split("\\s+").length > MAX_IMPORTED_EXPRESSION_WORDS)) continue;
        try { addPref(id, pr.optString("kind"), pr.optString("text")); } catch (IllegalArgumentException ignored) { }
      }
      return id;
    });
  }

  // ---- helpers ----

  private JSONObject need(long id) {
    JSONObject p = get(id);
    if (p == null) throw new IllegalArgumentException("That profile no longer exists.");
    return p;
  }

  static boolean acceptable(String t) {
    return t != null && t.length() >= 2 && t.length() <= MAX_TEXT && !URL.matcher(t).find() && !EMAIL.matcher(t).find() && !DIGITS.matcher(t).find()
        && !StyleAnalyzer.hasPrivateBits(t) && !hasControl(t);
  }

  static boolean expressionOk(String t) {
    return t != null && !t.isEmpty() && t.length() <= 80 && EXPRESSION.matcher(t).matches() && !StyleAnalyzer.hasPrivateBits(t);
  }

  private static boolean hasControl(String t) {
    for (int i = 0; i < t.length(); i++) { char c = t.charAt(i); if (c < 32 && c != '\n') return true; if (c == 0x7f || (c >= 0x202a && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069)) return true; }
    return false;
  }

  /** Validates a profile file's header. Returns its strength. Used by import and by the backup dry run. */
  public static int checkImport(JSONObject o) {
    if (o == null || !"victorbots-style-profile".equals(o.optString("format")) || o.optInt("v", 0) != 1) throw new IllegalArgumentException("Unsupported profile file version.");
    int strength = o.optInt("strength", 60);
    if (strength < 0 || strength > 100) throw new IllegalArgumentException("Invalid strength in profile file.");
    String name = o.optString("name", "");
    if (name.length() > 80) throw new IllegalArgumentException("The profile name in the file is longer than 80 characters.");
    return strength;
  }

  /**
   * Only what StyleAnalyzer itself produces: known numeric keys (finite, clamped) and word lists whose items pass the same
   * rules (slang, greetings and sign-offs from the fixed vocabularies, single emoji, digit-free words and short phrases).
   */
  public static JSONObject cleanStats(JSONObject in) {
    JSONObject out = new JSONObject();
    if (in == null) return out;
    try {
      for (String k : NUMERIC) {
        Object v = in.opt(k);
        if (!(v instanceof Number)) continue;
        double d = ((Number) v).doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) continue;
        d = Math.max(0, Math.min(100_000, d));
        if (v instanceof Integer || v instanceof Long) out.put(k, (long) d); else out.put(k, d);
      }
      out.put("topEmoji", vocab(in.optJSONArray("topEmoji"), 5, null, true, null));
      out.put("topSlang", vocab(in.optJSONArray("topSlang"), 8, StyleAnalyzer.SLANG, false, null));
      out.put("greetings", vocab(in.optJSONArray("greetings"), 4, StyleAnalyzer.GREET, false, null));
      out.put("signOffs", vocab(in.optJSONArray("signOffs"), 4, StyleAnalyzer.SIGNOFF, false, null));
      out.put("topWords", vocab(in.optJSONArray("topWords"), 15, null, false, WORD));
      out.put("phrases", vocab(in.optJSONArray("phrases"), 10, null, false, PHRASE));
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    return out;
  }

  /** Statistics fit to leave the phone: no word or phrase lists drawn from the owner's messages. */
  static JSONObject shareableStats(JSONObject in) {
    JSONObject c = cleanStats(in);
    c.remove("topWords");
    c.remove("phrases");
    return c;
  }

  private static JSONArray vocab(JSONArray a, int max, Set<String> allowed, boolean emoji, Pattern shape) {
    JSONArray out = new JSONArray();
    for (String s : strings(a)) {
      if (out.length() >= max) break;
      if (s.length() > 60 || StyleAnalyzer.hasDigit(s)) continue;
      if (allowed != null && !allowed.contains(s)) continue;
      if (emoji && !(s.codePointCount(0, s.length()) == 1 && StyleAnalyzer.isEmoji(s.codePointAt(0)))) continue;
      if (shape != null && !shape.matcher(s).matches()) continue;
      if (allowed == null && !emoji && shape == null) continue;
      out.put(s);
    }
    return out;
  }

  private static JSONArray jsonList(List<String> in, int max, int maxLen) {
    JSONArray a = new JSONArray();
    if (in != null) for (String s : in) { if (a.length() >= max) break; if (s != null && !s.isEmpty() && s.length() <= maxLen) a.put(s); }
    return a;
  }

  private static Set<String> strings(JSONArray a) {
    Set<String> out = new LinkedHashSet<>();
    if (a != null) for (int i = 0; i < a.length(); i++) { String s = a.optString(i, ""); if (!s.isEmpty()) out.add(s); }
    return out;
  }

  private static String join(Collection<String> c, int max) {
    List<String> l = new ArrayList<>(c);
    return String.join(", ", l.subList(0, Math.min(max, l.size())));
  }

  private static String listSuffix(JSONArray a, int max) {
    Set<String> s = strings(a);
    return s.isEmpty() ? "" : " (e.g. " + join(s, max).replace(", ", " ") + ")";
  }
}
