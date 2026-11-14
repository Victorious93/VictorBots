package com.victorious.bots.test;

import android.app.*;
import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import com.victorious.bots.*;
import java.util.*;
import org.json.*;

/**
 * Runs against an ISOLATED emulator installation: it deletes and recreates the app database and
 * Keystore entries it uses. Never run it on a populated personal installation.
 */
public final class SmokeInstrumentation extends Instrumentation {
  private int passed;
  private final StringBuilder log = new StringBuilder();

  private void check(boolean value, String label) {
    if (!value) throw new AssertionError(label);
    passed++;
    log.append("PASS ").append(label).append('\n');
  }

  private interface Throwing {
    void run() throws Exception;
  }

  private void refuses(Throwing r, String label) throws Exception {
    try {
      r.run();
    } catch (IllegalArgumentException | IllegalStateException | SecurityException | Gateway.Denied | ProviderError expected) {
      check(true, label);
      return;
    } catch (java.security.GeneralSecurityException expected) {
      check(true, label);
      return;
    }
    throw new AssertionError(label + " (no exception)");
  }

  private Bundle runArgs = new Bundle();

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    if (args != null) runArgs = args;
    start();
  }

  static final class MemKV implements KV {
    final Map<String, String> m = new HashMap<>();
    public String get(String k, String d) { return m.containsKey(k) ? m.get(k) : d; }
    public void put(String k, String v) { m.put(k, v); }
  }

  @Override
  public void onStart() {
    Bundle report = new Bundle();
    try {
      Context c = getTargetContext();
      BotApp app = (BotApp) c.getApplicationContext();
      Store store = app.store;
      // Remove fixtures left by an earlier, interrupted run of this test.
      store.db().update("DELETE FROM bots WHERE name IN ('Backup bot','PolA','PolB','Instrumentation bot')");
      store.db().update("DELETE FROM chats WHERE kind='team' OR title='bk'");
      store.db().update("DELETE FROM profiles WHERE name IN ('cloudp','pcp','Direct provider (migrated)')");
      upgradeFromRealV1(c, store);
      Db db = store.db();
      keystoreBinding(c);
      legacyKeyMigration(c, store);
      backupRoundTrip(db);
      policyGatewayAndTeams(c, store, db);
      jobsOnDevice(db);
      if (runArgs.getString("runner_port") != null) liveRunner(c, db, runArgs.getString("runner_port"), runArgs.getString("runner_pin"), runArgs.getString("runner_token"));
      dcaBridge(c, store);
      uiWalk(c);
      report.putString("stream", passed + " Android integration checks passed\n" + log);
      finish(Activity.RESULT_OK, report);
    } catch (Throwable e) {
      report.putString("stream", "FAILED after " + passed + " checks: " + android.util.Log.getStackTraceString(e) + "\n" + log);
      finish(Activity.RESULT_CANCELED, report);
    }
  }

  // ---------------------------------------------------------------- 1. real 0.1.0 database -> 0.2.0
  private void upgradeFromRealV1(Context c, Store appStore) throws Exception {
    final String name = "upgrade-test.db";
    c.deleteDatabase(name);
    SQLiteDatabase old = SQLiteDatabase.openOrCreateDatabase(c.getDatabasePath(name), null);
    old.execSQL("CREATE TABLE bots(id INTEGER PRIMARY KEY,name TEXT NOT NULL,description TEXT NOT NULL,instructions TEXT NOT NULL,avatar TEXT NOT NULL,image TEXT NOT NULL DEFAULT '')");
    old.execSQL("CREATE TABLE chats(id INTEGER PRIMARY KEY,bot INTEGER NOT NULL REFERENCES bots(id) ON DELETE CASCADE,title TEXT NOT NULL,created INTEGER NOT NULL)");
    old.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY,chat INTEGER NOT NULL REFERENCES chats(id) ON DELETE CASCADE,role TEXT NOT NULL,body TEXT NOT NULL,status TEXT NOT NULL,created INTEGER NOT NULL)");
    old.execSQL("CREATE TABLE tasks(id INTEGER PRIMARY KEY,title TEXT NOT NULL,body TEXT NOT NULL,status TEXT NOT NULL,created INTEGER NOT NULL)");
    old.execSQL("INSERT INTO bots(id,name,description,instructions,avatar,image) VALUES(1,'Old Eng','desc','old instructions','⌘','avatar-1.png'),(2,'Old QA','d2','i2','◎','')");
    old.execSQL("INSERT INTO chats(id,bot,title,created) VALUES(7,1,'Old chat',1000),(8,2,'Other',2000)");
    for (int i = 0; i < 6; i++) old.execSQL("INSERT INTO messages(chat,role,body,status,created) VALUES(7,'" + (i % 2 == 0 ? "user" : "assistant") + "','old message " + i + "','done'," + (10 + i) + ")");
    old.execSQL("INSERT INTO messages(chat,role,body,status,created) VALUES(8,'user','other chat','done',99)");
    old.execSQL("INSERT INTO tasks(title,body,status,created) VALUES('Old task','body','Draft',5)");
    old.execSQL("PRAGMA foreign_keys=ON");
    old.setVersion(1);
    old.close();

    Store s = new Store(c, name);
    Db db = s.db();
    check(db.count("SELECT COUNT(*) n FROM bots WHERE name IN ('Old Eng','Old QA')") == 2, "upgrade keeps both bots");
    check(db.one("SELECT instructions i FROM bots WHERE id=1").optString("i").equals("old instructions"), "upgrade keeps bot content");
    check(db.one("SELECT image i FROM bots WHERE id=1").optString("i").equals("avatar-1.png"), "upgrade keeps avatar reference");
    check(db.count("SELECT COUNT(*) n FROM chats") == 2 && db.one("SELECT title t FROM chats WHERE id=7").optString("t").equals("Old chat"), "upgrade keeps chats and their ids");
    check(db.count("SELECT COUNT(*) n FROM messages") == 7, "upgrade does NOT cascade-delete messages during the chats rebuild");
    check(db.rows("SELECT body FROM messages WHERE chat=7 ORDER BY id").get(5).optString("body").equals("old message 5"), "message order and content preserved");
    check(db.count("SELECT COUNT(*) n FROM tasks WHERE title='Old task'") == 1, "upgrade keeps tasks");
    check(db.count("SELECT COUNT(*) n FROM bots WHERE length(uid)=32") == db.count("SELECT COUNT(*) n FROM bots"), "every bot has a stable uid");
    check(db.rows("PRAGMA foreign_key_check").isEmpty(), "no foreign key violations after upgrade");
    check(db.one("PRAGMA foreign_keys").optString("foreign_keys").equals("1"), "foreign keys switched on after migration (onOpen)");
    check(db.count("SELECT COUNT(*) n FROM profiles WHERE kind='DROIDCOMMAND'") == 1, "built-in DroidCommand profile seeded on upgrade");
    check(db.count("SELECT COUNT(*) n FROM skills WHERE builtin=1") == 4, "four bundled skills seeded on upgrade");
    db.update("DELETE FROM bots WHERE id=2");
    check(db.count("SELECT COUNT(*) n FROM messages WHERE chat=8") == 0, "cascade still works through the rebuilt chats table");
    s.close();
    c.deleteDatabase(name);
    Store fs = new Store(c, name);
    Db fresh = fs.db();
    check(fresh.count("SELECT COUNT(*) n FROM bots") >= 4, "fresh install seeds starter bots");
    check(fresh.count("SELECT COUNT(*) n FROM sqlite_master WHERE name='memory_fts'") == 1, "FTS4 table created on Android SQLite");
    fresh.insert("INSERT INTO memory_fts(docid,body) VALUES(1,'prefers kotlin flow')");
    check(fresh.rows("SELECT docid FROM memory_fts WHERE memory_fts MATCH 'kotlin*'").size() == 1, "FTS4 search works on Android SQLite");
    fs.close();
    c.deleteDatabase(name);
  }

  // ---------------------------------------------------------------- 2. Keystore secret binding
  private void keystoreBinding(Context c) throws Exception {
    SecretStore s = new SecretStore(c);
    s.save(101, "https://a.example:443", "instrumentation-key-AAA");
    check(s.read(101, "https://a.example:443").equals("instrumentation-key-AAA"), "keystore round trip bound to profile+origin");
    refuses(() -> s.read(101, "https://b.example:443"), "key refuses to decrypt for another origin");
    check(s.read(999, "https://a.example:443").isEmpty(), "no key for an unknown profile");
    check(!c.getSharedPreferences("vault2", 0).getString("p101", "").contains("instrumentation-key-AAA"), "key not stored as plaintext");
    // Transplanting the ciphertext to another profile id must fail authentication (GCM associated data).
    String stored = c.getSharedPreferences("vault2", 0).getString("p101", "");
    c.getSharedPreferences("vault2", 0).edit().putString("p202", stored).commit();
    refuses(() -> s.read(202, "https://a.example:443"), "ciphertext copied to another profile id fails authentication");
    // Rewriting the stored origin label to match a new endpoint must also fail.
    c.getSharedPreferences("vault2", 0).edit().putString("p303", stored.replace("https://a.example:443", "https://evil.example:443")).commit();
    refuses(() -> s.read(303, "https://evil.example:443"), "edited origin label fails authentication");
    s.remove(101);
    check(s.read(101, "https://a.example:443").isEmpty() && !s.has(101), "key deletion");
    s.remove(202);
    s.remove(303);
  }

  // ---------------------------------------------------------------- 3. legacy single-key migration
  private void legacyKeyMigration(Context c, Store store) throws Exception {
    SecretStore s = new SecretStore(c);
    s.writeLegacyForTest("legacy-key-ZZZ-123456");
    check(s.hasLegacy() && s.readLegacy().equals("legacy-key-ZZZ-123456"), "0.1.0 style key readable by the migrator");
    SharedPreferences prefs = c.getSharedPreferences("migration-test", 0);
    prefs.edit().clear().putString("endpoint", "https://old.example.com/v1/chat/completions").putString("model", "old-model").putBoolean("direct", true).commit();
    MemKV kv = new MemKV();
    Db db = store.db();
    Profiles profiles = new Profiles(db, kv, s);
    LegacyMigration.run(null, store, profiles, s, kv, prefs);
    Profile found = null;
    for (Profile x : profiles.all()) if (x.name.equals("Direct provider (migrated)")) found = x;
    final Profile p = found;
    check(p != null && p.base.equals("https://old.example.com/v1") && p.model.equals("old-model"), "legacy endpoint and model become a profile");
    check(!p.streaming(), "migrated profile keeps the old non-streaming request shape");
    check(s.read(p.id, Hosts.origin(p.base)).equals("legacy-key-ZZZ-123456"), "legacy key re-encrypted and bound to its endpoint");
    refuses(() -> s.read(p.id, "https://other.example:443"), "migrated key will not decrypt for another endpoint");
    check(!s.hasLegacy(), "legacy shared key removed after a successful move");
    check(profiles.defaultProfile().id == p.id, "direct mode becomes the default profile");
    check(prefs.getBoolean("migrated_v2", false), "migration marked done (idempotent)");
    s.remove(p.id);
    db.update("DELETE FROM profiles WHERE id=?", p.id);
  }

  // ---------------------------------------------------------------- 4. backup on Android SQLite
  private void backupRoundTrip(Db db) throws Exception {
    db.update("DELETE FROM bots WHERE name IN ('Backup bot','PolA','PolB')");
    db.update("DELETE FROM chats WHERE kind='team' OR title='bk'");
    db.update("DELETE FROM profiles WHERE name IN ('cloudp','pcp')");
    long bot = db.insert("INSERT INTO bots(name,description,instructions,avatar,uid) VALUES('Backup bot','d','i','x',?)", Ids.uid());
    long chat = db.insert("INSERT INTO chats(bot,title,created,uid) VALUES(?,?,?,?)", bot, "bk", 1L, Ids.uid());
    db.insert("INSERT INTO messages(chat,role,body,status,created) VALUES(?,?,?,?,?)", chat, "user", "x'; DROP TABLE bots;--", "done", 2L);
    String json = Backup.export(db).toString();
    check(!json.contains("instrumentation-key") && !json.contains("legacy-key"), "export excludes keys");
    db.update("DELETE FROM bots WHERE id=?", bot);
    check(db.count("SELECT COUNT(*) n FROM messages WHERE body LIKE 'x%'") == 0, "deleted before restore");
    Backup.Preview pv = Backup.preview(db, json);
    check(pv.ok() && pv.counts.get("bots")[0] >= 1, "restore preview on device");
    check(Backup.apply(db, json, Backup.OnConflict.SKIP).ok(), "restore applies");
    check(db.count("SELECT COUNT(*) n FROM messages WHERE body LIKE 'x''; DROP%'") == 1, "hostile text restored verbatim as data");
    check(Backup.apply(db, json, Backup.OnConflict.SKIP).ok() && db.count("SELECT COUNT(*) n FROM bots WHERE name='Backup bot'") == 1, "second restore does not duplicate");
    check(!Backup.apply(db, "{\"format\":\"nope\"}", Backup.OnConflict.SKIP).ok(), "wrong format refused");
    check(db.rows("PRAGMA foreign_key_check").isEmpty(), "integrity after restore");
  }

  // ---------------------------------------------------------------- 5. policy gateway + team round + memory
  private void policyGatewayAndTeams(Context c, Store store, Db db) throws Exception {
    MemKV kv = new MemKV();
    SecretStore secrets = new SecretStore(c);
    Profiles profiles = new Profiles(db, kv, secrets);
    long cloud = profiles.save(0, "cloudp", Policy.Kind.CLOUD, "https://cloud.example/v1", "cm", "", null);
    long pc = profiles.save(0, "pcp", Policy.Kind.PC, "https://192.168.1.5:8443/v1", "pm", "", null);
    secrets.save(cloud, "https://cloud.example:443", "ck-12345678");
    secrets.save(pc, "https://192.168.1.5:8443", "pt-12345678");
    final List<String> calls = new ArrayList<>();
    Gateway.Transport t = (cfg, turns, stream, sink, cancel) -> {
      calls.add(cfg.base + "|" + cfg.key);
      Provider.Reply r = new Provider.Reply();
      r.text = "reply from " + turns.get(0).content.hashCode();
      r.usage.in = 10L;
      r.usage.out = 4L;
      return r;
    };
    Gateway gw = new Gateway(db, profiles, secrets, t);
    long a = db.insert("INSERT INTO bots(name,description,instructions,avatar,uid,profile) VALUES('PolA','d','You are PolA','x',?,?)", Ids.uid(), pc);
    long b = db.insert("INSERT INTO bots(name,description,instructions,avatar,uid,profile) VALUES('PolB','d','You are PolB','x',?,?)", Ids.uid(), cloud);
    kv.put(Profiles.GLOBAL_POLICY, Policy.Mode.PC_ONLY.id);
    for (Policy.Purpose p : Policy.Purpose.values()) {
      Gateway.Req q = new Gateway.Req();
      q.purpose = p;
      q.botId = b;
      q.turns = Arrays.asList(new Rules.Turn("system", "s"), new Rules.Turn("user", "u"));
      refuses(() -> gw.run(q), "PC_ONLY blocks cloud bot for " + p);
    }
    check(calls.isEmpty(), "no request reached any transport under PC_ONLY for the cloud bot");
    long ledgerBefore = db.count("SELECT COUNT(*) n FROM usage WHERE ok=1 AND host='192.168.1.5'");
    Gateway.Req ok = new Gateway.Req();
    ok.botId = a;
    ok.turns = Arrays.asList(new Rules.Turn("system", "s"), new Rules.Turn("user", "u"));
    gw.run(ok);
    check(calls.size() == 1 && calls.get(0).equals("https://192.168.1.5:8443/v1|pt-12345678"), "PC bot goes to the PC with the PC token");
    check(db.count("SELECT COUNT(*) n FROM usage WHERE ok=1 AND host='192.168.1.5'") == ledgerBefore + 1, "usage ledger written on device");
    // Team round with a scripted transport on Android SQLite.
    kv.put(Profiles.GLOBAL_POLICY, Policy.Mode.ALLOW_CLOUD.id);
    db.update("UPDATE bots SET profile=? WHERE id IN (?,?)", pc, a, b);
    Memory mem = new Memory(db, kv);
    mem.add("bot", a, "", "PolA private memory fact", "owner", "active", 0, 0, "");
    final List<String> prompts = new ArrayList<>();
    Gateway tg = new Gateway(db, profiles, secrets, (cfg, turns, stream, sink, cancel) -> {
      StringBuilder sb = new StringBuilder();
      for (Rules.Turn x : turns) sb.append(x.content);
      prompts.add(sb.toString());
      Provider.Reply r = new Provider.Reply();
      r.text = "answer number " + prompts.size() + " distinct words " + System.nanoTime();
      r.usage.total = 20L;
      return r;
    });
    long team = Teams.create(db, "Device team", Arrays.asList(a, b), new Teams.Config());
    Teams.Config cfg = new Teams.Config();
    cfg.mode = Teams.Mode.ALL;
    Teams.saveConfig(db, team, cfg);
    TeamEngine.Outcome o = new TeamEngine(db, tg, mem, System::currentTimeMillis, () -> { }).post(team, "status please", new Provider.Cancel());
    check(o.turns == 2 && o.state.equals("done"), "team round ran two turns on Android SQLite");
    check(prompts.size() == 2 && prompts.get(0).contains("PolA private memory fact") && !prompts.get(1).contains("PolA private memory fact"), "private memory not shared with the other bot");
    check(db.count("SELECT COUNT(*) n FROM messages WHERE chat=? AND status='pending'", team) == 0, "no pending leftovers");
    db.update("INSERT INTO rounds(chat,started,state) VALUES(?,?, 'running')", team, 1L);
    TeamEngine.recover(db);
    check(db.count("SELECT COUNT(*) n FROM rounds WHERE state='interrupted'") == 1, "interrupted round recovered after restart");
    secrets.remove(cloud);
    secrets.remove(pc);
    db.update("DELETE FROM bots WHERE id IN (?,?)", a, b);
    db.update("DELETE FROM profiles WHERE id IN (?,?)", cloud, pc);
  }

  // ---------------------------------------------------------------- 6. jobs
  private void jobsOnDevice(Db db) throws Exception {
    Jobs jobs = new Jobs(db);
    long j = jobs.create("dev job", 0, 0, 0, "PC", 0, "cmd.x", new JSONObject(), 0, 0);
    check(jobs.get(j).state == Jobs.State.DRAFT, "draft is not sent");
    jobs.markSubmitting(j);
    refuses(() -> jobs.markSubmitting(j), "never sent twice");
    Jobs.Remote r = new Jobs.Remote();
    r.id = "remote1";
    r.state = "running";
    jobs.applyRemote(j, r);
    Jobs.Remote done = new Jobs.Remote();
    done.state = "completed";
    done.exitCode = 0;
    done.events.add(new String[] {"1", "5", "stdout", "hello token=abcdef123456"});
    jobs.applyRemote(j, done);
    check(jobs.get(j).state == Jobs.State.COMPLETED && jobs.get(j).stateSource.equals("backend"), "completion only from a backend report");
    check(!jobs.events(j, 10).get(0)[2].contains("abcdef123456"), "log redacted on device");
    Jobs.Remote late = new Jobs.Remote();
    late.state = "running";
    jobs.applyRemote(j, late);
    check(jobs.get(j).state == Jobs.State.COMPLETED, "late event cannot reopen a finished job");
    long u = jobs.create("crash job", 0, 0, 0, "PC", 0, "cmd.y", new JSONObject(), 0, 0);
    jobs.markSubmitting(u);
    Jobs.recover(db);
    check(jobs.get(u).state == Jobs.State.UNKNOWN, "a job mid-send at process death becomes UNKNOWN, not retried");
    db.update("DELETE FROM jobs WHERE title IN ('dev job','crash job')");
  }

  // ---------------------------------------------------------------- 6b. LIVE: emulator -> real runner on the build host (10.0.2.2)
  private void liveRunner(Context c, Db db, String port, String pin, String token) throws Exception {
    db.update("DELETE FROM bots WHERE name='LiveBot'");
    db.update("DELETE FROM profiles WHERE name IN ('live-pc','live-badpin')");
    MemKV kv = new MemKV();
    SecretStore secrets = new SecretStore(c);
    Profiles profiles = new Profiles(db, kv, secrets);
    String base = "https://10.0.2.2:" + port + "/v1";
    long pc = profiles.save(0, "live-pc", Policy.Kind.PC, base, "stub-model", pin, new JSONObject().put("streaming", true));
    secrets.save(pc, Hosts.origin(base), token);
    Gateway gw = new Gateway(db, profiles, secrets, Gateway.LIVE);
    Diagnostics diag = new Diagnostics(db, profiles, secrets, gw);
    Diagnostics.Report probe = diag.probe(profiles.get(pc));
    check(probe.reachable.state == Diagnostics.State.OK && probe.auth.state == Diagnostics.State.OK && probe.model.state == Diagnostics.State.OK, "LIVE probe over pinned TLS: reachable, auth accepted, model listed (" + probe.reachable.detail + ")");
    check(probe.reply.state == Diagnostics.State.NOT_RUN, "LIVE probe does not claim a reply");
    Diagnostics.Report live = diag.liveTest(profiles.get(pc), true);
    check(live.reply.state == Diagnostics.State.OK, "LIVE streamed reply received (" + live.reply.detail + ")");
    check(live.firstTokenMs >= 100 && live.firstTokenMs <= live.latencyMs, "LIVE first-token latency measured: " + live.firstTokenMs + " ms of " + live.latencyMs + " ms");
    check(live.reportedModel.equals("stub-model-served") && live.requestedModel.equals("stub-model"), "LIVE requested vs server-reported model differ and are both shown");
    check(live.inTokens != null && live.inTokens == 17L && live.outTokens == 3L, "LIVE token usage as reported by the server");
    // Wrong pin and unpinned self-signed are both rejected by Android's TLS stack.
    long bad = profiles.save(0, "live-badpin", Policy.Kind.PC, base, "stub-model", "AB".repeat(32), null);
    secrets.save(bad, Hosts.origin(base), token);
    Diagnostics.Report badRep = diag.probe(profiles.get(bad));
    check(badRep.reachable.state == Diagnostics.State.FAILED && badRep.reachable.detail.contains("TLS"), "LIVE wrong certificate pin rejected on Android: " + badRep.reachable.detail);
    db.update("UPDATE profiles SET pin='' WHERE id=?", bad);
    check(diag.probe(profiles.get(bad)).reachable.state == Diagnostics.State.FAILED, "LIVE self-signed certificate without a pin rejected");
    // Real job flow through the app's own client, store and monitor.
    Jobs jobs = new Jobs(db);
    JobMonitor mon = new JobMonitor(db, jobs, id -> new RunnerClient(profiles.get(pc), secrets.read(pc, Hosts.origin(base))));
    long j = jobs.create("live list", 0, 0, 0, "PC", pc, "repo.list", new JSONObject().put("path", "."), 0, 0);
    mon.submit(j, "main");
    for (int i = 0; i < 60 && !jobs.get(j).state.terminal(); i++) { mon.pollOnce(); Thread.sleep(250); }
    check(jobs.get(j).state == Jobs.State.COMPLETED && jobs.get(j).stateSource.equals("backend"), "LIVE job completed by the runner's own report");
    check(logText(jobs, j).contains("hello.txt"), "LIVE job log came from the runner");
    long g = jobs.create("live guarded", 0, 0, 0, "PC", pc, "cmd.guarded", new JSONObject(), 0, 0);
    mon.submit(g, "main");
    for (int i = 0; i < 20 && jobs.get(g).state != Jobs.State.AWAITING_APPROVAL; i++) { mon.pollOnce(); Thread.sleep(250); }
    check(jobs.get(g).state == Jobs.State.AWAITING_APPROVAL, "LIVE runner holds an approval-required job (not queued/run)");
    Thread.sleep(1200);
    mon.pollOnce();
    check(jobs.get(g).state == Jobs.State.AWAITING_APPROVAL && !logText(jobs, g).contains("guarded-ran"), "LIVE guarded command has not run without approval");
    RunnerClient rc = new RunnerClient(profiles.get(pc), token);
    jobs.applyRemote(g, rc.approve(jobs.get(g).remoteId, true));
    for (int i = 0; i < 60 && !jobs.get(g).state.terminal(); i++) { mon.pollOnce(); Thread.sleep(250); }
    check(jobs.get(g).state == Jobs.State.COMPLETED && logText(jobs, g).contains("guarded-ran"), "LIVE job ran only after the owner's approval was sent to the runner");
    long sl = jobs.create("live slow", 0, 0, 0, "PC", pc, "cmd.slow", new JSONObject(), 0, 0);
    mon.submit(sl, "main");
    for (int i = 0; i < 40 && jobs.get(sl).state != Jobs.State.RUNNING; i++) { mon.pollOnce(); Thread.sleep(250); }
    check(jobs.get(sl).state == Jobs.State.RUNNING, "LIVE long job reported running");
    jobs.applyRemote(sl, rc.cancel(jobs.get(sl).remoteId));
    check(jobs.get(sl).state == Jobs.State.CANCELLED, "LIVE cancel confirmed by the runner");
    // A chat through the policy gateway, streaming, with a per-bot model.
    long bot = db.insert("INSERT INTO bots(name,description,instructions,avatar,uid,profile,model) VALUES('LiveBot','d','You are LiveBot','x',?,?,'stub-model')", Ids.uid(), pc);
    Gateway.Req q = new Gateway.Req();
    q.botId = bot;
    q.stream = true;
    q.turns = Arrays.asList(new Rules.Turn("system", "s"), new Rules.Turn("user", "hello"));
    StringBuilder streamed = new StringBuilder();
    q.sink = streamed::append;
    Gateway.Result r = gw.run(q);
    check(r.reply.text.equals("stub stream ok") && streamed.toString().equals("stub stream ok"), "LIVE chat streamed through the gateway");
    kv.put(Profiles.GLOBAL_POLICY, Policy.Mode.PC_ONLY.id);
    check(gw.run(q).reply.text.equals("stub stream ok"), "LIVE PC_ONLY policy still allows the PC bot");
    db.update("DELETE FROM bots WHERE id=?", bot);
    secrets.remove(pc);
    secrets.remove(bad);
    db.update("DELETE FROM profiles WHERE id IN (?,?)", pc, bad);
    db.update("DELETE FROM jobs WHERE title LIKE 'live %'");
  }

  // ---------------------------------------------------------------- 7. DroidCommand contracts (text)
  private void dcaBridge(Context c, Store store) throws Exception {
    Intent request = DcaBridge.request(Arrays.asList(new Rules.Turn("system", "Role"), new Rules.Turn("user", "Hello")));
    check(request.getComponent().getPackageName().equals("ai.droidcommand.app"), "explicit DCA target");
    JSONObject payload = new JSONObject(request.getStringExtra(DcaBridge.REQUEST));
    check(payload.getInt("v") == 1 && payload.getString("task").equals("assist") && !payload.getBoolean("destructive"), "text-only assist request");
    check(DcaBridge.result(new Intent().putExtra(DcaBridge.RESULT, "{\"v\":1,\"ok\":true,\"text\":\"Hello\"}")).equals("Hello"), "reply parser");
    refuses(() -> DcaBridge.result(null), "missing reply refused");
    try {
      DcaBridge.verify(c, DcaBridge.DEBUG_PIN);
      throw new AssertionError("No DCA should be installed in this isolated emulator");
    } catch (android.content.pm.PackageManager.NameNotFoundException expected) {
      check(true, "missing DroidCommand is reported, never assumed present");
    }
  }

  // ---------------------------------------------------------------- 8. click through every screen
  private static String logText(Jobs jobs, long id) {
    StringBuilder b = new StringBuilder();
    for (String[] e : jobs.events(id, 200)) b.append(e[2]).append('\n');
    return b.toString();
  }

  private View find(View v, String text) {
    if (v instanceof TextView && ((TextView) v).getText().toString().contains(text) && v.isShown()) return v;
    if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
      View f = find(((ViewGroup) v).getChildAt(i), text);
      if (f != null) return f;
    }
    return null;
  }

  private View button(View v, String exact) {
    if (v instanceof Button && ((Button) v).getText().toString().equals(exact) && v.isShown()) return v;
    if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
      View f = button(((ViewGroup) v).getChildAt(i), exact);
      if (f != null) return f;
    }
    return null;
  }

  private void uiWalk(Context c) throws Exception {
    Intent i = new Intent().setClassName("com.victorious.bots", "com.victorious.bots.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    final Activity act = startActivitySync(i);
    waitForIdleSync();
    check(find(act.getWindow().getDecorView(), "victor / bots") != null, "main screen draws");
    String[][] steps = {
        {"Teams", "Specialists, together"}, {"Jobs", "Real work, real status"}, {"More", "Everything else"},
        {"Open|Providers, policy", "Where text may go"}, {"‹ More", "Everything else"},
        {"Open|Usage & requests", "Every request"}, {"‹ More", "Everything else"},
        {"Open|Memory", "What bots remember"}, {"‹ More", "Everything else"},
        {"Open|Skills", "Reusable instructions"}, {"‹ More", "Everything else"},
        {"Open|Routines", "Scheduled work"}, {"‹ More", "Everything else"},
        {"Open|Connectors", "Read-only repository"}, {"‹ More", "Everything else"},
        {"Open|Task drafts", "Your next moves"}, {"‹ More", "Everything else"},
        {"Open|Backup & restore", "Export and restore"}, {"‹ More", "Everything else"},
        {"Open|DroidCommand link", "Same-device link"}, {"‹ More", "Everything else"},
        {"Bots", "A bot for every"}};
    for (String[] s : steps) {
      final String label = s[0];
      final String expect = s[1];
      runOnMainSync(() -> {
        View root = act.getWindow().getDecorView();
        if (label.startsWith("Open|")) {
          View card = find(root, label.substring(5));
          if (card != null && card.getParent() instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) card.getParent();
            for (int k = 0; k < g.getChildCount(); k++) if (g.getChildAt(k) instanceof Button && ((Button) g.getChildAt(k)).getText().toString().equals("Open")) { g.getChildAt(k).performClick(); return; }
          }
        } else {
          View b = button(root, label);
          if (b != null) b.performClick();
        }
      });
      waitForIdleSync();
      final boolean[] seen = {false};
      runOnMainSync(() -> seen[0] = find(act.getWindow().getDecorView(), expect) != null);
      check(seen[0], "screen shows “" + expect + "” after " + label);
    }
    // Open a bot chat and check the status header shows provider/policy.
    runOnMainSync(() -> { View b = find(act.getWindow().getDecorView(), "Open conversation"); if (b != null) b.performClick(); });
    waitForIdleSync();
    final boolean[] chat = {false};
    runOnMainSync(() -> chat[0] = find(act.getWindow().getDecorView(), "Context window") != null && find(act.getWindow().getDecorView(), "Message your bot") == null || find(act.getWindow().getDecorView(), "What are we working on") != null);
    check(chat[0], "chat screen draws with context-window line");
    act.finish();
  }
}
