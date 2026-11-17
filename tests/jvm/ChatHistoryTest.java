import com.victorious.bots.*;
import java.util.*;
import org.json.*;

/** Regenerate and edit-and-resend: which tail may change, what drops out of prompts, and nothing is deleted. */
public final class ChatHistoryTest {
  static long next = 1;

  static ChatHistory.Msg m(String role, String status, String body) {
    return new ChatHistory.Msg(next++, role, status, "text", body);
  }

  static List<ChatHistory.Msg> list(ChatHistory.Msg... ms) {
    return new ArrayList<>(Arrays.asList(ms));
  }

  public static void main(String[] args) throws Exception {
    // ---- pure tail logic
    ChatHistory.Msg u1 = m("user", "done", "first"), a1 = m("assistant", "done", "reply 1");
    ChatHistory.Msg u2 = m("user", "done", "second"), a2 = m("assistant", "done", "reply 2");
    List<ChatHistory.Msg> chat = list(u1, a1, u2, a2);
    ChatHistory.Replacement r = ChatHistory.forRetry(chat);
    T.check("retry offered after a finished reply", r != null);
    T.eq("retry re-asks the last user message", "second", r.text);
    T.eq("retry replaces the last user message and its reply", Arrays.asList(u2.id, a2.id), r.ids);
    T.eq("retry button sits on the last reply", a2.id, ChatHistory.retryableReplyId(chat));
    T.eq("edit button sits on the last user message", u2.id, ChatHistory.editableUserId(chat));

    ChatHistory.Replacement e = ChatHistory.forEdit(chat, u2.id, "  second, better  ");
    T.eq("edit sends the new text, trimmed", "second, better", e.text);
    T.eq("edit replaces the same tail as retry", Arrays.asList(u2.id, a2.id), e.ids);
    T.eq("an earlier user message cannot be edited", null, ChatHistory.forEdit(chat, u1.id, "x"));
    T.eq("a bot reply cannot be edited", null, ChatHistory.forEdit(chat, a2.id, "x"));
    T.eq("an unknown id cannot be edited", null, ChatHistory.forEdit(chat, 99999, "x"));
    T.eq("empty edit refused", null, ChatHistory.forEdit(chat, u2.id, "   "));
    T.eq("null edit refused", null, ChatHistory.forEdit(chat, u2.id, null));

    // failure notice after a user message: retry and edit both allowed
    ChatHistory.Msg u3 = m("user", "done", "will fail"), err = m("system", "error", "Timeout: no answer");
    List<ChatHistory.Msg> failed = list(u1, a1, u3, err);
    T.eq("retry works on a failed request", Arrays.asList(u3.id, err.id), ChatHistory.forRetry(failed).ids);
    T.eq("retry button sits on the failure notice", err.id, ChatHistory.retryableReplyId(failed));
    T.eq("edit works after a failure", u3.id, ChatHistory.editableUserId(failed));

    // several replies after one user message are all replaced together
    ChatHistory.Msg a2b = m("assistant", "done", "reply 2b");
    T.eq("all replies after the user message go together", 3, ChatHistory.forRetry(list(u1, a1, u2, a2, a2b)).ids.size());

    // pending blocks everything
    ChatHistory.Msg pend = m("system", "pending", "Waiting for X…");
    List<ChatHistory.Msg> waiting = list(u1, a1, u2, pend);
    T.eq("no retry while a request is pending", null, ChatHistory.forRetry(waiting));
    T.eq("no edit while a request is pending", null, ChatHistory.forEdit(waiting, u2.id, "x"));
    T.eq("no editable id while pending", 0L, ChatHistory.editableUserId(waiting));
    T.eq("no retry id while pending", 0L, ChatHistory.retryableReplyId(waiting));

    // user message with no reply yet (for example the app was killed): edit yes, retry no
    List<ChatHistory.Msg> orphan = list(u1, a1, m("user", "done", "unanswered"));
    T.eq("retry needs a reply to replace", null, ChatHistory.forRetry(orphan));
    T.check("edit allowed on an unanswered message", ChatHistory.editableUserId(orphan) != 0);

    // empty or reply-only chats
    T.eq("empty chat: nothing to retry", null, ChatHistory.forRetry(new ArrayList<ChatHistory.Msg>()));
    T.eq("empty chat: nothing to edit", 0L, ChatHistory.editableUserId(new ArrayList<ChatHistory.Msg>()));
    T.eq("reply without a user message is not retryable", null, ChatHistory.forRetry(list(m("assistant", "done", "hello"))));

    // team-style notices are never treated as replies
    ChatHistory.Msg note = new ChatHistory.Msg(next++, "system", "done", "notice", "Round stopped");
    T.eq("a notice after a user message blocks retry", null, ChatHistory.forRetry(list(u1, a1, u2, note)));

    // already replaced messages are invisible to the logic
    ChatHistory.Msg old = new ChatHistory.Msg(next++, "assistant", "replaced", "text", "old reply");
    T.eq("replaced messages are ignored", Arrays.asList(u2.id, a2.id), ChatHistory.forRetry(list(u1, a1, old, u2, a2)).ids);
    T.eq("visible() drops replaced", 2, ChatHistory.visible(list(u1, old, a1)).size() );

    // ---- real SQLite: replacing keeps rows, hides them everywhere, and is atomic
    Fakes.Env env = Fakes.env();
    Db db = env.db;
    long chatId = db.insert("INSERT INTO chats(bot,title,created,uid) VALUES(?,?,?,?)", env.bot, "t", 1L, "chat-uid-1");
    long mu1 = msg(db, chatId, "user", "done", "How do I sort a list?");
    long ma1 = msg(db, chatId, "assistant", "done", "Use sorted().");
    long mu2 = msg(db, chatId, "user", "done", "And in reverse?");
    long ma2 = msg(db, chatId, "assistant", "done", "Pass reverse=True. SECRET-OLD-REPLY");

    List<ChatHistory.Msg> rows = ChatHistory.from(db.rows("SELECT * FROM messages WHERE chat=? ORDER BY id", chatId));
    ChatHistory.Replacement rr = ChatHistory.forRetry(rows);
    T.eq("db rows give the same tail", Arrays.asList(mu2, ma2), rr.ids);

    Memory mem = new Memory(db, env.kv);
    JSONObject bot = db.one("SELECT * FROM bots WHERE id=?", env.bot);
    JSONObject chatRow = db.one("SELECT * FROM chats WHERE id=?", chatId);
    PromptBuilder pb = new PromptBuilder(db, mem);
    String all = turnsText(pb.build(bot, chatRow, null, "And in reverse?", null, "", null).plan.turns);
    T.check("without exclusion the old reply is still in the prompt (control)", all.contains("SECRET-OLD-REPLY"));
    String ex = turnsText(pb.build(bot, chatRow, null, "And in reverse?", null, "", rr.ids).plan.turns);
    T.check("with exclusion the old reply is not in the prompt", !ex.contains("SECRET-OLD-REPLY"));
    T.check("with exclusion the old user message is not duplicated", count(ex, "And in reverse?") == 1);
    T.check("earlier turns are kept", ex.contains("How do I sort a list?") && ex.contains("Use sorted()."));
    T.eq("notIn builds an id list", " AND id NOT IN (4,5)", ChatHistory.notIn(Arrays.asList(4L, 5L)));
    T.eq("notIn with nothing is empty", "", ChatHistory.notIn(null));

    ChatHistory.apply(db, chatId, rr);
    T.eq("replaced rows are kept, not deleted", 4L, db.count("SELECT COUNT(*) n FROM messages WHERE chat=?", chatId));
    T.eq("two rows are marked replaced", 2L, db.count("SELECT COUNT(*) n FROM messages WHERE chat=? AND status='replaced'", chatId));
    T.eq("history queries (status done) no longer see them", 2L, db.count("SELECT COUNT(*) n FROM messages WHERE chat=? AND status='done'", chatId));
    String after = turnsText(pb.build(bot, chatRow, null, "And in reverse?", null, "", null).plan.turns);
    T.check("after replacing, even without exclusion the old reply is gone from prompts", !after.contains("SECRET-OLD-REPLY"));

    String export = Backup.export(db).toString();
    T.check("backup export does not include replaced messages", !export.contains("SECRET-OLD-REPLY"));
    T.check("backup export still includes kept messages", export.contains("Use sorted()."));

    // the new exchange then follows normally and can itself be retried
    long mu3 = msg(db, chatId, "user", "done", "And in reverse?");
    long ma3 = msg(db, chatId, "assistant", "done", "reverse=True works.");
    List<ChatHistory.Msg> now = ChatHistory.from(db.rows("SELECT * FROM messages WHERE chat=? ORDER BY id", chatId));
    T.eq("the new reply is the retryable one", ma3, ChatHistory.retryableReplyId(now));
    T.eq("the new user message is the editable one", mu3, ChatHistory.editableUserId(now));

    // stale or conflicting apply changes nothing
    ChatHistory.Replacement stale = ChatHistory.forRetry(rows);
    try {
      ChatHistory.apply(db, chatId, stale);
      T.check("stale replacement is refused", false);
    } catch (IllegalStateException ex2) {
      T.check("stale replacement is refused", true);
    }
    T.eq("a refused replacement changed nothing", 2L, db.count("SELECT COUNT(*) n FROM messages WHERE chat=? AND status='replaced'", chatId));
    long other = db.insert("INSERT INTO chats(bot,title,created,uid) VALUES(?,?,?,?)", env.bot, "other", 1L, "chat-uid-2");
    long om = msg(db, other, "user", "done", "x");
    long om2 = msg(db, other, "assistant", "done", "y");
    ChatHistory.Replacement foreign = ChatHistory.forRetry(ChatHistory.from(db.rows("SELECT * FROM messages WHERE chat=? ORDER BY id", other)));
    try {
      ChatHistory.apply(db, chatId, foreign);
      T.check("ids from another chat are refused", false);
    } catch (IllegalStateException ex3) {
      T.check("ids from another chat are refused", true);
    }
    T.eq("the other chat is untouched", 0L, db.count("SELECT COUNT(*) n FROM messages WHERE chat=? AND status='replaced'", other));

    // a half-valid replacement rolls back as a whole
    long p1 = msg(db, other, "user", "done", "z");
    long p2 = msg(db, other, "assistant", "pending", "wait");
    List<Long> mixed = Arrays.asList(p1, p2);
    ChatHistory.Replacement bad = ChatHistory.forEdit(ChatHistory.from(db.rows("SELECT * FROM messages WHERE chat=? ORDER BY id", other)), p1, "q");
    T.eq("a pending message blocks the edit before any write", null, bad);
    try {
      ChatHistory.apply(db, other, new FakeRep(p1, mixed).get());
      T.check("a replacement containing a pending row is refused", false);
    } catch (IllegalStateException ex4) {
      T.check("a replacement containing a pending row is refused", true);
    }
    T.eq("atomic: the valid row was not left half replaced", "done", db.one("SELECT status s FROM messages WHERE id=?", p1).optString("s"));

    T.done();
  }

  /** Builds a Replacement through the public API only: the first row of a one-row chat. */
  static final class FakeRep {
    final long id;
    final List<Long> ids;

    FakeRep(long id, List<Long> ids) {
      this.id = id;
      this.ids = ids;
    }

    ChatHistory.Replacement get() {
      List<ChatHistory.Msg> only = new ArrayList<>();
      for (long i : ids) only.add(new ChatHistory.Msg(i, i == id ? "user" : "assistant", "done", "text", "x"));
      return ChatHistory.forRetry(only);
    }
  }

  static long msg(Db db, long chat, String role, String status, String body) {
    return db.insert("INSERT INTO messages(chat,role,body,status,created,author,meta) VALUES(?,?,?,?,?,0,'{}')", chat, role, body, status, System.nanoTime());
  }

  static String turnsText(List<Rules.Turn> turns) {
    StringBuilder b = new StringBuilder();
    for (Rules.Turn t : turns) b.append(t.role).append(": ").append(t.content).append("\n");
    return b.toString();
  }

  static int count(String hay, String needle) {
    int n = 0, i = 0;
    while ((i = hay.indexOf(needle, i)) >= 0) {
      n++;
      i += needle.length();
    }
    return n;
  }
}
