package com.victorious.bots;

import java.util.*;
import org.json.*;

/**
 * Model-assisted memory work: suggestions and summaries. Both go through the Gateway, so they obey
 * the owner's inference policy exactly like chat; unlike OpenGrokBot's fixed-model memory updater,
 * nothing is uploaded unless the destination is allowed, and suggestions stay inert until accepted.
 */
public final class MemoryAssistant {
  private final Db db;
  private final Gateway gateway;
  private final Memory memory;

  public MemoryAssistant(Db db, Gateway gateway, Memory memory) {
    this.db = db;
    this.gateway = gateway;
    this.memory = memory;
  }

  /** Parses a model reply that should be a JSON array of strings, tolerating prose around it. */
  public static List<String> parseFacts(String reply) {
    List<String> out = new ArrayList<>();
    int a = reply.indexOf('['), z = reply.lastIndexOf(']');
    if (a < 0 || z <= a) return out;
    try {
      JSONArray arr = new JSONArray(reply.substring(a, z + 1));
      for (int i = 0; i < arr.length() && out.size() < 5; i++) {
        Object o = arr.get(i);
        if (!(o instanceof String)) continue;
        String s = ((String) o).trim().replaceAll("\\s+", " ");
        if (s.length() >= 6 && s.length() <= Memory.MAX_BODY) out.add(s);
      }
    } catch (JSONException ignored) {
    }
    return out;
  }

  /** Suggests memories from ONE chat of ONE bot. Returns how many new suggestions were stored. */
  public int suggest(long botId, long chatId) throws ProviderError {
    JSONObject bot = db.one("SELECT * FROM bots WHERE id=?", botId);
    if (bot == null) throw new IllegalArgumentException("Bot no longer exists.");
    List<JSONObject> msgs = db.rows("SELECT id,role,body FROM messages WHERE chat=? AND status='done' AND role='user' ORDER BY id DESC LIMIT 12", chatId);
    if (msgs.isEmpty()) throw new IllegalArgumentException("This conversation has no messages from you yet.");
    Collections.reverse(msgs);
    StringBuilder t = new StringBuilder();
    for (JSONObject m : msgs) t.append("- ").append(m.optString("body").replace('\n', ' ')).append('\n');
    String transcript = t.length() > 6000 ? t.substring(0, 6000) : t.toString();
    Gateway.Req q = new Gateway.Req();
    q.purpose = Policy.Purpose.MEMORY;
    q.botId = botId;
    q.turns = Arrays.asList(
        new Rules.Turn("system", "Extract up to 5 durable facts or stable preferences the OWNER stated about themselves, their projects or how they want work done. Ignore anything that is a one-off request, a question, a secret, a password or a key. Reply with ONLY a JSON array of short strings. Reply [] if there is nothing durable."),
        new Rules.Turn("user", Untrusted.wrap("the owner's recent messages", transcript)));
    Gateway.Result res = gateway.run(q);
    int added = 0;
    long label = chatId;
    JSONObject chat = db.one("SELECT title FROM chats WHERE id=?", chatId);
    for (String fact : parseFacts(res.reply.text)) {
      if (looksSecret(fact)) continue;
      long before = db.count("SELECT COUNT(*) n FROM memories");
      memory.add("bot", botId, "", fact, "suggested", "suggested", chatId, msgs.get(msgs.size() - 1).optLong("id"), "chat “" + (chat == null ? label : chat.optString("title")) + "”");
      if (db.count("SELECT COUNT(*) n FROM memories") > before) added++;
    }
    return added;
  }

  static boolean looksSecret(String s) {
    return !Redact.text(s).equals(s) || s.toLowerCase(Locale.ROOT).matches(".*(password|passphrase|private key|api key|secret).*");
  }

  /** Summarises turns that no longer fit the budget; stored on the chat and always labelled generated. */
  public String summarize(long botId, long chatId, int olderCount) throws ProviderError {
    List<JSONObject> msgs = db.rows("SELECT role,body FROM messages WHERE chat=? AND status='done' ORDER BY id LIMIT " + Math.max(1, Math.min(olderCount, 60)), chatId);
    if (msgs.isEmpty()) throw new IllegalArgumentException("Nothing to summarise.");
    StringBuilder t = new StringBuilder();
    for (JSONObject m : msgs) t.append(m.optString("role").toUpperCase(Locale.ROOT)).append(": ").append(m.optString("body").replace('\n', ' ')).append('\n');
    String transcript = t.length() > 12000 ? t.substring(0, 12000) : t.toString();
    Gateway.Req q = new Gateway.Req();
    q.purpose = Policy.Purpose.SUMMARY;
    q.botId = botId;
    q.turns = Arrays.asList(
        new Rules.Turn("system", "Summarise the conversation in at most 150 words: decisions made, open questions, facts the owner gave. Do not add anything that is not in it."),
        new Rules.Turn("user", Untrusted.wrap("the conversation to summarise", transcript)));
    Gateway.Result res = gateway.run(q);
    String s = res.reply.text.trim();
    if (s.length() > 1500) s = s.substring(0, 1500);
    db.update("UPDATE chats SET summary=? WHERE id=?", s, chatId);
    return s;
  }
}
