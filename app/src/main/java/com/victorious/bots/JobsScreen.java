package com.victorious.bots;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.*;
import java.io.File;
import java.io.FileOutputStream;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Jobs: drafts you create, proposals bots suggest (inert until you approve), and what a backend
 * actually reports. Run, cancel and approve talk to the backend; the screen only mirrors its answers.
 */
final class JobsScreen {
  private final Host h;
  private final Ui ui;
  private final BotApp app;
  private static final String DCA_PACKAGE = "ai.droidcommand.app", DCA_ACTIVITY = "ai.droidcommand.app.companionassist.CompanionExecActivity";
  private JSONObject caps;
  private long capsProfile;

  JobsScreen(Host h) {
    this.h = h;
    this.ui = h.ui();
    this.app = h.app();
  }

  void render(LinearLayout body, String sub, long id) {
    if (sub.equals("job")) {
      detail(body, id);
      return;
    }
    body.addView(ui.text("JOBS", 11, Ui.ACCENT));
    body.addView(ui.heading("Real work, real status.", 30));
    body.addView(ui.text("A job is only “Completed” when its backend (your PC runner or DroidCommand) says so. A drafted, copied, sent or queued job is not done, and a bot saying “done” changes nothing here.", 14, Ui.MUTED));
    if (!app.monitorNote.isEmpty()) body.addView(ui.text("⚠ " + app.monitorNote, 12, Ui.WARN));
    body.addView(ui.button("＋  New job", () -> newJob(0, 0, 0, ""), true));
    List<Jobs.Job> jobs = app.jobs.list(80);
    if (jobs.isEmpty()) {
      LinearLayout c = ui.card(body);
      c.addView(ui.text("No jobs yet. Create one, or long-press a bot reply → “Create a job draft…”. Where each job runs: PC jobs run on your PC runner (they continue if the phone goes offline); DroidCommand jobs run on this phone after you tap Allow in DroidCommand.", 13, Ui.MUTED));
    }
    for (Jobs.Job j : jobs) {
      LinearLayout c = ui.card(body);
      c.addView(ui.text(j.state.label.toUpperCase(Locale.ROOT) + " · " + (j.backend.equals("PC") ? "PC runner" : j.backend.equals("DCA") ? "DroidCommand" : "manual") + (j.state.backendReported() ? " reported" : ""), 10, j.state == Jobs.State.COMPLETED ? Ui.ACCENT : j.state == Jobs.State.FAILED || j.state == Jobs.State.UNKNOWN ? Ui.WARN : Ui.MUTED));
      c.addView(ui.heading(j.title, 17));
      c.addView(ui.text(j.tool + " · " + new java.text.SimpleDateFormat("MMM d HH:mm", Locale.ROOT).format(new Date(j.created)), 12, Ui.MUTED));
      c.addView(ui.button("Open", () -> h.go("Jobs", "job", j.id), false));
    }
  }

  // ------------------------------------------------------------------ creating
  void reviewProposal(JobProposals.Proposal p, long botId, long chatId, long msgId) {
    try {
      Profile prof = p.backend.equals("PC") ? app.profiles.runnerProfile() : app.profiles.byKind(Policy.Kind.DROIDCOMMAND);
      if (prof == null) throw new IllegalStateException("No " + (p.backend.equals("PC") ? "PC" : "DroidCommand") + " profile exists for this proposal.");
      if (p.backend.equals("PC") && prof.runner() == null) throw new IllegalStateException(VictorLinux.NO_RUNNER + ". Add a runner URL to the profile under More → Providers, or use another PC profile.");
      JSONObject args = new JSONObject(p.args.toString());
      if (p.backend.equals("PC")) args.put("_workspace", p.workspace);
      long id = app.jobs.create(p.title, botId, chatId, msgId, p.backend, prof.id, p.tool, args, botId == 0 ? 1 : botId, 0);
      ui.info("Proposal saved, not sent", "A bot suggested this job. Nothing has been sent or approved. Review it, approve it yourself, then press Run. The backend still applies its own approvals.");
      h.go("Jobs", "job", id);
    } catch (Exception e) {
      h.error(e);
    }
  }

  void newJob(long botId, long chatId, long msgId, String seed) {
    ui.choose("Where should this job run?", new String[] {"My PC (runner)", "DroidCommand on this phone", "Draft only (manual, I will do it)"}, w -> {
      if (w == 0) newPcJob(botId, chatId, msgId, seed);
      else if (w == 1) newDcaJob(botId, chatId, msgId);
      else {
        LinearLayout f = ui.column();
        f.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12));
        EditText title = ui.field(f, "Title", Ui.clip(seed.replace('\n', ' '), 80), false);
        ui.form("Manual job draft", f, "Save", () -> {
          long id = app.jobs.create(title.getText().toString(), botId, chatId, msgId, "MANUAL", 0, "manual", new JSONObject(), 0, 0);
          h.go("Jobs", "job", id);
          return true;
        }, null);
      }
    });
  }

  private void newPcJob(long botId, long chatId, long msgId, String seed) {
    Profile pc = app.profiles.runnerProfile();
    if (pc == null || !app.secrets.has(pc.id)) {
      ui.info("No PC profile", "Add your PC under More → Providers (with its access token) first.");
      return;
    }
    if (pc.runner() == null) {
      ui.info(VictorLinux.NO_RUNNER, "“" + pc.name + "” has no runner URL, so jobs cannot be sent from it. Add a runner URL to the profile under More → Providers.");
      return;
    }
    app.runAsync(() -> {
      try {
        caps = RunnerClient.forProfile(pc, app.secrets).capabilities();
        capsProfile = pc.id;
        h.act().runOnUiThread(() -> pcForm(pc, botId, chatId, msgId, seed));
      } catch (Exception e) {
        h.act().runOnUiThread(() -> ui.info("PC runner not reachable", (e instanceof ProviderError ? ((ProviderError) e).category.label + ": " : "") + e.getMessage() + "\n\nA PC that only serves a model (no runner) cannot run jobs. See MANUAL.md → PC runner."));
      }
    });
  }

  private void pcForm(Profile pc, long botId, long chatId, long msgId, String seed) {
    try {
      JSONArray ws = caps.getJSONArray("workspaces"), tools = caps.getJSONArray("tools");
      String[] wn = new String[ws.length()], tn = new String[tools.length()];
      for (int i = 0; i < wn.length; i++) wn[i] = ws.getJSONObject(i).getString("id") + " (" + ws.getJSONObject(i).getString("mode") + ")";
      for (int i = 0; i < tn.length; i++) {
        JSONObject t = tools.getJSONObject(i);
        tn[i] = t.getString("name") + " [" + t.getString("risk") + (t.optBoolean("requires_approval") ? ", needs approval" : "") + "]";
      }
      LinearLayout f = ui.column();
      f.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12));
      EditText title = ui.field(f, "Title", Ui.clip(seed.replace('\n', ' '), 60), false);
      f.addView(ui.text("Workspace (only those your PC runner exposes)", 12, Ui.ACCENT));
      Spinner sw = new Spinner(h.act());
      sw.setAdapter(new ArrayAdapter<>(h.act(), android.R.layout.simple_spinner_dropdown_item, wn));
      f.addView(sw);
      f.addView(ui.text("Tool (the runner decides what exists; nothing else can be requested)", 12, Ui.ACCENT));
      Spinner st = new Spinner(h.act());
      st.setAdapter(new ArrayAdapter<>(h.act(), android.R.layout.simple_spinner_dropdown_item, tn));
      f.addView(st);
      EditText path = ui.field(f, "Path (for repo.* tools)", ".", false), pat = ui.field(f, "Search text (repo.search)", "", false);
      ui.form("New PC job (saved as a draft)", f, "Save draft", () -> {
        JSONObject t = tools.getJSONObject(st.getSelectedItemPosition());
        JSONObject args = new JSONObject();
        String name = t.getString("name");
        if (name.equals("repo.list") || name.equals("repo.read") || name.equals("repo.search")) args.put("path", path.getText().toString().trim());
        if (name.equals("repo.search")) args.put("pattern", pat.getText().toString());
        JSONObject store = new JSONObject().put("_workspace", ws.getJSONObject(sw.getSelectedItemPosition()).getString("id"));
        for (java.util.Iterator<String> it = args.keys(); it.hasNext(); ) { String k = it.next(); store.put(k, args.get(k)); }
        long id = app.jobs.create(title.getText().toString().isEmpty() ? name : title.getText().toString(), botId, chatId, msgId, "PC", pc.id, name, store, 0, 0);
        h.go("Jobs", "job", id);
        return true;
      }, null);
    } catch (JSONException e) {
      h.error(e);
    }
  }

  private void newDcaJob(long botId, long chatId, long msgId) {
    Profile dca = app.profiles.byKind(Policy.Kind.DROIDCOMMAND);
    String[] caps = {"device.apps.list"};
    ui.info("DroidCommand execution", "This uses DroidCommand's separate COMPANION_EXEC contract (not the text chat). DroidCommand lists what it offers, shows you the exact request and runs it only after you tap Allow there. This build offers read-only capabilities; if your installed DroidCommand does not include the contract, the request is refused and nothing runs.");
    ui.choose("Capability", caps, w -> {
      long id = app.jobs.create("DroidCommand: " + caps[w], botId, chatId, msgId, "DCA", dca == null ? 0 : dca.id, caps[w], new JSONObject(), 0, 0);
      h.go("Jobs", "job", id);
    });
  }

  // ------------------------------------------------------------------ detail
  private void detail(LinearLayout body, long id) {
    Jobs.Job j = app.jobs.get(id);
    if (j == null) {
      body.addView(ui.text("This job no longer exists.", 14, Ui.MUTED));
      body.addView(ui.button("Back", () -> h.go("Jobs", "", 0), false));
      return;
    }
    body.addView(ui.button("‹ Jobs", () -> h.go("Jobs", "", 0), false));
    body.addView(ui.heading(j.title, 24));
    LinearLayout c = ui.card(body);
    c.addView(ui.text(j.state.label.toUpperCase(Locale.ROOT), 12, j.state == Jobs.State.COMPLETED ? Ui.ACCENT : Ui.WARN));
    c.addView(ui.text(j.statusLine(), 13, Ui.FG));
    c.addView(ui.text("Runs on: " + (j.backend.equals("PC") ? "your PC runner (continues if the phone goes offline; the app shows the last reported state until it reconnects)" : j.backend.equals("DCA") ? "this phone, in DroidCommand, after you tap Allow there" : "nowhere: manual draft") + "\nTool: " + j.tool + "\nCorrelation id: " + j.requestId + "\nCreated: " + new Date(j.created), 12, Ui.MUTED));
    if (j.state == Jobs.State.PROPOSED) c.addView(ui.text("A bot proposed this. It is inert until you approve it, and then Run still needs your tap.", 12, Ui.WARN));
    if (!j.error.isEmpty()) c.addView(ui.text("Note: " + j.error, 12, Ui.WARN));
    if (j.state == Jobs.State.UNKNOWN) c.addView(ui.text("The app could not confirm whether this ran. It will not send it again by itself. Use Check status; if the backend has no record you may start a new, separate job.", 12, Ui.WARN));
    if (j.state == Jobs.State.COMPLETED && j.backend.equals("DCA")) {
      try {
        String out = new JSONObject(j.result).optString("output");
        if (!out.isEmpty()) c.addView(ui.text("Result from DroidCommand:\n" + Ui.clip(out, 3000), 12, Ui.FG));
      } catch (JSONException ignored) {
      }
    }
    // actions
    switch (j.state) {
      case PROPOSED:
        c.addView(ui.button("Approve this proposal (makes it a draft)", () -> { app.jobs.ownerApprovesProposal(j.id); h.refresh(); }, true));
        c.addView(ui.button("Dismiss", () -> { app.jobs.cancelLocalDraft(j.id); h.refresh(); }, false));
        break;
      case DRAFT:
        c.addView(ui.button("Run", () -> confirmRun(j), true));
        c.addView(ui.button("Discard draft", () -> { app.jobs.cancelLocalDraft(j.id); h.refresh(); }, false));
        if (j.backend.equals("MANUAL")) c.addView(ui.text("A manual draft has no backend, so it cannot be completed from here. Do the work yourself and record it in Tasks.", 12, Ui.MUTED));
        break;
      case AWAITING_APPROVAL:
        c.addView(ui.text("The PC runner is waiting for you. This tap is sent to the runner, which enforces its own expiry.", 12, Ui.WARN));
        c.addView(ui.button("Allow this job on the PC", () -> remote(j, "approve"), true));
        c.addView(ui.button("Deny", () -> remote(j, "deny"), false));
        break;
      default:
        break;
    }
    if (j.backend.equals("PC") && (j.state == Jobs.State.QUEUED || j.state == Jobs.State.RUNNING || j.state == Jobs.State.AWAITING_APPROVAL))
      c.addView(ui.button("Cancel on the PC", () -> remote(j, "cancel"), false));
    if (j.state == Jobs.State.UNKNOWN || j.state == Jobs.State.SUBMITTING || (j.state == Jobs.State.QUEUED && j.backend.equals("DCA")))
      c.addView(ui.button("Check status", () -> checkStatus(j), false));
    if (j.state == Jobs.State.UNKNOWN)
      c.addView(ui.button("The backend has no record: start a new job from this", () -> ui.confirm("Start a new job?", "Only do this if Check status showed the backend never received the request. A new request id is used; the old record stays as it is.", "Create new draft", () -> {
        app.db.update("UPDATE jobs SET state='DRAFT',request_id=?,remote_id='',last_seq=0,error='Marked not received by you.',updated=? WHERE id=? AND state='UNKNOWN'", Ids.uuid(), System.currentTimeMillis(), j.id);
        h.refresh();
      }), false));
    if (j.state.terminal() || j.state == Jobs.State.DRAFT)
      c.addView(ui.button("Delete this record", () -> ui.confirm("Delete job record?", "Removes the record and its stored log from this phone only. The backend keeps its own history.", "Delete", () -> { app.db.update("DELETE FROM jobs WHERE id=?", j.id); h.go("Jobs", "", 0); }), false));
    // log
    List<String[]> ev = app.jobs.events(j.id, 200);
    LinearLayout lg = ui.card(body);
    lg.addView(ui.text("BACKEND LOG (redacted, last " + ev.size() + " lines kept on this phone)", 10, Ui.ACCENT));
    StringBuilder sb = new StringBuilder();
    for (String[] e : ev) sb.append(e[1].equals("stderr") ? "! " : "").append(e[2]).append('\n');
    TextView t = ui.text(sb.length() == 0 ? "No output reported yet." : sb.toString(), 11, Ui.FG);
    t.setTypeface(android.graphics.Typeface.MONOSPACE);
    t.setTextIsSelectable(true);
    lg.addView(t);
    // artifacts
    List<JSONObject> arts = app.db.rows("SELECT * FROM artifacts WHERE job=?", j.id);
    for (JSONObject a : arts) artifactCard(body, j, a);
  }

  private void confirmRun(Jobs.Job j) {
    if (j.backend.equals("PC")) {
      Profile pc = app.profiles.get(j.profile);
      String ws;
      try { ws = new JSONObject(j.args).optString("_workspace"); } catch (JSONException e) { ws = ""; }
      final String workspace = ws;
      ui.confirm("Run on your PC?", "Sends “" + j.tool + "” in workspace “" + ws + "” to " + (pc == null ? "?" : Hosts.host(pc.base)) + ". The runner may ask for its own approval first. It is sent once; if the connection drops mid-send the job becomes “outcome unknown” and is never repeated automatically.", "Run", () -> {
        try {
          JSONObject a = new JSONObject(j.args);
          a.remove("_workspace");
          app.db.update("UPDATE jobs SET args=? WHERE id=?", a.toString(), j.id);
        } catch (JSONException ignored) {
        }
        app.submitJob(j.id, workspace);
        h.refresh();
      });
    } else if (j.backend.equals("DCA")) runDca(j);
    else ui.info("Nothing to run", "Manual drafts have no backend.");
  }

  private void remote(Jobs.Job j, String what) {
    app.runAsync(() -> {
      try {
        Profile pc = app.profiles.get(j.profile);
        RunnerClient c = RunnerClient.forProfile(pc, app.secrets);
        Jobs.Remote r = what.equals("cancel") ? c.cancel(j.remoteId) : c.approve(j.remoteId, what.equals("approve"));
        app.jobs.applyRemote(j.id, r);
        Jobs.Remote ev = c.events(j.remoteId, app.jobs.get(j.id).lastSeq);
        app.jobs.applyRemote(j.id, ev);
      } catch (Exception e) {
        h.act().runOnUiThread(() -> ui.info("The runner did not confirm", String.valueOf(e.getMessage()) + "\nThe job keeps its last reported state."));
      }
    });
  }

  private void checkStatus(Jobs.Job j) {
    if (j.backend.equals("DCA")) {
      try {
        h.startForResult(dcaIntent(DcaExecCodec.receipt(j.requestId)), MainActivity.DCA_EXEC);
        h.act().getSharedPreferences("settings", 0).edit().putLong("dca_exec_job", j.id).putBoolean("dca_exec_check", true).commit();
      } catch (Exception e) {
        h.error(e);
      }
      return;
    }
    app.runAsync(() -> {
      JobMonitor.Report r = app.monitor.pollOnce();
      if (r.notes.size() > 0) h.act().runOnUiThread(() -> ui.info("Status check", android.text.TextUtils.join("\n", r.notes)));
    });
  }

  // ------------------------------------------------------------------ DroidCommand execution
  private Intent dcaIntent(String json) {
    return new Intent(DcaExecCodec.ACTION).setComponent(new ComponentName(DCA_PACKAGE, DCA_ACTIVITY)).putExtra(DcaBridge.REQUEST, json);
  }

  private void runDca(Jobs.Job j) {
    try {
      DcaBridge.verify(h.act(), h.act().getSharedPreferences("settings", 0).getString("pin", DcaBridge.DEBUG_PIN));
      String json = DcaExecCodec.execute(j.requestId, j.tool, new JSONObject(j.args), System.currentTimeMillis(), "VictorBots job: " + j.title);
      ui.confirm("Ask DroidCommand to run this?", "VictorBots sends capability “" + j.tool + "” (request " + j.requestId.substring(0, 8) + "…) to DroidCommand. DroidCommand shows you the exact request and runs it only if you tap Allow there. The request expires in 2 minutes. If DroidCommand has no execution contract installed, it refuses and nothing runs.", "Send", () -> {
        try {
          app.jobs.markSubmitting(j.id);
          h.act().getSharedPreferences("settings", 0).edit().putLong("dca_exec_job", j.id).putBoolean("dca_exec_check", false).commit();
          h.startForResult(dcaIntent(json), MainActivity.DCA_EXEC);
        } catch (Exception e) {
          app.jobs.markNotSent(j.id, "DroidCommand's execution interface could not be opened.");
          h.error(new IllegalStateException("DroidCommand's execution interface could not be opened. Install a DroidCommand build that includes COMPANION_EXEC. Nothing ran."));
        }
        h.refresh();
      });
    } catch (Exception e) {
      h.error(e);
    }
  }

  void onDcaResult(int result, Intent data) {
    android.content.SharedPreferences p = h.act().getSharedPreferences("settings", 0);
    long jobId = p.getLong("dca_exec_job", 0);
    boolean check = p.getBoolean("dca_exec_check", false);
    p.edit().remove("dca_exec_job").remove("dca_exec_check").apply();
    if (jobId == 0 || app.jobs.get(jobId) == null) return;
    try {
      DcaBridge.verify(h.act(), p.getString("pin", DcaBridge.DEBUG_PIN));
      if (result == android.app.Activity.RESULT_OK && data != null) {
        DcaExecCodec.Reply r = DcaExecCodec.parse(data.getStringExtra(DcaBridge.RESULT));
        if (!r.ok) {
          app.jobs.markUnknown(jobId, "DroidCommand refused or could not process the request: " + r.error);
          return;
        }
        if (!DcaExecCodec.apply(app.jobs, jobId, r) && !check) app.jobs.markUnknown(jobId, "DroidCommand answered without a usable receipt.");
        if (check && !r.known) app.jobs.markUnknown(jobId, "DroidCommand has no record of this request. It did not run.");
      } else if (!check) {
        app.jobs.markUnknown(jobId, "DroidCommand returned no receipt (cancelled, refused, or not installed). Use Check status.");
      }
    } catch (Exception e) {
      app.jobs.markUnknown(jobId, "Receipt could not be verified: " + e.getMessage());
    }
  }

  // ------------------------------------------------------------------ artifacts
  private void artifactCard(LinearLayout body, Jobs.Job j, JSONObject a) {
    LinearLayout c = ui.card(body);
    String kind = a.optString("kind");
    c.addView(ui.text((kind.equals("apk") ? "APK" : kind.equals("patch") ? "PATCH" : kind.equals("screenshot") ? "SCREENSHOT" : kind.equals("report") ? "REPORT" : "FILE") + " · reported by the PC runner", 10, Ui.ACCENT));
    c.addView(ui.heading(a.optString("remote_name"), 16));
    c.addView(ui.text(a.optLong("size") + " bytes\nSHA-256 (runner): " + a.optString("sha256") + "\n" + (a.optInt("verified") == 1 ? "✓ Downloaded here and matches that checksum." : "Not downloaded yet."), 11, Ui.MUTED));
    if (kind.equals("apk")) c.addView(ui.text("APKs are never installed from here. Download and verify, then install it yourself after checking the checksum.", 11, Ui.WARN));
    c.addView(ui.button(a.optInt("verified") == 1 ? "Open preview" : "Download and verify", () -> artifact(j, a), false));
  }

  private void artifact(Jobs.Job j, JSONObject a) {
    app.runAsync(() -> {
      try {
        String name = a.optString("remote_name");
        File f = new File(h.act().getFilesDir(), "artifacts/" + j.id + "/" + name);
        byte[] data;
        if (a.optInt("verified") == 1 && f.isFile()) data = java.nio.file.Files.readAllBytes(f.toPath());
        else {
          Profile pc = app.profiles.get(j.profile);
          data = RunnerClient.forProfile(pc, app.secrets).artifact(j.remoteId, name, a.optLong("size"), a.optString("sha256"), 32L * 1024 * 1024);
          f.getParentFile().mkdirs();
          try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
          app.db.update("UPDATE artifacts SET verified=1,local_path=? WHERE id=?", "artifacts/" + j.id + "/" + name, a.optLong("id"));
        }
        final byte[] bytes = data;
        h.act().runOnUiThread(() -> preview(a, bytes, j.bot));
      } catch (Exception e) {
        h.act().runOnUiThread(() -> ui.info("Download failed", String.valueOf(e.getMessage())));
      }
    });
  }

  /** Safe previews only: plain text in a TextView and a bounded bitmap. No WebView, no executing anything. */
  /** The runner's reported type when it is a plain type/subtype, otherwise a generic binary type. */
  static String mimeFor(JSONObject a) {
    String m = a.optString("mime");
    return m.matches("[a-z]+/[a-z0-9.+-]{1,80}") ? m : "application/octet-stream";
  }

  private void preview(JSONObject a, byte[] data, long bot) {
    String kind = a.optString("kind"), name = a.optString("remote_name");
    LinearLayout box = ui.column();
    box.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8));
    if (kind.equals("screenshot")) {
      BitmapFactory.Options o = new BitmapFactory.Options();
      o.inJustDecodeBounds = true;
      BitmapFactory.decodeByteArray(data, 0, data.length, o);
      if (o.outWidth > 0 && o.outHeight > 0 && (long) o.outWidth * o.outHeight < 40_000_000L) {
        o.inJustDecodeBounds = false;
        while (Math.max(o.outWidth, o.outHeight) / Math.max(1, o.inSampleSize) > 1600) o.inSampleSize = Math.max(2, o.inSampleSize * 2);
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (bmp != null) {
          ImageView iv = new ImageView(h.act());
          iv.setImageBitmap(bmp);
          iv.setAdjustViewBounds(true);
          box.addView(iv);
        }
      } else box.addView(ui.text("This image is too large or not decodable for a safe preview.", 13, Ui.WARN));
    } else if (kind.equals("apk")) box.addView(ui.text("APK, " + data.length + " bytes. Not opened or installed by VictorBots.", 13, Ui.FG));
    else {
      String text = new String(data, 0, Math.min(data.length, 60000), java.nio.charset.StandardCharsets.UTF_8);
      TextView tv = ui.text(text + (data.length > 60000 ? "\n…[preview cut; save the file to read it all]" : ""), 11, Ui.FG);
      tv.setTypeface(android.graphics.Typeface.MONOSPACE);
      tv.setTextIsSelectable(true);
      box.addView(tv);
    }
    box.addView(ui.button("Save a copy…", () -> ((MainActivity) h.act()).saveArtifactBytes(data, name), false));
    if (bot != 0) box.addView(ui.button("Save to the bot folder", () -> ((MainActivity) h.act()).saveToBotFolder(bot, name, mimeFor(a), data), false));
    ui.form(name, box, "Close", () -> true, null);
  }
}
