package com.victorious.bots;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Application scope: owns the database, the Gateway and the background executors so that work and
 * results survive activity recreation. Foreground-only: there is no service keeping the app alive.
 */
public final class BotApp extends Application {
  public Store store;
  public Db db;
  public KV kv;
  public SecretStore secrets;
  public Profiles profiles;
  public Gateway gateway;
  public Memory memory;
  public Jobs jobs;
  public Diagnostics diagnostics;
  public JobMonitor monitor;
  public TeamEngine teams;
  public Runnable changed;
  private final ExecutorService worker = Executors.newSingleThreadExecutor(), rounds = Executors.newFixedThreadPool(2), misc = Executors.newFixedThreadPool(2);
  private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Map<Long, Provider.Cancel> inflight = new ConcurrentHashMap<>(), roundStops = new ConcurrentHashMap<>();
  public volatile String monitorNote = "";

  @Override
  public void onCreate() {
    super.onCreate();
    store = new Store(this);
    db = store.db();
    SharedPreferences prefs = getSharedPreferences("settings", 0);
    kv = new PrefsKV(prefs);
    secrets = new SecretStore(this);
    profiles = new Profiles(db, kv, secrets);
    gateway = new Gateway(db, profiles, secrets, Gateway.LIVE);
    memory = new Memory(db, kv);
    jobs = new Jobs(db);
    diagnostics = new Diagnostics(db, profiles, secrets, gateway);
    teams = new TeamEngine(db, gateway, memory, System::currentTimeMillis, this::notifyChanged);
    monitor = new JobMonitor(db, jobs, id -> {
      Profile p = profiles.get(id);
      if (p == null) throw new IllegalStateException("The PC profile for this job was deleted.");
      return RunnerClient.forProfile(p, secrets);
    });
    // Anything left pending by a dead process failed; nothing is retried automatically.
    db.update("UPDATE messages SET status='error',role='system',body='Interrupted when the app process stopped. No automatic retry was sent.' WHERE status='pending' AND body!='Waiting for DroidCommand consent…'");
    TeamEngine.recover(db);
    Jobs.recover(db);
    Routines.recover(db);
    LegacyMigration.run(this, store, profiles, secrets, kv, prefs);
    poller.scheduleWithFixedDelay(this::pollJobs, 3, 3, TimeUnit.SECONDS);
    RoutineService.reschedule(this);
  }

  public void notifyChanged() {
    main.post(() -> {
      if (changed != null) changed.run();
    });
  }

  private void pollJobs() {
    try {
      if (changed == null || jobs.active().isEmpty()) return;
      JobMonitor.Report r = monitor.pollOnce();
      monitorNote = r.unreachable > 0 ? "Runner unreachable for " + r.unreachable + " job(s); showing the last reported state." : "";
      if (r.changed > 0 || r.unreachable > 0) notifyChanged();
    } catch (RuntimeException ignored) {
    }
  }

  public void submitJob(long jobId, String workspace) {
    misc.execute(() -> {
      monitor.submit(jobId, workspace);
      notifyChanged();
    });
  }

  public void runAsync(Runnable r) {
    misc.execute(() -> {
      try {
        r.run();
      } finally {
        notifyChanged();
      }
    });
  }

  /** One owner-initiated chat request. The reply message must already exist with status 'pending'. */
  public void ask(long reply, long botId, List<Rules.Turn> turns, Policy.Purpose purpose, boolean confirmed, long profileOverride, boolean stream) {
    Provider.Cancel cancel = new Provider.Cancel();
    inflight.put(reply, cancel);
    worker.execute(() -> {
      StringBuilder partial = new StringBuilder();
      long[] last = {0};
      try {
        Gateway.Req q = new Gateway.Req();
        q.purpose = purpose;
        q.botId = botId;
        q.turns = turns;
        q.stream = stream;
        q.cancel = cancel;
        q.ownerConfirmed = confirmed;
        q.profileOverride = profileOverride;
        q.sink = piece -> {
          partial.append(piece);
          long now = System.currentTimeMillis();
          if (now - last[0] > 400) {
            last[0] = now;
            db.update("UPDATE messages SET body=? WHERE id=? AND status='pending'", partial.toString(), reply);
            notifyChanged();
          }
        };
        ToolLoop.Outcome tools = null;
        Gateway.Result res;
        // Model-driven tools: owner-initiated chat only, bots whose owner switched model use on.
        List<ToolLoop.Binding> bindings = purpose == Policy.Purpose.CHAT ? ToolLoop.bindings(db, botId) : new ArrayList<ToolLoop.Binding>();
        if (!bindings.isEmpty()) {
          tools = new ToolLoop(gateway, new RunnerHost(botId)).run(q, bindings);
          res = tools.last;
        } else res = gateway.run(q);
        JSONObject meta = new JSONObject().put("model_requested", res.plan.model).put("model_reported", res.reply.modelReported).put("latency_ms", tools != null ? tools.latencyMs : res.reply.latencyMs).put("streamed", res.reply.streamed).put("profile", res.plan.profile.name).put("profile_id", res.plan.profile.id);
        if (res.reply.firstTokenMs >= 0) meta.put("ttft_ms", res.reply.firstTokenMs);
        Long tin = tools != null ? tools.inTokens : res.reply.usage.in, tout = tools != null ? tools.outTokens : res.reply.usage.out;
        if (tin != null) meta.put("in_tokens", tin);
        if (tout != null) meta.put("out_tokens", tout);
        if (tools != null && (!tools.trace.isEmpty() || !tools.notice.isEmpty())) meta.put("tools", tools.traceJson()).put("tool_notice", tools.notice);
        store.finish(reply, res.reply.text, true, meta.toString());
      } catch (Gateway.Denied d) {
        store.finish(reply, d.getMessage(), false, "{}");
      } catch (ProviderError e) {
        String body = e.category == ProviderError.Category.CANCELLED ? "Stopped. The provider may still have processed it; nothing was retried." : e.category.label + ": " + e.getMessage();
        try {
          JSONObject meta = new JSONObject().put("error_category", e.category.name());
          store.finish(reply, partial.length() > 0 ? partial + "\n\n[" + body + "]" : body, false, meta.toString());
        } catch (JSONException je) {
          store.finish(reply, body, false, "{}");
        }
      } catch (Exception e) {
        store.finish(reply, "Could not complete the request: " + Redact.snippet(String.valueOf(e.getMessage()), 160), false, "{}");
      } finally {
        inflight.remove(reply);
      }
      notifyChanged();
    });
  }

  /** Executes a bot's tool calls on the PC runner that holds the connector. Only reads exist there. */
  private final class RunnerHost implements ToolLoop.Host {
    private final long bot;

    RunnerHost(long bot) {
      this.bot = bot;
    }

    private RunnerClient client(String connector) {
      JSONObject r = db.one("SELECT profile FROM bot_connectors WHERE bot=? AND connector=?", bot, connector);
      if (r == null) throw new IllegalStateException("Connector is not enabled for this bot.");
      return RunnerClient.forProfile(profiles.get(r.optLong("profile")), secrets);
    }

    @Override
    public JSONObject tools(String connector, String workspace) throws Exception {
      return client(connector).connectorTools(connector, workspace);
    }

    @Override
    public JSONObject call(String connector, String tool, JSONObject args, String workspace) throws Exception {
      return client(connector).connectorCall(connector, tool, args, workspace);
    }
  }

  public void stop(long reply) {
    Provider.Cancel c = inflight.get(reply);
    if (c != null) c.cancel();
  }

  public boolean isInflight(long reply) {
    return inflight.containsKey(reply);
  }

  public void startRound(long chat, String text) {
    Provider.Cancel stop = new Provider.Cancel();
    roundStops.put(chat, stop);
    rounds.execute(() -> {
      try {
        teams.post(chat, text, stop);
      } catch (RuntimeException e) {
        db.insert("INSERT INTO messages(chat,role,body,status,created,author,kind) VALUES(?,?,?,?,?,0,'notice')", chat, "system", "Could not start the round: " + Redact.snippet(String.valueOf(e.getMessage()), 160), "error", System.currentTimeMillis());
      } finally {
        roundStops.remove(chat);
        notifyChanged();
      }
    });
    notifyChanged();
  }

  public void stopRound(long chat) {
    Provider.Cancel c = roundStops.get(chat);
    if (c != null) c.cancel();
  }

  public boolean roundRunning(long chat) {
    return roundStops.containsKey(chat) || db.count("SELECT COUNT(*) n FROM rounds WHERE chat=? AND state='running'", chat) > 0;
  }
}
