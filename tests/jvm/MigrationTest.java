import com.victorious.bots.*;
import java.util.*;
import org.json.JSONObject;

public final class MigrationTest {
  public static void main(String[] a) throws Exception {
    JdbcDb db = new JdbcDb("jdbc:sqlite::memory:");
    db.exec("PRAGMA foreign_keys=ON");
    // Build a populated version-1 database exactly as 0.1.0 shipped it.
    Migrations.run(db, Migrations.v1());
    long b1 = db.insert("INSERT INTO bots(name,description,instructions,avatar,image) VALUES(?,?,?,?,?)", "Eng", "d", "instr", "⌘", "avatar-1.png");
    long b2 = db.insert("INSERT INTO bots(name,description,instructions,avatar) VALUES(?,?,?,?)", "QA", "d2", "i2", "◎");
    long c1 = db.insert("INSERT INTO chats(bot,title,created) VALUES(?,?,?)", b1, "First chat", 1000L);
    long c2 = db.insert("INSERT INTO chats(bot,title,created) VALUES(?,?,?)", b2, "Second chat", 2000L);
    for (int i = 0; i < 5; i++)
      db.insert("INSERT INTO messages(chat,role,body,status,created) VALUES(?,?,?,?,?)", c1, i % 2 == 0 ? "user" : "assistant", "m" + i, "done", 100L + i);
    db.insert("INSERT INTO messages(chat,role,body,status,created) VALUES(?,?,?,?,?)", c2, "user", "hello", "done", 5L);
    db.insert("INSERT INTO tasks(title,body,status,created) VALUES(?,?,?,?)", "T", "body", "Draft", 7L);

    // Migrate with foreign keys off (as the Android helper does), then verify integrity.
    db.exec("PRAGMA foreign_keys=OFF");
    db.tx(() -> { Migrations.upgrade(db, 1); return null; });
    db.exec("PRAGMA foreign_keys=ON");

    T.eq("bots preserved", 2L, db.count("SELECT COUNT(*) n FROM bots"));
    T.eq("bot fields preserved", "instr", db.one("SELECT instructions i FROM bots WHERE id=?", b1).optString("i"));
    T.eq("avatar image preserved", "avatar-1.png", db.one("SELECT image i FROM bots WHERE id=?", b1).optString("i"));
    T.eq("chats preserved", 2L, db.count("SELECT COUNT(*) n FROM chats"));
    T.eq("chat id preserved", "First chat", db.one("SELECT title t FROM chats WHERE id=?", c1).optString("t"));
    T.eq("chat bot link preserved", b2, db.one("SELECT bot b FROM chats WHERE id=?", c2).optLong("b"));
    T.eq("messages preserved (not cascaded away)", 6L, db.count("SELECT COUNT(*) n FROM messages"));
    T.eq("message order+body preserved", "m3", db.rows("SELECT body FROM messages WHERE chat=? ORDER BY id", c1).get(3).optString("body"));
    T.eq("tasks preserved", 1L, db.count("SELECT COUNT(*) n FROM tasks"));
    T.check("new bot uid assigned and unique", db.count("SELECT COUNT(DISTINCT uid) n FROM bots WHERE length(uid)=32") == 2);
    T.check("new chat uid assigned", db.count("SELECT COUNT(DISTINCT uid) n FROM chats WHERE length(uid)=32") == 2);
    T.eq("foreign_key_check clean", 0, db.rows("PRAGMA foreign_key_check").size());
    T.eq("defaults: bot inherits profile", true, db.one("SELECT profile p FROM bots WHERE id=?", b1).optString("p", "").isEmpty());
    T.eq("defaults: bot policy inherits", "", db.one("SELECT policy p FROM bots WHERE id=?", b1).optString("p", ""));

    // Cascades still work after the chats rebuild.
    db.update("DELETE FROM chats WHERE id=?", c2);
    T.eq("messages cascade from rebuilt chats", 5L, db.count("SELECT COUNT(*) n FROM messages"));
    db.update("DELETE FROM bots WHERE id=?", b1);
    T.eq("chats cascade from bots", 0L, db.count("SELECT COUNT(*) n FROM chats"));
    T.eq("messages cascade transitively", 0L, db.count("SELECT COUNT(*) n FROM messages"));

    // Team chat can exist without an owning bot (the reason for the rebuild).
    long team = db.insert("INSERT INTO chats(bot,title,created,kind,uid) VALUES(NULL,?,?,'team',?)", "Team", 1L, "u1");
    T.check("team chat without owner bot", team > 0);

    // Fresh install path = v1 then v2, same result.
    JdbcDb fresh = new JdbcDb("jdbc:sqlite::memory:");
    fresh.exec("PRAGMA foreign_keys=OFF");
    fresh.tx(() -> { Migrations.upgrade(fresh, 0); return null; });
    T.check("fresh install creates all tables", fresh.count("SELECT COUNT(*) n FROM sqlite_master WHERE name IN ('profiles','memories','jobs','job_events','skills','routines','usage','chat_members','rounds','attachments','artifacts','routine_runs','bot_connectors')") == 13);

    // FTS4 is usable (memory search depends on it).
    fresh.insert("INSERT INTO memory_fts(docid,body) VALUES(?,?)", 1L, "prefers kotlin coroutines for android work");
    T.eq("fts4 match", 1, fresh.rows("SELECT docid FROM memory_fts WHERE memory_fts MATCH ?", "kotlin*").size());

    // A failed migration step rolls back completely.
    JdbcDb bad = new JdbcDb("jdbc:sqlite::memory:");
    Migrations.run(bad, Migrations.v1());
    bad.insert("INSERT INTO bots(name,description,instructions,avatar) VALUES('x','d','i','a')");
    bad.exec("PRAGMA foreign_keys=OFF");
    try {
      bad.tx(() -> { Migrations.run(bad, Migrations.v2().subList(0, 5)); bad.exec("THIS IS NOT SQL"); return null; });
    } catch (RuntimeException e) { }
    T.check("failed migration rolled back", bad.rows("PRAGMA table_info(bots)").size() == 6);
    T.done();
  }
}
