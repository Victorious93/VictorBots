package com.victorious.bots;

import java.util.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Victorious Framework: configuration of communication-specialised bots. VictorBots owns WHAT a bot is for (mode, style
 * profile, which contacts and conversations); it does not own the authority to send. That authority is an owner-created
 * grant in droidcommand-AI plus an independent rule in VictorSMS. The checks here are advisory and exist so a bot that is
 * paused, outside its hours or over its own limit never even asks. Nothing in this class can send, call or approve.
 */
public final class CommsBots {
  public enum Mode { MANUAL, DRAFT, APPROVAL, AUTO, AUTONOMOUS, AWAY, CUSTOM }

  public static final class Config {
    public long bot;
    public Mode mode = Mode.APPROVAL; // a new communications bot never starts with unattended sending
    public String profileUid = "";
    public List<String> contacts = new ArrayList<>();
    public int hoursStart = -1, hoursEnd = -1, maxPerHour = 4;
    public boolean paused, disclose = true, voiceNeedsApproval = true;
  }

  public static final class Decision {
    public final boolean allowed;
    public final String reason;

    Decision(boolean allowed, String reason) {
      this.allowed = allowed;
      this.reason = reason;
    }
  }

  private static final String ALL_PAUSED = "all_paused";
  private final Db db;

  public CommsBots(Db db) {
    this.db = db;
  }

  public Config get(long bot) {
    Config c = new Config();
    c.bot = bot;
    JSONObject r = db.one("SELECT * FROM comm_bots WHERE bot=?", bot);
    if (r == null) return c;
    try {
      c.mode = parseMode(r.optString("mode"));
      c.profileUid = r.optString("profile_uid");
      JSONArray a = new JSONArray(r.optString("contacts", "[]"));
      for (int i = 0; i < a.length(); i++) c.contacts.add(a.getString(i));
    } catch (JSONException ignored) { }
    c.hoursStart = (int) r.optLong("hours_start", -1);
    c.hoursEnd = (int) r.optLong("hours_end", -1);
    c.maxPerHour = (int) r.optLong("max_per_hour", 4);
    c.paused = r.optLong("paused") == 1;
    c.disclose = r.optLong("disclose", 1) == 1;
    c.voiceNeedsApproval = r.optLong("voice_needs_approval", 1) == 1;
    return c;
  }

  /** An unreadable stored mode falls back to the safest one, never to a more permissive one. */
  static Mode parseMode(String s) {
    try { return Mode.valueOf(s); } catch (Exception e) { return Mode.APPROVAL; }
  }

  public void save(Config c) {
    if (c.hoursStart != c.hoursEnd && (c.hoursStart < 0 || c.hoursEnd < 0 || c.hoursStart > 23 || c.hoursEnd > 23)) throw new IllegalArgumentException("Hours must be 0 to 23, or both unset.");
    if (c.maxPerHour < 1 || c.maxPerHour > 60) throw new IllegalArgumentException("Replies per hour must be 1 to 60.");
    JSONArray contacts = new JSONArray();
    for (String s : c.contacts) if (contacts.length() < 500 && s.length() <= 80) contacts.put(s);
    db.update("INSERT OR REPLACE INTO comm_bots(bot,mode,profile_uid,contacts,hours_start,hours_end,max_per_hour,paused,disclose,voice_needs_approval,updated) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        c.bot, c.mode.name(), c.profileUid == null ? "" : c.profileUid, contacts.toString(), c.hoursStart, c.hoursEnd, c.maxPerHour, c.paused, c.disclose, c.voiceNeedsApproval, System.currentTimeMillis());
  }

  // ---- one bot per conversation ----

  /** Assigns a conversation to a bot. Two bots can never own the same conversation, so they can never answer each other or compete. */
  public void assign(long bot, String conversation) {
    if (conversation == null || conversation.isEmpty() || conversation.length() > 80) throw new IllegalArgumentException("Invalid conversation.");
    db.tx(() -> {
      JSONObject cur = db.one("SELECT bot FROM comm_assign WHERE conversation=?", conversation);
      if (cur != null && cur.optLong("bot") != bot) throw new IllegalStateException("Another bot already handles this conversation. Release it first.");
      if (cur == null) db.insert("INSERT INTO comm_assign(conversation,bot,created) VALUES(?,?,?)", conversation, bot, System.currentTimeMillis());
      return null;
    });
  }

  public void release(String conversation) {
    db.update("DELETE FROM comm_assign WHERE conversation=?", conversation);
  }

  public Long botFor(String conversation) {
    JSONObject r = db.one("SELECT bot FROM comm_assign WHERE conversation=?", conversation);
    return r == null ? null : r.optLong("bot");
  }

  // ---- stop controls ----

  public void pauseAll() {
    db.update("INSERT OR REPLACE INTO comm_state(key,value) VALUES(?,?)", ALL_PAUSED, "1");
  }

  /** Only an explicit owner action resumes; individual pauses stay as they were. */
  public void resumeAll() {
    db.update("INSERT OR REPLACE INTO comm_state(key,value) VALUES(?,?)", ALL_PAUSED, "0");
  }

  public boolean allPaused() {
    JSONObject r = db.one("SELECT value FROM comm_state WHERE key=?", ALL_PAUSED);
    return r != null && "1".equals(r.optString("value"));
  }

  public void pause(long bot, boolean paused) {
    Config c = get(bot);
    c.paused = paused;
    save(c);
  }

  /**
   * Advisory pre-check before a bot asks droidcommand-AI to send anything. The gateway and the grant still decide;
   * this only stops a bot that is paused, off-hours or over its own hourly limit from asking at all.
   */
  public Decision mayRequestSend(long bot, long nowMs, int zoneOffsetMinutes) {
    if (allPaused()) return new Decision(false, "All communication bots are paused.");
    Config c = get(bot);
    if (c.paused) return new Decision(false, "This bot is paused.");
    if (c.mode == Mode.MANUAL) return new Decision(false, "Manual assistant mode never sends.");
    if (c.hoursStart >= 0) {
      int h = (int) (((nowMs / 60_000L + zoneOffsetMinutes) / 60) % 24);
      if (h < 0) h += 24;
      boolean in = c.hoursStart == c.hoursEnd || (c.hoursStart < c.hoursEnd ? h >= c.hoursStart && h < c.hoursEnd : h >= c.hoursStart || h < c.hoursEnd);
      if (!in) return new Decision(false, "Outside this bot's operating hours.");
    }
    long recent = db.count("SELECT COUNT(*) n FROM comm_activity WHERE bot=? AND kind='send_requested' AND created>?", bot, nowMs - 3_600_000L);
    if (recent >= c.maxPerHour) return new Decision(false, "This bot reached its limit of " + c.maxPerHour + " requests per hour.");
    return new Decision(true, "");
  }

  public void log(long bot, String kind, String detail) {
    db.insert("INSERT INTO comm_activity(bot,kind,detail,created) VALUES(?,?,?,?)", bot, kind, Redact.snippet(detail, 300), System.currentTimeMillis());
    db.update("DELETE FROM comm_activity WHERE bot=? AND id NOT IN (SELECT id FROM comm_activity WHERE bot=? ORDER BY id DESC LIMIT 500)", bot, bot);
  }

  public void logAt(long bot, String kind, String detail, long at) {
    db.insert("INSERT INTO comm_activity(bot,kind,detail,created) VALUES(?,?,?,?)", bot, kind, Redact.snippet(detail, 300), at);
  }

  public List<JSONObject> activity(long bot, int limit) {
    return db.rows("SELECT kind,detail,created FROM comm_activity WHERE bot=? ORDER BY id DESC LIMIT " + Math.max(1, Math.min(limit, 200)), bot);
  }
}
