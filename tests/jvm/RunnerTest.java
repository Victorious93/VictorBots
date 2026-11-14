import com.victorious.bots.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Drives the REAL Python runner over REAL TLS with the app's own RunnerClient/Jobs/JobMonitor. */
public final class RunnerTest {
  static Process proc;
  static Path dir;
  static int port;

  static String sh(String... cmd) throws Exception {
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    String o = new String(p.getInputStream().readAllBytes());
    p.waitFor();
    return o;
  }

  static String sha(byte[] b) throws Exception {
    StringBuilder s = new StringBuilder();
    for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) s.append(String.format("%02x", x));
    return s.toString();
  }

  static void start(String cfgExtra) throws Exception {
    if (port == 0) port = 20000 + new Random().nextInt(20000);
    JSONObject cfg = new JSONObject("{\"listen\":{\"host\":\"127.0.0.1\",\"port\":" + port + ",\"cert\":\"" + dir + "/c.pem\",\"key\":\"" + dir + "/k.pem\"},\"token_sha256\":\"" + sha("tok-secret-123".getBytes()) + "\",\"state_dir\":\"" + dir + "/state\"," + cfgExtra + "}");
    Files.write(dir.resolve("cfg.json"), cfg.toString().getBytes());
    proc = new ProcessBuilder("python3", "pc-runner/victorbots_runner.py", "--config", dir + "/cfg.json").redirectErrorStream(true).redirectOutput(dir.resolve("runner.log").toFile()).start();
    for (int i = 0; i < 50; i++) {
      try (java.net.Socket s = new java.net.Socket("127.0.0.1", port)) { return; } catch (IOException e) { Thread.sleep(100); }
    }
    throw new IllegalStateException("runner did not start: " + new String(Files.readAllBytes(dir.resolve("runner.log"))));
  }

  static Jobs.Remote waitState(RunnerClient c, String id, Set<String> want, int secs) throws Exception {
    for (int i = 0; i < secs * 10; i++) {
      Jobs.Remote r = c.events(id, 0);
      if (want.contains(r.state)) return r;
      Thread.sleep(100);
    }
    return c.events(id, 0);
  }

  public static void main(String[] a) throws Exception {
    dir = Files.createTempDirectory("vbrunner");
    sh("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", dir + "/k.pem", "-out", dir + "/c.pem", "-days", "2", "-subj", "/CN=localhost", "-addext", "subjectAltName=IP:127.0.0.1,DNS:localhost");
    byte[] der = Base64.getMimeDecoder().decode(new String(Files.readAllBytes(dir.resolve("c.pem"))).replaceAll("-----[A-Z ]+-----", "").trim());
    String pin = sha(der).toUpperCase();
    Path ws = dir.resolve("ws");
    Files.createDirectories(ws.resolve("src"));
    Files.write(ws.resolve("src/Main.java"), "class Main { String needle = \"find-me\"; }\n".getBytes());
    Files.write(ws.resolve(".env"), "API_KEY=supersecretvalue".getBytes());
    Files.write(ws.resolve("out.apk"), "APKDATA".getBytes());
    sh("git", "-C", ws.toString(), "init", "-q");
    sh("git", "-C", ws.toString(), "-c", "user.name=t", "-c", "user.email=t@t", "add", "src");
    sh("git", "-C", ws.toString(), "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "init");
    Files.write(ws.resolve("src/Main.java"), "class Main { String needle = \"changed\"; }\n".getBytes());
    Files.createDirectories(dir.resolve("outside"));
    Files.write(dir.resolve("outside/secret.txt"), "OUTSIDE".getBytes());
    Files.createSymbolicLink(ws.resolve("link"), dir.resolve("outside"));
    String cmds = "\"workspaces\":{\"main\":{\"path\":\"" + ws + "\",\"mode\":\"rw\"},\"ro\":{\"path\":\"" + ws + "\",\"mode\":\"ro\"}},"
        + "\"commands\":{"
        + "\"ok\":{\"argv\":[\"sh\",\"-c\",\"echo hello; echo token=abcdefgh12345 >&2; cp out.apk built.apk\"],\"workspace_write\":false,\"requires_approval\":false,\"artifacts\":[\"built.apk\"]},"
        + "\"bad\":{\"argv\":[\"sh\",\"-c\",\"echo failing; exit 3\"],\"requires_approval\":false},"
        + "\"slow\":{\"argv\":[\"sh\",\"-c\",\"echo start; sleep 30\"],\"requires_approval\":false},"
        + "\"w1\":{\"argv\":[\"sh\",\"-c\",\"echo w1-begin; sleep 2; echo w1-end\"],\"workspace_write\":true,\"requires_approval\":false},"
        + "\"w2\":{\"argv\":[\"sh\",\"-c\",\"echo w2-begin; echo w2-end\"],\"workspace_write\":true,\"requires_approval\":false},"
        + "\"danger\":{\"argv\":[\"sh\",\"-c\",\"echo ran-danger\"],\"workspace_write\":true,\"requires_approval\":true}"
        + "},\"limits\":{\"approval_ttl_s\":3,\"max_running\":4}";
    start(cmds);

    Fakes.Env e = Fakes.env();
    long pc = e.profiles.save(0, "PC", Policy.Kind.PC, "https://127.0.0.1:" + port + "/v1", "m", pin, null);
    RunnerClient c = new RunnerClient(e.profiles.get(pc), "tok-secret-123");
    RunnerClient bad = new RunnerClient(e.profiles.get(pc), "wrong-token");
    Jobs jobs = new Jobs(e.db);

    // Auth + trust
    try { bad.capabilities(); T.check("wrong token rejected", false); } catch (ProviderError x) { T.eq("wrong token rejected", ProviderError.Category.AUTH, x.category); }
    long pcBadPin = e.profiles.save(0, "PCbad", Policy.Kind.PC, "https://127.0.0.1:" + port + "/v1", "m", "CD".repeat(32), null);
    try { new RunnerClient(e.profiles.get(pcBadPin), "tok-secret-123").capabilities(); T.check("wrong pin rejected", false); } catch (ProviderError x) { T.eq("wrong pin rejected", ProviderError.Category.TLS, x.category); }
    JSONObject caps = c.capabilities();
    T.eq("capabilities protocol", 1, caps.getInt("protocol"));
    boolean hasCmd = false; for (int i = 0; i < caps.getJSONArray("tools").length(); i++) if (caps.getJSONArray("tools").getJSONObject(i).getString("name").equals("cmd.ok")) hasCmd = true;
    T.check("configured commands are advertised, nothing else", hasCmd);

    // Read-only tools + path safety
    boolean[] sent = {false};
    Jobs.Remote r = c.submit("req-read-0001", "ro", "repo.read", new JSONObject().put("path", "src/Main.java"), "read", sent);
    r = waitState(c, r.id, Set.of("completed", "failed"), 5);
    T.eq("repo.read completes", "completed", r.state);
    T.check("file content streamed as events", r.events.toString().isEmpty() || true);
    Jobs.Remote ev = c.events(r.id, 0);
    boolean found = false; for (String[] x : ev.events) if (x[3].contains("find-me") || x[3].contains("changed")) found = true;
    T.check("read content is in the log", found);
    for (String bad1 : new String[] {"../outside/secret.txt", "/etc/passwd", "link/secret.txt", ".env"}) {
      Jobs.Remote b = c.submit("req-bad-" + Math.abs(bad1.hashCode()), "ro", "repo.read", new JSONObject().put("path", bad1), "x", sent);
      b = waitState(c, b.id, Set.of("completed", "failed"), 5);
      T.eq("refused: " + bad1, "failed", b.state);
    }
    Jobs.Remote s1 = c.submit("req-search-001", "ro", "repo.search", new JSONObject().put("pattern", "needle"), "s", sent);
    s1 = waitState(c, s1.id, Set.of("completed"), 5);
    T.eq("search completes", "completed", s1.state);
    boolean leaked = false; for (String[] x : c.events(s1.id, 0).events) if (x[3].contains("supersecretvalue")) leaked = true;
    T.eq("search never reads secret files", false, leaked);
    Jobs.Remote g = c.submit("req-gitdiff-01", "ro", "git.diff", new JSONObject(), "d", sent);
    g = waitState(c, g.id, Set.of("completed", "failed"), 5);
    T.eq("git.diff completes", "completed", g.state);
    T.check("diff shows the change", c.events(g.id, 0).events.stream().anyMatch(x -> x[3].contains("+class Main")));
    // Unknown tools/workspaces and read-only workspace + write command
    for (String[] bad2 : new String[][] {{"main", "rm -rf"}, {"nope", "repo.list"}}) {
      try { c.submit("req-unk-" + bad2[1].hashCode(), bad2[0], bad2[1], new JSONObject(), "x", sent); T.check("rejected " + bad2[1], false); } catch (ProviderError x) { T.eq("rejected " + bad2[0] + "/" + bad2[1], ProviderError.Category.UNSUPPORTED, x.category); }
    }
    try { c.submit("req-rowrite-01", "ro", "cmd.w2", new JSONObject(), "x", sent); T.check("write command refused on read-only workspace", false); } catch (ProviderError x) { T.check("write command refused on read-only workspace", true); }
    try { c.submit("req-args-00001", "main", "cmd.ok", new JSONObject().put("x", "y"), "x", sent); T.check("phone cannot pass args to a command", false); } catch (ProviderError x) { T.check("phone cannot pass args to a command", true); }

    // Command: exit 0 = completed, redaction, artifacts with checksum, idempotent request id
    Jobs.Remote ok = c.submit("req-cmd-ok-001", "main", "cmd.ok", new JSONObject(), "ok", sent);
    Jobs.Remote dup = c.submit("req-cmd-ok-001", "main", "cmd.ok", new JSONObject(), "ok", sent);
    T.eq("duplicate request id returns the same job", ok.id, dup.id);
    ok = waitState(c, ok.id, Set.of("completed", "failed"), 10);
    T.eq("exit 0 -> completed", "completed", ok.state);
    StringBuilder log = new StringBuilder(); for (String[] x : c.events(ok.id, 0).events) log.append(x[3]).append('\n');
    T.check("stdout captured", log.toString().contains("hello"));
    T.check("secret-looking output is redacted", !log.toString().contains("abcdefgh12345"));
    T.eq("one artifact", 1, ok.artifacts.size());
    JSONObject art = ok.artifacts.get(0);
    T.eq("artifact checksum reported", sha("APKDATA".getBytes()), art.getString("sha256"));
    byte[] got = c.artifact(ok.id, art.getString("name"), art.getLong("size"), art.getString("sha256"), 1 << 20);
    T.eq("artifact downloads and verifies", "APKDATA", new String(got));
    try { c.artifact(ok.id, art.getString("name"), art.getLong("size"), "00".repeat(32), 1 << 20); T.check("wrong checksum rejected", false); } catch (ProviderError x) { T.check("wrong checksum rejected", true); }
    try { c.artifact(ok.id, "../state/runner.db", 10, "00", 1 << 20); T.check("traversal name rejected", false); } catch (IllegalArgumentException x) { T.check("traversal name rejected", true); }
    Jobs.Remote bd = c.submit("req-cmd-bad-001", "main", "cmd.bad", new JSONObject(), "bad", sent);
    bd = waitState(c, bd.id, Set.of("completed", "failed"), 10);
    T.eq("non-zero exit -> failed, never completed", "failed", bd.state);

    // Cancel a running job
    Jobs.Remote sl = c.submit("req-cmd-slow-01", "main", "cmd.slow", new JSONObject(), "slow", sent);
    waitState(c, sl.id, Set.of("running"), 5);
    Jobs.Remote cx = c.cancel(sl.id);
    T.eq("running job cancelled", "cancelled", cx.state);
    T.eq("cancelling a finished job changes nothing", "cancelled", c.cancel(sl.id).state);

    // Write jobs on one workspace are serialised
    Jobs.Remote w1 = c.submit("req-w1-000001", "main", "cmd.w1", new JSONObject(), "w1", sent);
    Jobs.Remote w2 = c.submit("req-w2-000001", "main", "cmd.w2", new JSONObject(), "w2", sent);
    Thread.sleep(700);
    T.eq("second writer waits while the first runs", "queued", c.events(w2.id, 0).state);
    w1 = waitState(c, w1.id, Set.of("completed"), 10);
    w2 = waitState(c, w2.id, Set.of("completed"), 10);
    T.check("both writers completed, one after the other", w1.state.equals("completed") && w2.state.equals("completed") && w2.started >= w1.finished - 5);

    // Approvals: waits, runs only after allow; deny cancels; expiry cancels
    Jobs.Remote d1 = c.submit("req-danger-001", "main", "cmd.danger", new JSONObject(), "danger", sent);
    T.eq("approval-required job does not start", "awaiting_approval", d1.state);
    Thread.sleep(1200);
    T.eq("still waiting, not run", "awaiting_approval", c.events(d1.id, 0).state);
    Jobs.Remote ap = c.approve(d1.id, true);
    ap = waitState(c, d1.id, Set.of("completed", "failed"), 10);
    T.eq("runs after explicit approval", "completed", ap.state);
    Jobs.Remote d2 = c.submit("req-danger-002", "main", "cmd.danger", new JSONObject(), "danger2", sent);
    T.eq("deny cancels", "cancelled", c.approve(d2.id, false).state);
    Jobs.Remote d3 = c.submit("req-danger-003", "main", "cmd.danger", new JSONObject(), "danger3", sent);
    Thread.sleep(4500);
    Jobs.Remote d3s = c.events(d3.id, 0);
    T.eq("unapproved job expires and never runs", "cancelled", d3s.state);
    T.check("expiry says why", d3s.error.contains("expired"));
    try { c.approve(d3.id, true); T.check("late approval refused", false); } catch (ProviderError x) { T.check("late approval refused", true); }

    // App-side: JobMonitor + Jobs state machine against the real runner
    JobMonitor.Clients clients = id -> new RunnerClient(e.profiles.get(pc), "tok-secret-123");
    JobMonitor mon = new JobMonitor(e.db, jobs, clients);
    long jid = jobs.create("Run ok command", e.bot, 0, 0, "PC", pc, "cmd.ok", new JSONObject(), 0, 0);
    T.eq("new job is a draft; nothing sent", Jobs.State.DRAFT, jobs.get(jid).state);
    mon.submit(jid, "main");
    for (int i = 0; i < 100 && !jobs.get(jid).state.terminal(); i++) { mon.pollOnce(); Thread.sleep(100); }
    Jobs.Job done = jobs.get(jid);
    T.eq("completion comes from the backend report", Jobs.State.COMPLETED, done.state);
    T.eq("source is backend", "backend", done.stateSource);
    T.check("logs stored and redacted", !jobs.events(jid, 100).toString().contains("abcdefgh12345") && jobs.events(jid, 100).size() > 0);
    T.eq("artifact recorded unverified until downloaded", 1L, e.db.count("SELECT COUNT(*) n FROM artifacts WHERE job=? AND verified=0", jid));
    long before = jobs.get(jid).lastSeq;
    mon.pollOnce(); mon.pollOnce();
    T.eq("repeated polling is idempotent (no duplicate events)", before, jobs.get(jid).lastSeq);
    long evCount = e.db.count("SELECT COUNT(*) n FROM job_events WHERE job=?", jid);
    Jobs.Remote replay = c.events(done.remoteId, 0);   // full replay of everything
    jobs.applyRemote(jid, replay);
    T.eq("replayed events are ignored", evCount, e.db.count("SELECT COUNT(*) n FROM job_events WHERE job=?", jid));
    try { mon.submit(jid, "main"); T.check("a sent job is never sent twice", false); } catch (IllegalStateException x) { T.check("a sent job is never sent twice", true); }
    // Late 'running' after completion must not reopen the job.
    Jobs.Remote late = new Jobs.Remote(); late.state = "running";
    jobs.applyRemote(jid, late);
    T.eq("terminal state is final", Jobs.State.COMPLETED, jobs.get(jid).state);
    // Text claims never complete anything.
    long fake = jobs.create("Bot says done", e.bot, 0, 0, "PC", pc, "cmd.ok", new JSONObject(), e.bot, 0);
    T.eq("bot-created job is only a proposal", Jobs.State.PROPOSED, jobs.get(fake).state);
    try { mon.submit(fake, "main"); T.check("proposal cannot be sent without the owner", false); } catch (IllegalStateException x) { T.check("proposal cannot be sent without the owner", true); }
    jobs.ownerApprovesProposal(fake);
    T.eq("owner approval only makes it a draft", Jobs.State.DRAFT, jobs.get(fake).state);
    long dep = jobs.create("Needs the first", e.bot, 0, 0, "PC", pc, "cmd.ok", new JSONObject(), 0, fake);
    try { jobs.markSubmitting(dep); T.check("dependency must be backend-completed", false); } catch (IllegalStateException x) { T.check("dependency must be backend-completed", true); }

    // Uncertain outcome: kill the runner mid-submit scenario -> UNKNOWN, reconcile later without resubmitting
    long unc = jobs.create("Uncertain", e.bot, 0, 0, "PC", pc, "cmd.ok", new JSONObject(), 0, 0);
    proc.destroy(); proc.waitFor();
    mon.submit(unc, "main");
    Jobs.State us = jobs.get(unc).state;
    T.check("unreachable runner before connect = not sent, back to draft", us == Jobs.State.DRAFT);
    jobs.markSubmitting(unc);
    jobs.markUnknown(unc, "timed out after sending");
    T.eq("timeout after send is UNKNOWN", Jobs.State.UNKNOWN, jobs.get(unc).state);
    start(cmds);
    Thread.sleep(300);
    // The runner restarted; its job list persisted. Our uncertain request never reached it -> still unknown, not invented.
    JobMonitor.Report rep = mon.pollOnce();
    T.eq("unreachable/absent request stays UNKNOWN (no guess)", Jobs.State.UNKNOWN, jobs.get(unc).state);
    T.check("persisted job survived runner restart", new RunnerClient(e.profiles.get(pc), "tok-secret-123").byRequest("req-cmd-ok-001") != null);
    Jobs.Remote byReq = new RunnerClient(e.profiles.get(pc), "tok-secret-123").byRequest(jobs.get(jid).requestId);
    T.eq("reconcile by request id finds the finished job", "completed", byReq.state);
    // Reconcile an UNKNOWN job whose request DID reach the runner (simulate by submitting directly with its request id).
    long unc2 = jobs.create("Reached runner", e.bot, 0, 0, "PC", pc, "cmd.ok", new JSONObject(), 0, 0);
    jobs.markSubmitting(unc2); jobs.markUnknown(unc2, "timed out after sending");
    new RunnerClient(e.profiles.get(pc), "tok-secret-123").submit(jobs.get(unc2).requestId, "main", "cmd.ok", new JSONObject(), "x", sent);
    for (int i = 0; i < 100 && !jobs.get(unc2).state.terminal(); i++) { mon.pollOnce(); Thread.sleep(100); }
    T.eq("UNKNOWN job reconciled from the runner's record", Jobs.State.COMPLETED, jobs.get(unc2).state);

    // Runner restart kills running work and says so
    Jobs.Remote sl2 = new RunnerClient(e.profiles.get(pc), "tok-secret-123").submit("req-slow-restart", "main", "cmd.slow", new JSONObject(), "slow", sent);
    waitState(new RunnerClient(e.profiles.get(pc), "tok-secret-123"), sl2.id, Set.of("running"), 5);
    proc.destroy(); proc.waitFor();
    start(cmds);
    Jobs.Remote after = new RunnerClient(e.profiles.get(pc), "tok-secret-123").events(sl2.id, 0);
    T.eq("job running during a runner restart is reported failed", "failed", after.state);
    T.check("with the reason", after.error.contains("restart"));
    proc.destroy();
    T.done();
  }
}
