package com.victorious.bots;

import android.content.Intent;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The "More" tab: providers, memory, skills, routines, connectors, tasks, backup, DroidCommand link. */
final class MoreScreens {
  interface Attach {
    void accept(String name, String text);
  }

  private final Host h;
  private final Ui ui;
  private final BotApp app;
  private final ProvidersScreen providers;
  private String memTab = "active", memSearch = "";

  MoreScreens(Host h) {
    this.h = h;
    this.ui = h.ui();
    this.app = h.app();
    this.providers = new ProvidersScreen(h);
  }

  void render(LinearLayout body, String sub, long id) {
    switch (sub) {
      case "providers": back(body); providers.render(body); return;
      case "usage": back(body); providers.renderUsage(body); return;
      case "memory": back(body); memory(body, id); return;
      case "skills": back(body); skills(body); return;
      case "routines": back(body); routines(body); return;
      case "connectors": back(body); connectors(body); return;
      case "tasks": back(body); tasks(body); return;
      case "backup": back(body); backup(body); return;
      case "dca": back(body); dcaLink(body); return;
      default: menu(body);
    }
  }

  private void back(LinearLayout body) {
    body.addView(ui.button("‹ More", () -> h.go("More", "", 0), false));
  }

  private void menu(LinearLayout body) {
    body.addView(ui.text("WORKSPACE", 11, Ui.ACCENT));
    body.addView(ui.heading("Everything else.", 30));
    String[][] items = {
        {"providers", "Providers, policy & connection tests", "Profiles for cloud and PC models, who may receive text, per-request diagnostics."},
        {"usage", "Usage & requests", "Every request with destination, model and measured tokens/latency."},
        {"memory", "Memory", "What each bot remembers. Review, edit, forget, search."},
        {"skills", "Skills", "Reusable task instructions (they never grant permissions)."},
        {"routines", "Routines", "Opt-in scheduled work, on the PC or the phone."},
        {"connectors", "Connectors", "Read-only repository access through your PC runner."},
        {"tasks", "Task drafts", "Notes to yourself. Status here is manual."},
        {"backup", "Backup & restore", "Versioned export; restore with preview and conflict handling."},
        {"dca", "DroidCommand link", "Pairing, pinned certificate, text chat vs execution."},
    };
    for (String[] it : items) {
      LinearLayout c = ui.card(body);
      c.addView(ui.heading(it[1], 17));
      c.addView(ui.text(it[2], 12, Ui.MUTED));
      c.addView(ui.button("Open", () -> h.go("More", it[0], 0), false));
    }
    body.addView(ui.text("VictorBots 0.2.0 · development build signed with a public development key. See MANUAL.md for setup, security and troubleshooting.", 11, Ui.MUTED));
  }

  // ------------------------------------------------------------------ memory
  private void memory(LinearLayout body, long botId) {
    body.addView(ui.text("MEMORY", 11, Ui.ACCENT));
    body.addView(ui.heading("What bots remember.", 28));
    body.addView(ui.text("Only memories you approved are used, and only for the bot they belong to. Project memory is shared among bots with the same project name, and only while you switch sharing on. Nothing is uploaded to create memory unless you ask, and then your policy applies.", 13, Ui.MUTED));
    List<JSONObject> bots = app.db.rows("SELECT id,name,project FROM bots ORDER BY id");
    if (botId == 0 && !bots.isEmpty()) botId = bots.get(0).optLong("id");
    final long bid = botId;
    String[] names = new String[bots.size()];
    String project = "";
    for (int i = 0; i < names.length; i++) {
      names[i] = bots.get(i).optString("name");
      if (bots.get(i).optLong("id") == bid) project = bots.get(i).optString("project");
    }
    final String proj = project;
    body.addView(ui.button("Bot: " + (bots.isEmpty() ? "-" : app.store.bot(bid).optString("name")) + "  (change)", () -> ui.choose("Whose memory?", names, w -> h.go("More", "memory", bots.get(w).optLong("id"))), false));
    Switch share = ui.toggle(body, "Share project memory between bots with the same project name" + (proj.isEmpty() ? " (this bot has no project)" : " (“" + proj + "”)"), app.memory.sharingOn());
    share.setOnCheckedChangeListener((b, on) -> app.kv.put(Memory.SHARE_KEY, on ? "1" : "0"));
    body.addView(ui.text("Budget per request: " + app.memory.budgetTokens() + " tokens", 12, Ui.MUTED));
    LinearLayout tabs = ui.row();
    for (String t : new String[] {"active", "suggested"}) {
      Button b = ui.button(t.equals("active") ? "Saved" : "Suggested (" + app.memory.list(bid, proj, "suggested", "").size() + ")", () -> { memTab = t; h.refresh(); }, memTab.equals(t));
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
      p.setMargins(ui.dp(3), 0, ui.dp(3), 0);
      tabs.addView(b, p);
    }
    body.addView(tabs);
    EditText search = ui.field(body, "Search memory", memSearch, false);
    body.addView(ui.button("Search", () -> { memSearch = search.getText().toString(); h.refresh(); }, false));
    body.addView(ui.button("＋  Remember something", () -> addMemory(bid, proj), true));
    for (Memory.Item m : app.memory.list(bid, proj, memTab, memSearch)) {
      LinearLayout c = ui.card(body);
      c.addView(ui.text((m.scope.equals("project") ? "PROJECT · " + m.project : "THIS BOT") + (m.pinned ? " · pinned" : ""), 10, Ui.ACCENT));
      c.addView(ui.text(m.body, 15, Ui.FG));
      c.addView(ui.text(m.provenance(), 11, Ui.MUTED));
      if (m.status.equals("suggested")) {
        c.addView(ui.button("Accept", () -> { app.memory.setStatus(m.id, "active"); h.refresh(); }, true));
        c.addView(ui.button("Dismiss", () -> { app.memory.setStatus(m.id, "dismissed"); h.refresh(); }, false));
      } else {
        c.addView(ui.button("Edit", () -> editMemory(m), false));
        c.addView(ui.button(m.pinned ? "Unpin" : "Pin", () -> { app.memory.pin(m.id, !m.pinned); h.refresh(); }, false));
      }
      c.addView(ui.button("Forget", () -> ui.confirm("Forget this?", "It is deleted from this phone and from search.", "Forget", () -> { app.memory.forget(m.id); h.refresh(); }), false));
    }
  }

  private void addMemory(long bot, String project) {
    LinearLayout f = ui.column();
    f.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12));
    EditText text = ui.field(f, "A short fact or preference (max " + Memory.MAX_BODY + " characters)", "", true);
    CheckBox proj = new CheckBox(h.act());
    proj.setText("Project memory (visible to bots in project “" + project + "” when sharing is on)");
    proj.setTextColor(Ui.FG);
    proj.setEnabled(!project.isEmpty());
    f.addView(proj);
    ui.form("Remember", f, "Save", () -> {
      boolean p = proj.isChecked() && !project.isEmpty();
      app.memory.add(p ? "project" : "bot", bot, p ? project : "", text.getText().toString(), "owner", "active", 0, 0, "");
      h.refresh();
      return true;
    }, null);
  }

  private void editMemory(Memory.Item m) {
    LinearLayout f = ui.column();
    f.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12));
    EditText text = ui.field(f, "Memory", m.body, true);
    ui.form("Edit memory", f, "Save", () -> { app.memory.edit(m.id, text.getText().toString()); h.refresh(); return true; }, null);
  }

  // ------------------------------------------------------------------ skills
  private void skills(LinearLayout body) {
    body.addView(ui.text("SKILLS", 11, Ui.ACCENT));
    body.addView(ui.heading("Reusable instructions.", 28));
    body.addView(ui.text("Type /name in a message to add a skill's instructions. A skill is only text. It lists what it needs, but never grants a permission, never starts a job and never changes policy.", 13, Ui.MUTED));
    for (Skills.Skill s : Skills.all(app.db)) {
      LinearLayout c = ui.card(body);
      c.addView(ui.text((s.builtin ? "BUNDLED" : "YOURS") + " · v" + s.version + (s.requires.isEmpty() ? "" : " · needs " + s.requires), 10, Ui.ACCENT));
      c.addView(ui.heading("/" + s.name, 17));
      c.addView(ui.text(s.description, 13, Ui.MUTED));
      c.addView(ui.button("View", () -> ui.info("/" + s.name + " v" + s.version, s.body + (s.requires.isEmpty() ? "" : "\n\nDeclared needs (informational): " + s.requires)), false));
      if (!s.builtin) {
        c.addView(ui.button("Edit", () -> editSkill(s), false));
        c.addView(ui.button("Delete", () -> ui.confirm("Delete skill?", "/" + s.name, "Delete", () -> { Skills.delete(app.db, s.id); h.refresh(); }), false));
      } else c.addView(ui.button("Duplicate to edit", () -> editSkill(copyOf(s)), false));
    }
    body.addView(ui.button("＋  New skill", () -> editSkill(null), true));
  }

  private Skills.Skill copyOf(Skills.Skill s) {
    Skills.Skill c = new Skills.Skill();
    c.name = s.name + "-copy";
    c.description = s.description;
    c.body = s.body;
    c.requires = new ArrayList<>(s.requires);
    return c;
  }

  private void editSkill(Skills.Skill s) {
    LinearLayout f = ui.column();
    f.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12));
    EditText name = ui.field(f, "Name (lowercase, digits, hyphens)", s == null ? "" : s.name, false), desc = ui.field(f, "Description", s == null ? "" : s.description, false), text = ui.field(f, "Instructions", s == null ? "" : s.body, true);
    String[] reqs = Skills.KNOWN_REQUIREMENTS.toArray(new String[0]);
    Arrays.sort(reqs);
    List<CheckBox> boxes = new ArrayList<>();
    f.addView(ui.text("Needs (informational only; ticking one grants nothing)", 12, Ui.ACCENT));
    for (String r : reqs) {
      CheckBox cb = new CheckBox(h.act());
      cb.setText(r);
      cb.setTextColor(Ui.FG);
      cb.setChecked(s != null && s.requires.contains(r));
      f.addView(cb);
      boxes.add(cb);
    }
    ui.form(s == null || s.id == 0 ? "New skill" : "Edit skill", f, "Save", () -> {
      List<String> req = new ArrayList<>();
      for (int i = 0; i < reqs.length; i++) if (boxes.get(i).isChecked()) req.add(reqs[i]);
      Skills.save(app.db, s == null ? 0 : s.id, name.getText().toString().trim(), desc.getText().toString().trim(), text.getText().toString(), req);
      h.refresh();
      return true;
    }, null);
  }

  // ------------------------------------------------------------------ routines
  private void routines(LinearLayout body) {
    body.addView(ui.text("ROUTINES", 11, Ui.ACCENT));
    body.addView(ui.heading("Scheduled work, opt in.", 28));
    body.addView(ui.text("PC routines run on your PC runner's clock, even when the phone is off or offline; results show here when the phone next connects. Phone routines run only when Android wakes the app (about every 15 minutes at best, later in Doze): never exact. A run counts as succeeded only after its work finished. Routines follow the same inference policy as chat, so under PC-first policies they never use a cloud provider.", 13, Ui.MUTED));
    for (JSONObject r : app.db.rows("SELECT * FROM routines ORDER BY id")) {
      LinearLayout c = ui.card(body);
      boolean pc = r.optString("target").equals("PC");
      c.addView(ui.text((pc ? "RUNS ON YOUR PC" : "RUNS ON THIS PHONE") + " · " + (r.optInt("enabled") == 1 ? "ON" : "OFF"), 10, r.optInt("enabled") == 1 ? Ui.ACCENT : Ui.MUTED));
      c.addView(ui.heading(r.optString("name"), 17));
      c.addView(ui.text(Routines.describe(r.optString("schedule"), r.optString("tz")) + " · overlap: " + r.optString("overlap") + " · if missed: " + r.optString("missed") + (r.has("next_run") && !pc ? "\nNext (approximate): " + new Date(r.optLong("next_run")) : ""), 12, Ui.MUTED));
      c.addView(ui.text(pc ? "If the PC is offline at the scheduled time, the run is missed (or run once later, per your setting). The phone being offline changes nothing." : "If the phone is off or Android defers the job, the run is missed or run once later, per your setting.", 11, Ui.MUTED));
      c.addView(ui.button(r.optInt("enabled") == 1 ? "Switch off" : "Switch on", () -> toggleRoutine(r), false));
      c.addView(ui.button("Run history", () -> history(r), false));
      c.addView(ui.button("Delete", () -> ui.confirm("Delete routine?", pc ? "It is also removed from the PC runner." : "", "Delete", () -> deleteRoutine(r)), false));
    }
    body.addView(ui.button("＋  New routine", this::newRoutine, true));
  }

  private void toggleRoutine(JSONObject r) {
    boolean on = r.optInt("enabled") != 1;
    long id = r.optLong("id");
    if (r.optString("target").equals("PC")) {
      app.runAsync(() -> {
        try {
          Profile pc = app.profiles.get(r.optLong("profile"));
          RunnerClient c = RunnerClient.forProfile(pc, app.secrets);
          app.db.update("UPDATE routines SET enabled=? WHERE id=?", on ? 1 : 0, id);
          JSONObject def = Routines.pcDefinition(app.db.one("SELECT * FROM routines WHERE id=?", id));
          c.putRoutine(r.optString("uid"), def);
          app.db.update("UPDATE routines SET remote_id=? WHERE id=?", r.optString("uid"), id);
        } catch (Exception e) {
          app.db.update("UPDATE routines SET enabled=? WHERE id=?", on ? 0 : 1, id);
          h.act().runOnUiThread(() -> ui.info("The runner did not accept this", String.valueOf(e.getMessage()) + "\nThe routine keeps its previous state."));
        }
      });
    } else {
      Routines.setEnabled(app.db, id, on);
      RoutineService.reschedule(h.act());
      h.refresh();
    }
  }

  private void deleteRoutine(JSONObject r) {
    long id = r.optLong("id");
    if (r.optString("target").equals("PC") && !r.optString("remote_id").isEmpty()) {
      app.runAsync(() -> {
        try {
          Profile pc = app.profiles.get(r.optLong("profile"));
          RunnerClient.forProfile(pc, app.secrets).deleteRoutine(r.optString("uid"));
          app.db.update("DELETE FROM routines WHERE id=?", id);
        } catch (Exception e) {
          h.act().runOnUiThread(() -> ui.info("Not deleted", "The PC runner could not be reached, so its copy would keep running. Try again when it is reachable. " + e.getMessage()));
        }
      });
    } else {
      app.db.update("DELETE FROM routines WHERE id=?", id);
      RoutineService.reschedule(h.act());
      h.refresh();
    }
  }

  private void history(JSONObject r) {
    StringBuilder b = new StringBuilder();
    if (r.optString("target").equals("PHONE")) {
      for (JSONObject x : app.db.rows("SELECT * FROM routine_runs WHERE routine=? ORDER BY scheduled DESC LIMIT 30", r.optLong("id")))
        b.append(new Date(x.optLong("scheduled"))).append(" — ").append(x.optString("status")).append(x.optString("error").isEmpty() ? "" : " (" + x.optString("error") + ")").append('\n');
      ui.info("Phone run history", b.length() == 0 ? "No runs yet." : b.toString());
    } else {
      app.runAsync(() -> {
        try {
          Profile pc = app.profiles.get(r.optLong("profile"));
          JSONObject o = RunnerClient.forProfile(pc, app.secrets).routineRuns(r.optString("uid"));
          JSONArray a = o.optJSONArray("runs");
          if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.getJSONObject(i);
            b.append(new Date((long) (x.optDouble("scheduled") * 1000))).append(" — ").append(x.optString("status")).append(x.has("job_state") ? " · job " + x.optString("job_state") : "").append(x.optString("note").isEmpty() ? "" : " (" + x.optString("note") + ")").append('\n');
          }
          h.act().runOnUiThread(() -> ui.info("PC run history (reported by the runner)", b.length() == 0 ? "No runs recorded." : b.toString()));
        } catch (Exception e) {
          h.act().runOnUiThread(() -> ui.info("Runner unreachable", "Cannot show the PC's history now. " + e.getMessage()));
        }
      });
    }
  }

  private void newRoutine() {
    List<JSONObject> bots = app.db.rows("SELECT id,name FROM bots ORDER BY id");
    String[] bn = new String[bots.size()];
    for (int i = 0; i < bn.length; i++) bn[i] = bots.get(i).optString("name");
    LinearLayout f = ui.column();
    f.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12));
    EditText name = ui.field(f, "Name", "", false);
    Spinner bot = new Spinner(h.act()), target = new Spinner(h.act()), type = new Spinner(h.act());
    bot.setAdapter(new ArrayAdapter<>(h.act(), android.R.layout.simple_spinner_dropdown_item, bn));
    target.setAdapter(new ArrayAdapter<>(h.act(), android.R.layout.simple_spinner_dropdown_item, new String[] {"Runs on this phone (a bot answers an instruction)", "Runs on my PC (a runner tool, even when the phone is offline)"}));
    type.setAdapter(new ArrayAdapter<>(h.act(), android.R.layout.simple_spinner_dropdown_item, new String[] {"Daily", "Weekly", "Every N minutes"}));
    f.addView(ui.text("Bot", 12, Ui.ACCENT));
    f.addView(bot);
    f.addView(ui.text("Where it runs", 12, Ui.ACCENT));
    f.addView(target);
    EditText instruction = ui.field(f, "Phone routine: what should the bot do?", "", true);
    EditText ws = ui.field(f, "PC routine: workspace id", "main", false), tool = ui.field(f, "PC routine: tool (e.g. cmd.unit-tests or repo.search)", "", false);
    f.addView(ui.text("Schedule", 12, Ui.ACCENT));
    f.addView(type);
    EditText time = ui.field(f, "Time HH:MM (daily/weekly)", "09:00", false), days = ui.field(f, "Weekdays for weekly, 0=Mon … 6=Sun, comma separated", "0,1,2,3,4", false), mins = ui.field(f, "Minutes (interval; phone minimum 15)", "60", false), tz = ui.field(f, "Time zone", TimeZone.getDefault().getID(), false);
    String[] ov = {"skip", "queue"}, ms = {"skip", "run_once"};
    Switch overlap = ui.toggle(f, "If the previous run is still going, skip the new one", true);
    Switch missed = ui.toggle(f, "If a run was missed, skip it (off = run it once when possible)", true);
    ui.form("New routine (created switched off)", f, "Create", () -> {
      try {
        String t = new String[] {"daily", "weekly", "interval"}[type.getSelectedItemPosition()];
        JSONObject sch = new JSONObject().put("type", t);
        if (t.equals("interval")) sch.put("minutes", Integer.parseInt(mins.getText().toString().trim()));
        else sch.put("time", time.getText().toString().trim());
        if (t.equals("weekly")) {
          JSONArray d = new JSONArray();
          for (String s : days.getText().toString().split(",")) if (!s.trim().isEmpty()) d.put(Integer.parseInt(s.trim()));
          sch.put("days", d);
        }
        boolean pc = target.getSelectedItemPosition() == 1;
        Profile pcp = app.profiles.runnerProfile();
        if (pc && pcp == null) throw new IllegalArgumentException("Add your PC under Providers first.");
        if (pc && pcp.runner() == null) throw new IllegalArgumentException(VictorLinux.NO_RUNNER + ". “" + pcp.name + "” has no runner URL; add one under Providers.");
        String args = pc ? new JSONObject().put("workspace", ws.getText().toString().trim()).put("tool_args", new JSONObject()).toString() : "{}";
        Routines.create(app.db, name.getText().toString(), bots.get(bot.getSelectedItemPosition()).optLong("id"), pc ? "PC" : "PHONE", pc ? pcp.id : 0, instruction.getText().toString(), pc ? tool.getText().toString().trim() : "", args, tz.getText().toString().trim(), sch.toString(), ov[overlap.isChecked() ? 0 : 1], ms[missed.isChecked() ? 0 : 1]);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Minutes and weekdays must be numbers.");
      } catch (JSONException e) {
        throw new IllegalArgumentException("Bad schedule.");
      }
      h.refresh();
      return true;
    }, null);
  }

  // ------------------------------------------------------------------ connectors
  private void connectors(LinearLayout body) {
    body.addView(ui.text("CONNECTORS", 11, Ui.ACCENT));
    body.addView(ui.heading("Read-only repository access.", 26));
    body.addView(ui.text("A connector is an MCP server your PC runner is configured to expose, limited by the runner to read-only tools. You enable it per bot and can revoke it any time. VictorBots does not install or run MCP servers; the runner owner configures them. Attached results are quoted as untrusted data.", 13, Ui.MUTED));
    Profile pc = app.profiles.runnerProfile();
    if (pc == null) {
      body.addView(ui.text("Add your PC under Providers first.", 13, Ui.WARN));
      return;
    }
    if (pc.runner() == null) {
      body.addView(ui.text(VictorLinux.NO_RUNNER + ". “" + pc.name + "” has no runner URL; add one under Providers to use connectors.", 13, Ui.WARN));
      return;
    }
    body.addView(ui.button("Load connectors from the runner", () -> app.runAsync(() -> {
      try {
        JSONObject o = RunnerClient.forProfile(pc, app.secrets).connectors();
        JSONArray a = o.getJSONArray("connectors");
        h.act().runOnUiThread(() -> offer(a, pc));
      } catch (Exception e) {
        h.act().runOnUiThread(() -> ui.info("Runner unreachable", String.valueOf(e.getMessage())));
      }
    }), true));
    for (JSONObject r : app.db.rows("SELECT bc.*,b.name bname FROM bot_connectors bc JOIN bots b ON b.id=bc.bot")) {
      LinearLayout c = ui.card(body);
      c.addView(ui.heading(r.optString("bname") + " → " + r.optString("connector"), 16));
      c.addView(ui.text("Tools allowed: " + r.optString("tools"), 12, Ui.MUTED));
      boolean auto = r.optInt("auto") == 1 && !r.optString("workspace").isEmpty();
      c.addView(ui.text(auto ? "The bot may call these tools itself in workspace “" + r.optString("workspace") + "”. Read-only; each call is listed under its reply." : "Owner-driven only: the bot cannot call these tools; you attach a read from the chat.", 12, auto ? Ui.ACCENT : Ui.MUTED));
      c.addView(ui.button(auto ? "Change or stop model use" : "Let the bot call these tools", () -> modelUse(r.optLong("bot"), r.optString("connector")), false));
      c.addView(ui.button("Revoke", () -> { app.db.update("DELETE FROM bot_connectors WHERE bot=? AND connector=?", r.optLong("bot"), r.optString("connector")); h.refresh(); }, false));
    }
  }

  /** Asks for the workspace the bot's own tool calls run in. Empty = switch model use off. */
  private void modelUse(long bot, String connector) {
    LinearLayout f = ui.column();
    f.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12));
    f.addView(ui.text("Lets this bot's model request the connector's read-only tools while you chat, only for chats you start and only on a provider profile with tool calls switched on. The runner still enforces its read-only list, and the results are sent to the model's provider, so a cloud provider would see them. Leave the workspace empty to keep reads owner-driven.", 12, Ui.MUTED));
    JSONObject cur = app.db.one("SELECT workspace FROM bot_connectors WHERE bot=? AND connector=?", bot, connector);
    EditText ws = ui.field(f, "Workspace id on the runner", cur == null ? "" : cur.optString("workspace"), false);
    ui.form("Model use of “" + connector + "”", f, "Save", () -> {
      String w = ws.getText().toString().trim();
      if (!w.isEmpty() && !w.matches("[A-Za-z0-9_-]{1,32}")) throw new IllegalArgumentException("Workspace ids use letters, digits, - and _ (max 32).");
      app.db.update("UPDATE bot_connectors SET workspace=?,auto=? WHERE bot=? AND connector=?", w, w.isEmpty() ? 0 : 1, bot, connector);
      h.refresh();
      return true;
    }, null);
  }

  private void offer(JSONArray conns, Profile pc) {
    try {
      String[] names = new String[conns.length()];
      for (int i = 0; i < names.length; i++) names[i] = conns.getJSONObject(i).getString("id") + " — " + conns.getJSONObject(i).getJSONArray("tools");
      if (names.length == 0) {
        ui.info("None configured", "The runner exposes no connectors. See MANUAL.md → Connectors.");
        return;
      }
      ui.choose("Connector", names, w -> {
        try {
          JSONObject conn = conns.getJSONObject(w);
          List<JSONObject> bots = app.db.rows("SELECT id,name FROM bots ORDER BY id");
          String[] bn = new String[bots.size()];
          for (int i = 0; i < bn.length; i++) bn[i] = bots.get(i).optString("name");
          ui.choose("Enable for which bot?", bn, k -> {
            long bot = bots.get(k).optLong("id");
            app.db.update("INSERT OR REPLACE INTO bot_connectors(bot,connector,profile,tools) VALUES(?,?,?,?)", bot, conn.optString("id"), pc.id, conn.optJSONArray("tools").toString());
            modelUse(bot, conn.optString("id"));
          });
        } catch (JSONException e) {
          h.error(e);
        }
      });
    } catch (JSONException e) {
      h.error(e);
    }
  }

  /** Owner-driven read: pick a tool and arguments; the result becomes a quoted attachment for this message. */
  void connectorAttach(long botId, Attach done) {
    List<JSONObject> rows = app.db.rows("SELECT * FROM bot_connectors WHERE bot=?", botId);
    if (rows.isEmpty()) {
      ui.info("No connector enabled", "Enable a read-only connector for this bot under More → Connectors.");
      return;
    }
    JSONObject c = rows.get(0);
    JSONArray tools;
    try { tools = new JSONArray(c.optString("tools")); } catch (JSONException e) { return; }
    String[] tn = new String[tools.length()];
    for (int i = 0; i < tn.length; i++) tn[i] = tools.optString(i);
    ui.choose("Tool on " + c.optString("connector"), tn, w -> {
      LinearLayout f = ui.column();
      f.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12));
      EditText path = ui.field(f, "Path (absolute path inside the runner's workspace)", "", false), ws = ui.field(f, "Workspace id", "main", false), extra = ui.field(f, "Search pattern (search_files only)", "", false);
      ui.form("Read through the connector", f, "Read", () -> {
        Profile pc = app.profiles.get(c.optLong("profile"));
        JSONObject args = new JSONObject();
        try {
          args.put("path", path.getText().toString().trim());
          if (!extra.getText().toString().isEmpty()) args.put("pattern", extra.getText().toString());
        } catch (JSONException e) {
          return false;
        }
        String tool = tn[w], wsid = ws.getText().toString().trim(), cid = c.optString("connector");
        app.runAsync(() -> {
          try {
            JSONObject res = RunnerClient.forProfile(pc, app.secrets).connectorCall(cid, tool, args, wsid);
            String text = res.optString("text");
            h.act().runOnUiThread(() -> done.accept(cid + ":" + tool + " " + args.optString("path"), Ui.clip(text, Attachments.MAX_SOURCE_CHARS)));
          } catch (Exception e) {
            h.act().runOnUiThread(() -> ui.info("Not read", String.valueOf(e.getMessage())));
          }
        });
        return true;
      }, null);
    });
  }

  // ------------------------------------------------------------------ tasks
  private void tasks(LinearLayout body) {
    body.addView(ui.text("KEEP WORK VISIBLE", 11, Ui.ACCENT));
    body.addView(ui.heading("Your next moves.", 28));
    body.addView(ui.text("Task drafts are notes for you. Status changes here are manual and are not evidence that anything ran. Use Jobs for real execution.", 13, Ui.MUTED));
    body.addView(ui.button("＋  New task", () -> {
      LinearLayout f = ui.column();
      f.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12));
      EditText title = ui.field(f, "Title", "", false), details = ui.field(f, "Instructions", "", true);
      ui.form("New task draft", f, "Save", () -> { Rules.length(title.getText().toString(), 80, "Title"); Rules.length(details.getText().toString(), 12000, "Instructions"); app.store.task(title.getText().toString(), details.getText().toString()); h.refresh(); return true; }, null);
    }, true));
    for (JSONObject t : app.db.rows("SELECT * FROM tasks ORDER BY id DESC")) {
      LinearLayout c = ui.card(body);
      c.addView(ui.text(t.optString("status").toUpperCase(Locale.ROOT) + " (manual)", 10, Ui.ACCENT));
      c.addView(ui.heading(t.optString("title"), 17));
      TextView d = ui.text(t.optString("body"), 13, Ui.MUTED);
      d.setMaxLines(5);
      c.addView(d);
      c.addView(ui.button("Make this a job draft…", () -> ((MainActivity) h.act()).newJobFromTask(t.optString("title"), t.optString("body")), false));
      c.addView(ui.button("Change status", () -> ui.choose("Manual status", new String[] {"Draft", "In progress (manual)", "Done (manual)", "Cancelled (manual)"}, w -> { app.db.update("UPDATE tasks SET status=? WHERE id=?", new String[] {"Draft", "In progress (manual)", "Done (manual)", "Cancelled (manual)"}[w], t.optLong("id")); h.refresh(); }), false));
      c.addView(ui.button("Delete", () -> { app.db.update("DELETE FROM tasks WHERE id=?", t.optLong("id")); h.refresh(); }, false));
    }
  }

  // ------------------------------------------------------------------ backup
  private String pendingImport;

  private void backup(LinearLayout body) {
    body.addView(ui.text("BACKUP", 11, Ui.ACCENT));
    body.addView(ui.heading("Export and restore.", 28));
    body.addView(ui.text("Exports are plain JSON you choose where to save. They never contain API keys or tokens, avatar images, job logs, or your policy. Restore shows a preview first, keeps what you already have by default, and refuses malformed or oversized files. Imported routines arrive switched off.", 13, Ui.MUTED));
    body.addView(ui.button("Export workspace (version " + Backup.VERSION + ")", () -> h.startForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "Victorious-Framework-VictorBots.json"), MainActivity.EXPORT), false));
    body.addView(ui.button("Restore from a backup file…", () -> h.startForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE), MainActivity.IMPORT), true));
    if (pendingImport != null) {
      Backup.Preview pv = Backup.preview(app.db, pendingImport);
      LinearLayout c = ui.card(body);
      c.addView(ui.heading("Restore preview", 18));
      c.addView(ui.text(pv.text(), 12, pv.ok() ? Ui.FG : Ui.BAD));
      if (pv.ok()) {
        c.addView(ui.button("Restore, keeping my versions on conflict", () -> doImport(Backup.OnConflict.SKIP), true));
        c.addView(ui.button("Restore, adding conflicting items as copies", () -> doImport(Backup.OnConflict.KEEP_BOTH), false));
      }
      c.addView(ui.button("Discard", () -> { pendingImport = null; h.refresh(); }, false));
    }
  }

  void importChosen(String text) {
    pendingImport = text;
    h.go("More", "backup", 0);
  }

  private void doImport(Backup.OnConflict oc) {
    String text = pendingImport;
    pendingImport = null;
    Backup.Preview r = Backup.apply(app.db, text, oc);
    ui.info(r.ok() ? "Restored" : "Nothing was changed", r.text());
    h.refresh();
  }

  // ------------------------------------------------------------------ DroidCommand
  private void dcaLink(LinearLayout body) {
    android.content.SharedPreferences prefs = h.act().getSharedPreferences("settings", 0);
    body.addView(ui.text("DROIDCOMMAND AI", 11, Ui.ACCENT));
    body.addView(ui.heading("Same-device link.", 28));
    LinearLayout status = ui.card(body);
    try {
      DcaBridge.verify(h.act(), prefs.getString("pin", DcaBridge.DEBUG_PIN));
      status.addView(ui.text("Installed · signing certificate matches the pin", 14, Ui.ACCENT));
    } catch (android.content.pm.PackageManager.NameNotFoundException e) {
      status.addView(ui.text("Not installed on this device", 14, Ui.WARN));
    } catch (Exception e) {
      status.addView(ui.text("Certificate not trusted · requests blocked", 14, Ui.WARN));
    }
    status.addView(ui.text("Text chat (COMPANION_ASSIST): " + (prefs.getBoolean("dca_verified", false) ? "a reply was received before" : "not verified yet") + ". A matching certificate alone does not prove registration.\nExecution (COMPANION_EXEC): separate contract, needs a DroidCommand build that includes it. Discovery/receipt/Allow all happen in DroidCommand; VictorBots has no approve button of its own.", 12, Ui.MUTED));
    status.addView(ui.button("Show installed fingerprint", () -> { try { ui.info("DroidCommand SHA-256", DcaBridge.fingerprint(h.act())); } catch (Exception e) { h.error(e); } }, false));
    LinearLayout form = ui.card(body);
    EditText pin = ui.field(form, "Trusted DroidCommand certificate SHA-256", prefs.getString("pin", DcaBridge.DEBUG_PIN), true);
    form.addView(ui.button("Save pin", () -> { try { prefs.edit().putString("pin", Rules.pin(pin.getText().toString())).putBoolean("dca_verified", false).apply(); ui.toast("Pin saved"); h.refresh(); } catch (Exception e) { h.error(e); } }, true));
  }
}
