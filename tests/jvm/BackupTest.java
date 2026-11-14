import com.victorious.bots.*;
import java.util.*;
import org.json.*;

public final class BackupTest {
  static JdbcDb fresh() throws Exception {
    JdbcDb d = new JdbcDb("jdbc:sqlite::memory:");
    d.exec("PRAGMA foreign_keys=OFF");
    d.tx(() -> { Migrations.upgrade(d, 0); return null; });
    d.exec("PRAGMA foreign_keys=ON");
    Seeds.profiles(d);
    Skills.seed(d);
    return d;
  }

  static String S(String s, int n) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < n; i++) b.append(s);
    return b.toString();
  }

  public static void main(String[] a) throws Exception {
    Fakes.Env e = Fakes.env();
    Skills.seed(e.db);
    Memory mem = new Memory(e.db, e.kv);
    String uid2 = S("b", 32);
    long bot2 = e.db.insert("INSERT INTO bots(name,description,instructions,avatar,uid,image,profile,model,policy,project) VALUES('Second','d2','i2','y',?,'avatar-9.png',?,'m9','pc_only','proj')", uid2, e.pc);
    long chat = e.db.insert("INSERT INTO chats(bot,title,created,uid) VALUES(?,?,?,?)", e.bot, "Chat one", 5L, S("c", 32));
    e.db.insert("INSERT INTO messages(chat,role,body,status,created,author) VALUES(?,?,?,?,?,0)", chat, "user", "hello <b>'; DROP TABLE bots;--", "done", 6L);
    e.db.insert("INSERT INTO messages(chat,role,body,status,created,author) VALUES(?,?,?,?,?,?)", chat, "assistant", "world", "done", 7L, e.bot);
    e.db.insert("INSERT INTO messages(chat,role,body,status,created,author) VALUES(?,?,?,?,?,0)", chat, "assistant", "still pending", "pending", 8L);
    Teams.create(e.db, "Crew", Arrays.asList(e.bot, bot2), new Teams.Config());
    mem.add("bot", e.bot, "", "Likes short answers", "owner", "active", chat, 0, "chat");
    mem.add("project", 0, "proj", "Project uses Gradle", "owner", "active", 0, 0, "");
    e.db.insert("INSERT INTO tasks(title,body,status,created,uid) VALUES('T','body','Draft',1,?)", S("d", 32));
    Skills.save(e.db, 0, "my-skill", "mine", "Do the thing", Arrays.asList("files.attach"));
    Routines.create(e.db, "Daily", e.bot, "PHONE", 0, "summarise", "", "{}", "Europe/London", "{\"type\":\"daily\",\"time\":\"09:00\"}", "skip", "skip");
    e.secrets.save(e.cloud, "https://api.example.com:443", "SECRET-KEY-VALUE-98765");

    // ---- export ----
    String json = Backup.export(e.db).toString();
    T.check("export contains no key or token material", !json.contains("SECRET-KEY-VALUE") && !json.contains("pc-token") && !json.contains("cloud-key"));
    T.check("export contains no avatar paths or local paths", !json.contains("avatar-9.png") && !json.contains("local_path"));
    T.check("pending message is not exported", !json.contains("still pending"));
    T.check("DroidCommand built-in profile not exported", !json.contains("DROIDCOMMAND"));
    T.check("bundled skills are not exported (only owner skills)", json.contains("my-skill") && !json.contains("repo-review"));
    JSONObject root = new JSONObject(json);
    T.eq("format marker", Backup.FORMAT, root.getString("format"));
    T.eq("version", 2, root.getInt("version"));

    // ---- round trip into an empty install ----
    JdbcDb target = fresh();
    Backup.Preview pv = Backup.preview(target, json);
    T.check("preview ok", pv.ok());
    T.check("preview does not write", target.count("SELECT COUNT(*) n FROM chats") == 0 && target.count("SELECT COUNT(*) n FROM memories") == 0);
    T.check("preview reports new items", pv.counts.get("bots")[0] >= 2 && pv.counts.get("messages")[0] == 2);
    Backup.Preview ap = Backup.apply(target, json, Backup.OnConflict.SKIP);
    T.check("apply ok: " + ap.errors, ap.ok());
    T.eq("bots restored (+4 seeded? no: fresh has none)", e.db.count("SELECT COUNT(*) n FROM bots"), target.count("SELECT COUNT(*) n FROM bots"));
    T.eq("chats restored incl. team", e.db.count("SELECT COUNT(*) n FROM chats"), target.count("SELECT COUNT(*) n FROM chats"));
    T.eq("messages restored without the pending one", 2L, target.count("SELECT COUNT(*) n FROM messages"));
    T.eq("hostile text stored verbatim as data", "hello <b>'; DROP TABLE bots;--", target.one("SELECT body b FROM messages WHERE role='user'").optString("b"));
    T.check("bots table intact", target.count("SELECT COUNT(*) n FROM bots") > 0);
    T.eq("author link remapped", target.one("SELECT name n FROM bots WHERE id=(SELECT author FROM messages WHERE body='world')").optString("n"), e.db.one("SELECT name n FROM bots WHERE id=?", e.bot).optString("n"));
    T.eq("team members restored", 2L, target.count("SELECT COUNT(*) n FROM chat_members"));
    T.eq("memories restored and searchable", 1, new Memory(target, new Fakes.MemKV()).list(target.one("SELECT id FROM bots WHERE uid=(SELECT uid FROM bots WHERE name='B')").optLong("id"), "", "active", "short").size());
    T.eq("imported memory marked as imported", "import", target.one("SELECT origin o FROM memories WHERE body LIKE 'Likes%'").optString("o"));
    T.eq("tasks restored", 1L, target.count("SELECT COUNT(*) n FROM tasks"));
    T.eq("owner skill restored", 1L, target.count("SELECT COUNT(*) n FROM skills WHERE name='my-skill' AND builtin=0"));
    T.eq("routine restored but switched OFF", 0L, target.count("SELECT COUNT(*) n FROM routines WHERE enabled=1"));
    T.eq("profile restored without a key", 1L, target.count("SELECT COUNT(*) n FROM profiles WHERE name='My PC'"));
    T.eq("bot->profile link restored by name", "My PC", target.one("SELECT p.name n FROM bots b JOIN profiles p ON p.id=b.profile WHERE b.name='Second'").optString("n"));
    T.eq("foreign keys clean", 0, target.rows("PRAGMA foreign_key_check").size());
    T.check("restore does not touch the owner's policy or default provider", !target.rows("SELECT * FROM profiles").toString().contains("policy_global"));

    // ---- idempotent re-import ----
    Backup.Preview again = Backup.apply(target, json, Backup.OnConflict.SKIP);
    T.check("re-import ok", again.ok());
    T.eq("no duplicates after a second import (bots)", e.db.count("SELECT COUNT(*) n FROM bots"), target.count("SELECT COUNT(*) n FROM bots"));
    T.eq("no duplicates (messages)", 2L, target.count("SELECT COUNT(*) n FROM messages"));
    T.eq("no duplicates (memories)", e.db.count("SELECT COUNT(*) n FROM memories"), target.count("SELECT COUNT(*) n FROM memories"));
    T.eq("no duplicates (tasks)", 1L, target.count("SELECT COUNT(*) n FROM tasks"));

    // ---- conflicts: same uid, different content ----
    JSONObject edited = new JSONObject(json);
    for (int i = 0; i < edited.getJSONArray("bots").length(); i++) if (edited.getJSONArray("bots").getJSONObject(i).getString("uid").equals(uid2)) edited.getJSONArray("bots").getJSONObject(i).put("instructions", "CHANGED instructions");
    Backup.Preview cp = Backup.preview(target, edited.toString());
    T.eq("conflict detected in preview", 1, cp.counts.get("bots")[2]);
    Backup.apply(target, edited.toString(), Backup.OnConflict.SKIP);
    T.eq("default keeps the local version", "i2", target.one("SELECT instructions i FROM bots WHERE uid=?", uid2).optString("i"));
    Backup.apply(target, edited.toString(), Backup.OnConflict.KEEP_BOTH);
    T.eq("keep-both adds an imported copy", 1L, target.count("SELECT COUNT(*) n FROM bots WHERE name LIKE 'Second (imported)'"));
    T.eq("original untouched", "i2", target.one("SELECT instructions i FROM bots WHERE uid=?", uid2).optString("i"));

    // ---- hostile / malformed input is rejected and changes nothing ----
    JdbcDb victim = fresh();
    long before = victim.count("SELECT COUNT(*) n FROM bots");
    String[] bad = {
        "not json", "{}", "{\"format\":\"Other\",\"version\":2}",
        "{\"format\":\"" + Backup.FORMAT + "\",\"version\":99}",
        "{\"format\":\"" + Backup.FORMAT + "\",\"version\":2,\"bots\":\"nope\"}",
        "{\"format\":\"" + Backup.FORMAT + "\",\"version\":2,\"bots\":[{\"name\":123,\"instructions\":\"x\"}]}",
        "{\"format\":\"" + Backup.FORMAT + "\",\"version\":2,\"bots\":[{\"name\":\"" + S("n", 81) + "\",\"instructions\":\"x\"}]}",
        "{\"format\":\"" + Backup.FORMAT + "\",\"version\":2,\"bots\":[{\"name\":\"ok\",\"instructions\":\"" + S("x", 9000) + "\"}]}",
        "{\"format\":\"" + Backup.FORMAT + "\",\"version\":2,\"bots\":[{\"name\":\"ok\",\"instructions\":\"x\",\"policy\":\"bogus\"}]}",
        "{\"format\":\"" + Backup.FORMAT + "\",\"version\":2,\"profiles\":[{\"name\":\"p\",\"kind\":\"CLOUD\",\"base\":\"http://plain.example/v1\"}]}",
        "{\"format\":\"" + Backup.FORMAT + "\",\"version\":2,\"profiles\":[{\"name\":\"p\",\"kind\":\"DROIDCOMMAND\",\"base\":\"https://x.example/v1\"}]}",
        "{\"format\":\"" + Backup.FORMAT + "\",\"version\":2,\"messages\":[{\"chat_uid\":\"" + S("c", 32) + "\",\"role\":\"root\",\"body\":\"x\"}]}",
        "{\"format\":\"" + Backup.FORMAT + "\",\"version\":2,\"routines\":[{\"name\":\"r\",\"bot_uid\":\"x\",\"target\":\"PC\",\"tz\":\"Mars/Base\",\"schedule\":\"{}\"}]}",
        "{\"format\":\"" + Backup.FORMAT + "\",\"version\":2,\"bots\":[{\"name\":\"nul\\u0000byte\",\"instructions\":\"x\"}]}",
    };
    for (int i = 0; i < bad.length; i++) {
      Backup.Preview r = Backup.apply(victim, bad[i], Backup.OnConflict.SKIP);
      T.check("hostile input #" + i + " rejected", !r.ok());
    }
    T.eq("nothing was written by any rejected import", before, victim.count("SELECT COUNT(*) n FROM bots"));
    T.eq("no profiles written by rejected imports", 1L, victim.count("SELECT COUNT(*) n FROM profiles"));
    T.check("oversized file rejected before parsing", !Backup.preview(victim, S("x", Backup.MAX_BYTES + 1)).ok());
    JSONObject tooMany = new JSONObject("{\"format\":\"" + Backup.FORMAT + "\",\"version\":2}");
    JSONArray many = new JSONArray();
    for (int i = 0; i < 201; i++) many.put(new JSONObject().put("name", "b" + i).put("instructions", "x"));
    tooMany.put("bots", many);
    T.check("too many bots rejected", !Backup.apply(victim, tooMany.toString(), Backup.OnConflict.SKIP).ok());
    // Image/path style fields in a file are ignored: avatar images and paths are never imported.
    JSONObject withPath = new JSONObject("{\"format\":\"" + Backup.FORMAT + "\",\"version\":2,\"bots\":[{\"name\":\"P\",\"instructions\":\"x\",\"image\":\"../../etc/passwd\",\"local_path\":\"/data/x\"}]}");
    T.check("import with path-like fields succeeds", Backup.apply(victim, withPath.toString(), Backup.OnConflict.SKIP).ok());
    T.eq("path-like field was not stored", "", victim.one("SELECT image i FROM bots WHERE name='P'").optString("i"));
    // Policy field in a bot can only tighten; a backup cannot flip the global policy.
    T.eq("global policy untouched by import", Policy.Mode.ALLOW_CLOUD, new Profiles(victim, new Fakes.MemKV(), e.secrets).globalMode());

    // ---- version 1 backups from 0.1.0 still restore (no uids) ----
    String v1 = "{\"format\":\"" + Backup.FORMAT + "\",\"version\":1,\"bots\":[{\"id\":\"1\",\"name\":\"Old Bot\",\"description\":\"d\",\"instructions\":\"old instructions\",\"avatar\":\"✦\"}],\"chats\":[{\"id\":\"1\",\"bot\":\"1\",\"title\":\"old chat\",\"created\":\"5\"}],\"messages\":[{\"id\":\"1\",\"chat\":\"1\",\"role\":\"user\",\"body\":\"hi\",\"status\":\"done\",\"created\":\"6\"}],\"tasks\":[{\"id\":\"1\",\"title\":\"t\",\"body\":\"b\",\"status\":\"Draft\",\"created\":\"7\"}]}";
    JdbcDb legacy = fresh();
    Backup.Preview lv = Backup.apply(legacy, v1, Backup.OnConflict.SKIP);
    T.check("v1 backup accepted: " + lv.errors, lv.ok());
    T.eq("v1 bot restored", 1L, legacy.count("SELECT COUNT(*) n FROM bots WHERE name='Old Bot'"));
    T.eq("v1 task restored", 1L, legacy.count("SELECT COUNT(*) n FROM tasks"));
    Backup.apply(legacy, v1, Backup.OnConflict.SKIP);
    T.eq("v1 re-import does not duplicate the bot", 1L, legacy.count("SELECT COUNT(*) n FROM bots WHERE name='Old Bot'"));
    T.done();
  }
}
