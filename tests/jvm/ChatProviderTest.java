import com.victorious.bots.*;
import com.victorious.bots.Policy.*;
import java.util.*;
import org.json.JSONObject;

/** Per-chat provider switch (roadmap PR order step 2): persistence, inheritance wording, Gateway routing, policy kept. */
public final class ChatProviderTest {
  static final List<Rules.Turn> TURNS = Arrays.asList(new Rules.Turn("system", "s"), new Rules.Turn("user", "hello"));

  static Gateway.Result chat(Fakes.Env e, long chatId, boolean confirmed) throws Exception {
    Gateway.Req r = new Gateway.Req();
    r.purpose = Purpose.CHAT;
    r.botId = e.bot;
    r.turns = TURNS;
    r.ownerConfirmed = confirmed;
    r.profileOverride = ChatProvider.route(e.db, chatId, e.bot);
    return e.gateway.run(r);
  }

  static String botRow(Fakes.Env e) {
    JSONObject b = e.db.one("SELECT profile,model,policy FROM bots WHERE id=?", e.bot);
    return b.optString("profile", "null") + "|" + b.optString("model") + "|" + b.optString("policy");
  }

  public static void main(String[] a) throws Exception {
    Fakes.Env e = Fakes.env();
    e.profiles.setDefault(e.cloud);
    long c1 = e.db.insert("INSERT INTO chats(bot,title,created,uid) VALUES(?,?,?,?)", e.bot, "One", 1L, "c1");
    long c2 = e.db.insert("INSERT INTO chats(bot,title,created,uid) VALUES(?,?,?,?)", e.bot, "Two", 2L, "c2");
    long team = e.db.insert("INSERT INTO chats(bot,title,created,kind,uid) VALUES(NULL,?,?,'team',?)", "Team", 3L, "t1");

    // 1. Nothing picked: the chat inherits the bot, which inherits the workspace default.
    T.eq("new chat has no pick", 0L, ChatProvider.chosen(e.db, c1));
    T.eq("no pick routes through the bot's own resolution", 0L, ChatProvider.route(e.db, c1, e.bot));
    ChatProvider.Effective ef = ChatProvider.effective(e.db, e.profiles, c1, e.bot);
    T.eq("inherits workspace default", ChatProvider.Source.DEFAULT, ef.source);
    T.eq("default profile shown", e.cloud, ef.profile.id);
    T.eq("wording for default", "Provider: OpenAI-style · inherited from the workspace default", ef.describe());
    T.eq("default path goes to the default (cloud)", "https://api.example.com/v1|cloud-key-1234|cloud-model", sendOne(e, c1));

    // 2. Bot has its own provider: wording says so.
    e.db.update("UPDATE bots SET profile=?,model='bot-model' WHERE id=?", e.cloud, e.bot);
    ef = ChatProvider.effective(e.db, e.profiles, c1, e.bot);
    T.eq("inherits the bot", ChatProvider.Source.BOT, ef.source);
    T.eq("wording for bot", "Provider: OpenAI-style · inherited from this bot", ef.describe());

    // 3. Pick the PC profile for chat 1 only: persists, routes there, never edits the bot.
    String before = botRow(e);
    String defBefore = e.kv.get(Profiles.DEFAULT_KEY, "");
    ChatProvider.set(e.db, e.profiles, c1, e.pc);
    T.eq("pick persists", e.pc, ChatProvider.chosen(e.db, c1));
    T.eq("other chat unaffected", 0L, ChatProvider.chosen(e.db, c2));
    T.eq("switch never edits the bot", before, botRow(e));
    T.eq("switch never edits the workspace default", defBefore, e.kv.get(Profiles.DEFAULT_KEY, ""));
    ef = ChatProvider.effective(e.db, e.profiles, c1, e.bot);
    T.eq("wording for chat pick", "Provider: My PC · chosen for this chat", ef.describe());
    T.eq("chat 1 goes to the PC with the PC token and the PC profile's model", "https://192.168.1.20:8443/v1|pc-token-5678|pc-model", sendOne(e, c1));
    T.eq("chat 2 still goes to the bot's provider with the bot's model", "https://api.example.com/v1|cloud-key-1234|bot-model", sendOne(e, c2));
    JSONObject led = e.db.one("SELECT host,kind,profile FROM usage ORDER BY id DESC LIMIT 1 OFFSET 1");
    T.eq("ledger records the chat's destination", "PC", led.optString("kind"));

    // 4. Picking the bot's own profile keeps the bot's model override.
    ChatProvider.set(e.db, e.profiles, c2, e.cloud);
    T.eq("pick of the bot's own profile routes as the bot", 0L, ChatProvider.route(e.db, c2, e.bot));
    T.eq("  and keeps the bot's model", "https://api.example.com/v1|cloud-key-1234|bot-model", sendOne(e, c2));

    // 5. Policy first: a switch can never loosen policy.
    e.kv.put(Profiles.GLOBAL_POLICY, Mode.PC_ONLY.id);
    T.eq("PC-only: chat switched to PC still allowed", "https://192.168.1.20:8443/v1|pc-token-5678|pc-model", sendOne(e, c1));
    e.kv.put(Profiles.GLOBAL_POLICY, Mode.ALLOW_CLOUD.id);
    e.db.update("UPDATE bots SET policy='pc_only' WHERE id=?", e.bot);
    ChatProvider.set(e.db, e.profiles, c1, e.cloud);
    e.calls.clear();
    T.check("bot stricter policy still refuses a chat switched to cloud", refused(e, c1, true));
    T.eq("  and made no network call", 0, e.calls.size());
    e.db.update("UPDATE bots SET policy='' WHERE id=?", e.bot);
    e.kv.put(Profiles.GLOBAL_POLICY, Mode.PC_PREFERRED.id);
    e.calls.clear();
    T.check("PC-first: a chat switched to cloud is refused without confirmation", refused(e, c1, false));
    T.eq("  no call", 0, e.calls.size());
    e.kv.put(Profiles.GLOBAL_POLICY, Mode.ALLOW_CLOUD.id);

    // 6. DroidCommand pick: Gateway plans it (policy first) and refuses direct network use.
    ChatProvider.set(e.db, e.profiles, c1, e.dca);
    Gateway.Plan p = e.gateway.plan(e.bot, ChatProvider.route(e.db, c1, e.bot), Purpose.CHAT, false, "");
    T.eq("DroidCommand pick plans the COMPANION_ASSIST path", Kind.DROIDCOMMAND, p.profile.kind);
    e.calls.clear();
    T.check("Gateway.run never sends DroidCommand over HTTP", refused(e, c1, true));
    T.eq("  no HTTP call", 0, e.calls.size());
    e.kv.put(Profiles.GLOBAL_POLICY, Mode.PC_ONLY.id);
    T.eq("PC-only refuses a DroidCommand pick at plan time", false, e.gateway.plan(e.bot, ChatProvider.route(e.db, c1, e.bot), Purpose.CHAT, false, "").decision.allowed);
    e.kv.put(Profiles.GLOBAL_POLICY, Mode.ALLOW_CLOUD.id);

    // 7. Background purposes ignore the chat pick (they plan with the bot only).
    T.eq("memory planning stays on the bot's provider", e.cloud, e.gateway.plan(e.bot, 0, Purpose.MEMORY, false, "").profile.id);

    // 8. Clearing, validation, team chats.
    ChatProvider.set(e.db, e.profiles, c1, 0);
    T.eq("clear returns to inherit", 0L, ChatProvider.chosen(e.db, c1));
    T.throwsIae("unknown profile refused", () -> ChatProvider.set(e.db, e.profiles, c1, 9999));
    T.throwsIae("team chat refused", () -> ChatProvider.set(e.db, e.profiles, team, e.pc));
    T.throwsIae("unknown chat refused", () -> ChatProvider.set(e.db, e.profiles, 12345, e.pc));
    T.eq("team chat never reports a pick", 0L, ChatProvider.chosen(e.db, team));

    // 9. Deleting the picked profile clears the pick; a dangling id is ignored.
    ChatProvider.set(e.db, e.profiles, c1, e.pc);
    e.profiles.delete(e.pc);
    T.eq("deleting the profile clears the chat pick", true, e.db.one("SELECT profile FROM chats WHERE id=?", c1).optString("profile", "").isEmpty());
    e.db.update("UPDATE chats SET profile=424242 WHERE id=?", c2);
    T.eq("dangling id is ignored", 0L, ChatProvider.chosen(e.db, c2));
    T.eq("dangling id falls back to the bot", ChatProvider.Source.BOT, ChatProvider.effective(e.db, e.profiles, c2, e.bot).source);

    // 10. Picker: inherit first, naming what it inherits; every saved profile; no keys or endpoints.
    List<ChatProvider.Option> opts = ChatProvider.options(e.db, e.profiles, e.bot);
    T.eq("first option is inherit", 0L, opts.get(0).id);
    T.eq("inherit names its source", "Inherit (OpenAI-style, from this bot)", opts.get(0).label);
    T.eq("all profiles listed", e.profiles.all().size() + 1, opts.size());
    for (ChatProvider.Option o : opts) T.check("option label has no key or URL: " + o.label, !o.label.contains("key") && !o.label.contains("https://"));

    // 11. Backups never carry the chat pick (a local id).
    ChatProvider.set(e.db, e.profiles, c1, e.cloud);
    org.json.JSONArray ch = Backup.export(e.db).getJSONArray("chats");
    boolean leaked = false;
    for (int i = 0; i < ch.length(); i++) leaked |= ch.getJSONObject(i).has("profile");
    T.check("export omits the chat pick", !leaked);
    T.done();
  }

  static String sendOne(Fakes.Env e, long chatId) throws Exception {
    e.calls.clear();
    chat(e, chatId, false);
    return e.calls.get(0);
  }

  static boolean refused(Fakes.Env e, long chatId, boolean confirmed) throws Exception {
    try {
      chat(e, chatId, confirmed);
      return false;
    } catch (Gateway.Denied d) {
      return true;
    }
  }
}
