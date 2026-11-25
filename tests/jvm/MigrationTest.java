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

    // v3: a pre-existing connector row keeps owner-driven behaviour (model use off, no workspace).
    JdbcDb up = new JdbcDb("jdbc:sqlite::memory:");
    up.exec("PRAGMA foreign_keys=OFF");
    up.tx(() -> { Migrations.upgrade(up, 0); return null; });
    T.eq("schema version", 5, Migrations.VERSION);
    long ub = up.insert("INSERT INTO bots(name,description,instructions,avatar,uid) VALUES('B','d','i','x','u')");
    up.exec("INSERT INTO bot_connectors(bot,connector,profile,tools) VALUES(" + ub + ",'repo',1,'[\"read_text_file\"]')");
    T.eq("new connector rows default to auto=0", 0L, up.one("SELECT auto FROM bot_connectors").optLong("auto"));
    T.eq("new connector rows default to no workspace", "", up.one("SELECT workspace FROM bot_connectors").optString("workspace"));
    JdbcDb v2db = new JdbcDb("jdbc:sqlite::memory:");
    v2db.exec("PRAGMA foreign_keys=OFF");
    v2db.tx(() -> { Migrations.run(v2db, Migrations.v1()); Migrations.run(v2db, Migrations.v2()); return null; });
    long vb = v2db.insert("INSERT INTO bots(name,description,instructions,avatar,uid) VALUES('B','d','i','x','u')");
    v2db.exec("INSERT INTO bot_connectors(bot,connector,profile,tools) VALUES(" + vb + ",'repo',1,'[\"t\"]')");
    v2db.tx(() -> { Migrations.upgrade(v2db, 2); return null; });
    T.eq("a 0.2.0 connector row survives the upgrade, model use off", 0L, v2db.one("SELECT auto FROM bot_connectors WHERE connector='repo'").optLong("auto"));

    // v4 (review F12): a populated 0.3.0 database upgrades with everything kept, gains the communications tables, and they cascade.
    JdbcDb v3db = new JdbcDb("jdbc:sqlite::memory:");
    v3db.exec("PRAGMA foreign_keys=OFF");
    v3db.tx(() -> { Migrations.run(v3db, Migrations.v1()); Migrations.run(v3db, Migrations.v2()); Migrations.run(v3db, Migrations.v3()); return null; });
    long b3 = v3db.insert("INSERT INTO bots(name,description,instructions,avatar,uid) VALUES('B3','d','keep me','x','u3')");
    long c3 = v3db.insert("INSERT INTO chats(bot,title,created,uid) VALUES(?,?,?,?)", b3, "Chat 3", 3L, "c3");
    v3db.insert("INSERT INTO messages(chat,role,body,status,created) VALUES(?,?,?,?,?)", c3, "user", "hello v3", "done", 1L);
    v3db.insert("INSERT INTO tasks(title,body,status,created,uid) VALUES('T3','b','Draft',1,'t3')");
    v3db.exec("INSERT INTO bot_connectors(bot,connector,profile,tools) VALUES(" + b3 + ",'repo',1,'[\"t\"]')");
    v3db.tx(() -> { Migrations.upgrade(v3db, 3); return null; });
    v3db.exec("PRAGMA foreign_keys=ON");
    T.eq("v3->v4 bot kept", "keep me", v3db.one("SELECT instructions i FROM bots WHERE id=?", b3).optString("i"));
    T.eq("v3->v4 chat kept", "Chat 3", v3db.one("SELECT title t FROM chats WHERE id=?", c3).optString("t"));
    T.eq("v3->v4 message kept", "hello v3", v3db.one("SELECT body b FROM messages WHERE chat=?", c3).optString("b"));
    T.eq("v3->v4 task kept", 1L, v3db.count("SELECT COUNT(*) n FROM tasks WHERE uid='t3'"));
    T.eq("v3->v4 connector kept", 1L, v3db.count("SELECT COUNT(*) n FROM bot_connectors WHERE bot=?", b3));
    String v4tables = "('style_profiles','style_versions','style_prefs','style_examples','comm_bots','comm_assign','comm_activity','comm_state')";
    T.eq("v3->v4 adds the 8 communications tables", 8L, v3db.count("SELECT COUNT(*) n FROM sqlite_master WHERE type='table' AND name IN " + v4tables));
    T.eq("v3->v4 adds the activity index", 1L, v3db.count("SELECT COUNT(*) n FROM sqlite_master WHERE type='index' AND name='comm_activity_bot'"));
    T.eq("fresh install has the 8 communications tables", 8L, fresh.count("SELECT COUNT(*) n FROM sqlite_master WHERE type='table' AND name IN " + v4tables));
    v3db.insert("INSERT INTO comm_bots(bot,updated) VALUES(?,1)", b3);
    v3db.insert("INSERT INTO comm_assign(conversation,bot,created) VALUES('conv',?,1)", b3);
    v3db.insert("INSERT INTO comm_activity(bot,kind,created) VALUES(?,'x',1)", b3);
    T.eq("new comm bots default to Approval", "APPROVAL", v3db.one("SELECT mode m FROM comm_bots WHERE bot=?", b3).optString("m"));
    long sp3 = v3db.insert("INSERT INTO style_profiles(uid,name,created,updated) VALUES('sp3','P',1,1)");
    v3db.insert("INSERT INTO style_versions(profile,version,stats,created) VALUES(?,1,'{}',1)", sp3);
    v3db.insert("INSERT INTO style_prefs(profile,kind,created) VALUES(?,'shorter',1)", sp3);
    v3db.insert("INSERT INTO style_examples(profile,text,source,created) VALUES(?,'hey','saved',1)", sp3);
    T.eq("v4 foreign_key_check clean", 0, v3db.rows("PRAGMA foreign_key_check").size());
    v3db.update("DELETE FROM style_profiles WHERE id=?", sp3);
    T.eq("style rows cascade from their profile", 0L, v3db.count("SELECT (SELECT COUNT(*) FROM style_versions)+(SELECT COUNT(*) FROM style_prefs)+(SELECT COUNT(*) FROM style_examples) n"));
    v3db.update("DELETE FROM bots WHERE id=?", b3);
    T.eq("comm rows cascade from their bot", 0L, v3db.count("SELECT (SELECT COUNT(*) FROM comm_bots)+(SELECT COUNT(*) FROM comm_assign)+(SELECT COUNT(*) FROM comm_activity) n"));

    // v5: a 0.4.0 chat survives the upgrade and inherits its bot's provider (profile NULL).
    JdbcDb v4db = new JdbcDb("jdbc:sqlite::memory:");
    v4db.exec("PRAGMA foreign_keys=OFF");
    v4db.tx(() -> { Migrations.run(v4db, Migrations.v1()); Migrations.run(v4db, Migrations.v2()); Migrations.run(v4db, Migrations.v3()); Migrations.run(v4db, Migrations.v4()); return null; });
    long b4 = v4db.insert("INSERT INTO bots(name,description,instructions,avatar,uid) VALUES('B','d','i','x','u4')");
    long c4 = v4db.insert("INSERT INTO chats(bot,title,created,uid) VALUES(?,?,?,?)", b4, "Kept chat", 9L, "c4");
    v4db.insert("INSERT INTO messages(chat,role,body,status,created) VALUES(?,?,?,?,?)", c4, "user", "hi", "done", 1L);
    v4db.tx(() -> { Migrations.upgrade(v4db, 4); return null; });
    v4db.exec("PRAGMA foreign_keys=ON");
    T.eq("v4 chat preserved", "Kept chat", v4db.one("SELECT title t FROM chats WHERE id=?", c4).optString("t"));
    T.eq("v4 messages preserved", 1L, v4db.count("SELECT COUNT(*) n FROM messages WHERE chat=?", c4));
    T.eq("existing chat inherits (no pick)", true, v4db.one("SELECT profile p FROM chats WHERE id=?", c4).optString("p", "").isEmpty());
    T.eq("v5 foreign_key_check clean", 0, v4db.rows("PRAGMA foreign_key_check").size());
    T.check("fresh install has chats.profile", fresh.rows("PRAGMA table_info(chats)").stream().anyMatch(r -> r.optString("name").equals("profile")));
    T.eq("upgrade and fresh install give chats the same columns", fresh.rows("PRAGMA table_info(chats)").size(), v4db.rows("PRAGMA table_info(chats)").size());

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
