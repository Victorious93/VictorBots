package com.victorious.bots;

import java.util.*;
import org.json.JSONObject;

/**
 * Builds the exact request for one bot: its instructions, owner-approved memory, the (labelled)
 * conversation summary, whole recent turns that fit the token budget, and the current message.
 * Other bots' messages and attachments are quoted as untrusted data.
 */
public final class PromptBuilder {
  public static final class Built {
    public Rules.Plan plan;
    public List<Memory.Item> memory = new ArrayList<>();
    public boolean summaryUsed;
  }

  public static final class Attachment {
    public String name, text;
    public List<String> images = new ArrayList<>();
    public boolean truncated;
  }

  private final Db db;
  private final Memory mem;

  public PromptBuilder(Db db, Memory mem) {
    this.db = db;
    this.mem = mem;
  }

  public Built build(JSONObject bot, JSONObject chat, Profile profile, String input, List<Attachment> files, String extraSystem) {
    long botId = bot.optLong("id");
    double cpt = profile == null ? 3.0 : profile.charsPerToken();
    String basis = profile == null ? "assumed" : profile.budgetBasis() + "; " + profile.tokenBasis();
    int window = profile == null ? 4096 : profile.budgetTokens();
    int reserve = Math.max(256, Math.min(window / 4, 2048));
    Built b = new Built();
    b.memory = mem.retrieve(botId, bot.optString("project"), input, mem.budgetTokens(), cpt);
    StringBuilder system = new StringBuilder(bot.optString("instructions"));
    system.append(Memory.block(b.memory));
    String summary = chat == null ? "" : chat.optString("summary");
    if (!summary.isEmpty()) {
      system.append("\n\n").append(Untrusted.wrap("model-generated summary of earlier turns in this conversation (may be inaccurate)", summary));
      b.summaryUsed = true;
    }
    if (extraSystem != null && !extraSystem.isEmpty()) system.append("\n\n").append(extraSystem);

    StringBuilder user = new StringBuilder(input);
    List<String> images = new ArrayList<>();
    if (files != null)
      for (Attachment f : files) {
        if (f.text != null && !f.text.isEmpty())
          user.append("\n\n").append(Untrusted.wrap("attached file " + f.name + (f.truncated ? " (shortened)" : ""), f.text));
        images.addAll(f.images);
      }

    List<Rules.Turn> history = new ArrayList<>();
    if (chat != null) {
      boolean team = chat.optString("kind").equals("team");
      for (JSONObject m : db.rows("SELECT role,body,author FROM messages WHERE chat=? AND status='done' ORDER BY id", chat.optLong("id"))) {
        long author = m.optLong("author");
        String body = m.optString("body");
        if (team && author != 0 && author != botId) {
          JSONObject other = db.one("SELECT name FROM bots WHERE id=?", author);
          history.add(new Rules.Turn("user", Untrusted.wrap("peer message from the bot " + (other == null ? "?" : other.optString("name")), body)));
        } else if (team && author == botId) history.add(new Rules.Turn("assistant", body));
        else history.add(new Rules.Turn(m.optString("role"), body));
      }
    }
    b.plan = Rules.plan(history, system.toString(), user.toString(), window, reserve, cpt, basis);
    if (!images.isEmpty()) {
      List<Rules.Turn> t = b.plan.turns;
      Rules.Turn last = t.get(t.size() - 1);
      t.set(t.size() - 1, new Rules.Turn(last.role, last.content, images));
    }
    return b;
  }
}
