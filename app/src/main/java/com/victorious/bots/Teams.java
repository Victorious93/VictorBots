package com.victorious.bots;

import java.util.*;
import org.json.*;

/** Team chat configuration and membership. A team chat is an ordinary chat row with kind='team'. */
public final class Teams {
  public enum Mode {
    MENTIONS("Mentions only", "Only bots you @mention answer. Nothing else happens."),
    CHIEF("Chief of Staff routes", "The Chief of Staff picks the one to three most relevant bots (a routing model call if policy allows, otherwise a local keyword match)."),
    ALL("All unmuted bots", "Every unmuted bot answers once, in order, up to the turn limit."),
    REVIEW("Developer → QA review", "One bot answers, then one bot reviews that answer once. It never loops.");

    public final String label, explanation;

    Mode(String l, String e) {
      label = l;
      explanation = e;
    }
  }

  public static final class Config {
    public Mode mode = Mode.MENTIONS;
    public int maxTurns = 4, maxHops = 2;
    public long timeBudgetMs = 180_000, tokenBudget = 8000;
    public long chiefBot, devBot, qaBot;

    public JSONObject json() {
      try {
        return new JSONObject().put("mode", mode.name()).put("max_turns", maxTurns).put("max_hops", maxHops).put("time_ms", timeBudgetMs).put("token_budget", tokenBudget).put("chief", chiefBot).put("dev", devBot).put("qa", qaBot);
      } catch (JSONException e) {
        throw new IllegalStateException(e);
      }
    }

    public static Config parse(String s) {
      Config c = new Config();
      try {
        JSONObject o = new JSONObject(s == null || s.isEmpty() ? "{}" : s);
        try {
          c.mode = Mode.valueOf(o.optString("mode", "MENTIONS"));
        } catch (IllegalArgumentException e) {
          c.mode = Mode.MENTIONS;
        }
        c.maxTurns = clamp(o.optInt("max_turns", 4), 1, 8);
        c.maxHops = clamp(o.optInt("max_hops", 2), 0, 4);
        c.timeBudgetMs = clamp(o.optLong("time_ms", 180_000), 10_000, 900_000);
        c.tokenBudget = clamp(o.optLong("token_budget", 8000), 500, 100_000);
        c.chiefBot = o.optLong("chief");
        c.devBot = o.optLong("dev");
        c.qaBot = o.optLong("qa");
      } catch (JSONException ignored) {
      }
      return c;
    }

    private static int clamp(int v, int lo, int hi) {
      return Math.max(lo, Math.min(hi, v));
    }

    private static long clamp(long v, long lo, long hi) {
      return Math.max(lo, Math.min(hi, v));
    }
  }

  public static long create(Db db, String title, List<Long> botIds, Config cfg) {
    Rules.length(title, 80, "Team name");
    if (botIds.size() < 2) throw new IllegalArgumentException("A team chat needs at least two bots.");
    if (botIds.size() > 8) throw new IllegalArgumentException("A team chat can have at most 8 bots.");
    return db.tx(() -> {
      long id = db.insert("INSERT INTO chats(bot,title,created,kind,config,uid) VALUES(NULL,?,?,'team',?,?)", title.trim(), System.currentTimeMillis(), cfg.json().toString(), Ids.uid());
      for (long b : new LinkedHashSet<>(botIds)) {
        if (db.count("SELECT COUNT(*) n FROM bots WHERE id=?", b) == 0) throw new IllegalArgumentException("A selected bot no longer exists.");
        db.insert("INSERT INTO chat_members(chat,bot,muted) VALUES(?,?,0)", id, b);
      }
      return id;
    });
  }

  public static List<Mentions.Member> members(Db db, long chat) {
    List<Mentions.Member> out = new ArrayList<>();
    for (JSONObject r : db.rows("SELECT m.bot id,b.name name,m.muted muted FROM chat_members m JOIN bots b ON b.id=m.bot WHERE m.chat=? ORDER BY b.id", chat))
      out.add(new Mentions.Member(r.optLong("id"), r.optString("name"), r.optInt("muted") == 1));
    return out;
  }

  public static void setMuted(Db db, long chat, long bot, boolean muted) {
    db.update("UPDATE chat_members SET muted=? WHERE chat=? AND bot=?", muted ? 1 : 0, chat, bot);
  }

  public static void addMember(Db db, long chat, long bot) {
    if (db.count("SELECT COUNT(*) n FROM chat_members WHERE chat=?", chat) >= 8) throw new IllegalArgumentException("A team chat can have at most 8 bots.");
    if (db.count("SELECT COUNT(*) n FROM chat_members WHERE chat=? AND bot=?", chat, bot) == 0) db.insert("INSERT INTO chat_members(chat,bot,muted) VALUES(?,?,0)", chat, bot);
  }

  public static void removeMember(Db db, long chat, long bot) {
    if (db.count("SELECT COUNT(*) n FROM chat_members WHERE chat=?", chat) <= 2) throw new IllegalArgumentException("A team chat keeps at least two bots. Delete the chat instead.");
    db.update("DELETE FROM chat_members WHERE chat=? AND bot=?", chat, bot);
  }

  public static void saveConfig(Db db, long chat, Config c) {
    db.update("UPDATE chats SET config=? WHERE id=? AND kind='team'", c.json().toString(), chat);
  }

  private Teams() {}
}
