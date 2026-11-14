package com.victorious.bots;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import org.json.JSONObject;

/**
 * Phone-local routines. Android decides when this runs (inexact, at best every ~15 minutes, and
 * deferred in Doze), so the app never promises exact timing. Each due routine goes through the
 * Gateway with purpose ROUTINE: under PC-first policies it can never fall back to a cloud provider.
 * PC routines are not run here; the PC runner runs them on its own clock.
 */
public final class RoutineService extends JobService {
  private static final int JOB_ID = 4711;
  private volatile Thread worker;

  public static void reschedule(Context c) {
    BotApp app = (BotApp) c.getApplicationContext();
    JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    boolean any = app.db.count("SELECT COUNT(*) n FROM routines WHERE enabled=1 AND target='PHONE'") > 0;
    if (!any) {
      js.cancel(JOB_ID);
      return;
    }
    js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, RoutineService.class)).setPeriodic(15 * 60 * 1000L).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build());
  }

  /** Builds and stores one routine run: user instruction + model reply, in a chat named after the routine. */
  static long runOne(BotApp app, JSONObject routine) throws Exception {
    long botId = routine.optLong("bot");
    JSONObject bot = app.db.one("SELECT * FROM bots WHERE id=?", botId);
    if (bot == null) throw new IllegalStateException("The routine's bot no longer exists.");
    String title = "⏰ " + routine.optString("name");
    JSONObject chat = app.db.one("SELECT id FROM chats WHERE bot=? AND title=?", botId, title);
    long chatId = chat == null ? app.db.insert("INSERT INTO chats(bot,title,created,uid) VALUES(?,?,?,?)", botId, title, System.currentTimeMillis(), Ids.uid()) : chat.optLong("id");
    String instruction = routine.optString("instruction");
    Gateway.Plan plan = app.gateway.plan(botId, 0, Policy.Purpose.ROUTINE, false, "");
    PromptBuilder.Built b = new PromptBuilder(app.db, app.memory).build(bot, null, plan.profile, instruction, null, "This is a scheduled routine run, not a live chat. Say plainly if something you were asked for is unavailable; do not guess.");
    Gateway.Req q = new Gateway.Req();
    q.purpose = Policy.Purpose.ROUTINE;
    q.botId = botId;
    q.turns = b.plan.turns;
    Gateway.Result r = app.gateway.run(q);
    app.db.insert("INSERT INTO messages(chat,role,body,status,created,author) VALUES(?,?,?,?,?,0)", chatId, "user", instruction, "done", System.currentTimeMillis());
    return app.db.insert("INSERT INTO messages(chat,role,body,status,created,author,meta) VALUES(?,?,?,?,?,0,?)", chatId, "assistant", r.reply.text, "done", System.currentTimeMillis(),
        new JSONObject().put("model_requested", r.plan.model).put("model_reported", r.reply.modelReported).put("routine", true).toString());
  }

  @Override
  public boolean onStartJob(JobParameters params) {
    final BotApp app = (BotApp) getApplicationContext();
    worker = new Thread(() -> {
      try {
        Routines.tick(app.db, System.currentTimeMillis(), r -> runOne(app, r));
      } finally {
        jobFinished(params, false);
        app.notifyChanged();
      }
    });
    worker.start();
    return true;
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    return true; // run again at the next period; an interrupted run is recorded 'unknown' on restart
  }
}
