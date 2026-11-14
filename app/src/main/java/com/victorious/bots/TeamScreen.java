package com.victorious.bots;

import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.util.*;
import org.json.JSONException;
import org.json.JSONObject;

/** Team conversations: list, create, participant controls, and the round-based chat. */
final class TeamScreen {
  private final Host h;
  private final Ui ui;
  private final BotApp app;
  private EditText composer;
  private String draft = "";

  TeamScreen(Host h) {
    this.h = h;
    this.ui = h.ui();
    this.app = h.app();
  }

  void list(LinearLayout body) {
    body.addView(ui.text("TEAM CONVERSATIONS", 11, Ui.ACCENT));
    body.addView(ui.heading("Specialists, together.", 30));
    body.addView(ui.text("Bots take turns, never in parallel loops. Every round is capped by turns, tokens and time, and you can stop it. Bots talk in text: what a bot says it did is not evidence. Only a job the backend reports completed is.", 14, Ui.MUTED));
    for (JSONObject c : app.db.rows("SELECT * FROM chats WHERE kind='team' ORDER BY id DESC")) {
      LinearLayout card = ui.card(body);
      Teams.Config cfg = Teams.Config.parse(c.optString("config"));
      card.addView(ui.heading(c.optString("title"), 18));
      StringBuilder names = new StringBuilder();
      for (Mentions.Member m : Teams.members(app.db, c.optLong("id"))) names.append(names.length() == 0 ? "" : ", ").append(m.name).append(m.muted ? " (muted)" : "");
      card.addView(ui.text(names + "\nReply mode: " + cfg.mode.label, 12, Ui.MUTED));
      final long id = c.optLong("id");
      card.addView(ui.button("Open", () -> h.go("Teams", "", 0), false));
      ((Button) card.getChildAt(card.getChildCount() - 1)).setOnClickListener(v -> openChat(id));
    }
    body.addView(ui.button("＋  New team chat", this::create, true));
  }

  private void openChat(long id) {
    ((MainActivity) h.act()).openTeam(id);
  }

  private void create() {
    List<JSONObject> bots = app.db.rows("SELECT id,name FROM bots ORDER BY id");
    LinearLayout f = ui.column();
    f.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12));
    EditText title = ui.field(f, "Team name", "Release crew", false);
    f.addView(ui.text("Members (2 to 8)", 12, Ui.ACCENT));
    List<CheckBox> boxes = new ArrayList<>();
    for (JSONObject b : bots) {
      CheckBox cb = new CheckBox(h.act());
      cb.setText(b.optString("name"));
      cb.setTextColor(Ui.FG);
      f.addView(cb);
      boxes.add(cb);
    }
    Teams.Mode[] modes = Teams.Mode.values();
    String[] ml = new String[modes.length];
    for (int i = 0; i < ml.length; i++) ml[i] = modes[i].label;
    f.addView(ui.text("Reply mode", 12, Ui.ACCENT));
    Spinner mode = new Spinner(h.act());
    mode.setAdapter(new ArrayAdapter<>(h.act(), android.R.layout.simple_spinner_dropdown_item, ml));
    f.addView(mode);
    f.addView(ui.text("Chief of Staff routing and the developer→QA review use the bots you pick below (first two members are used if you leave them).", 12, Ui.MUTED));
    String[] names = new String[bots.size()];
    for (int i = 0; i < names.length; i++) names[i] = bots.get(i).optString("name");
    Spinner chief = new Spinner(h.act()), dev = new Spinner(h.act()), qa = new Spinner(h.act());
    for (Spinner s : new Spinner[] {chief, dev, qa}) s.setAdapter(new ArrayAdapter<>(h.act(), android.R.layout.simple_spinner_dropdown_item, names));
    qa.setSelection(Math.min(1, names.length - 1));
    f.addView(ui.text("Chief of Staff", 12, Ui.ACCENT));
    f.addView(chief);
    f.addView(ui.text("Developer (answers first in review mode)", 12, Ui.ACCENT));
    f.addView(dev);
    f.addView(ui.text("QA (reviews the developer's answer once)", 12, Ui.ACCENT));
    f.addView(qa);
    ui.form("New team chat", f, "Create", () -> {
      List<Long> ids = new ArrayList<>();
      for (int i = 0; i < boxes.size(); i++) if (boxes.get(i).isChecked()) ids.add(bots.get(i).optLong("id"));
      Teams.Config c = new Teams.Config();
      c.mode = modes[mode.getSelectedItemPosition()];
      c.chiefBot = bots.get(chief.getSelectedItemPosition()).optLong("id");
      c.devBot = bots.get(dev.getSelectedItemPosition()).optLong("id");
      c.qaBot = bots.get(qa.getSelectedItemPosition()).optLong("id");
      if ((c.mode == Teams.Mode.REVIEW) && c.devBot == c.qaBot) throw new IllegalArgumentException("Developer and QA must be different bots.");
      long id = Teams.create(app.db, title.getText().toString(), ids, c);
      openChat(id);
      return true;
    }, null);
  }

  void chat(LinearLayout page, long chatId) {
    JSONObject chat = app.db.one("SELECT * FROM chats WHERE id=?", chatId);
    if (chat == null) {
      ((MainActivity) h.act()).closeChats();
      return;
    }
    Teams.Config cfg = Teams.Config.parse(chat.optString("config"));
    List<Mentions.Member> members = Teams.members(app.db, chatId);
    LinearLayout head = ui.row();
    head.addView(ui.button("‹", () -> ((MainActivity) h.act()).closeChats(), false), new LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)));
    LinearLayout t = ui.column();
    t.setPadding(ui.dp(12), 0, ui.dp(8), 0);
    t.addView(ui.heading(chat.optString("title"), 18));
    t.addView(ui.text(cfg.mode.label + " · max " + cfg.maxTurns + " turns · " + cfg.tokenBudget + " tokens · " + cfg.timeBudgetMs / 1000 + " s", 11, Ui.ACCENT));
    head.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
    head.addView(ui.button("⋮", () -> menu(chat, cfg, members), false), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(46)));
    page.addView(head);
    StringBuilder who = new StringBuilder("Members: ");
    for (Mentions.Member m : members) who.append(m.name).append(m.muted ? " (muted)" : "").append("  ");
    page.addView(ui.text(who + "\nMention with @Name. " + cfg.mode.explanation, 11, Ui.MUTED));
    LinearLayout messages = ui.column();
    Map<Long, String> names = new HashMap<>();
    for (Mentions.Member m : members) names.put(m.id, m.name);
    for (JSONObject m : app.db.rows("SELECT * FROM messages WHERE chat=? ORDER BY id", chatId)) {
      boolean user = m.optString("role").equals("user");
      boolean notice = m.optString("kind").equals("notice");
      LinearLayout b = ui.card(messages);
      b.setBackground(ui.bg(user ? 0xff283320 : Ui.CARD, 18));
      long author = m.optLong("author");
      String label = user ? "YOU" : notice ? "NOTICE" : (names.containsKey(author) ? names.get(author).toUpperCase(Locale.ROOT) : "BOT");
      if (m.optString("status").equals("pending")) label += " · WORKING";
      b.addView(ui.text(label, 10, notice ? Ui.WARN : Ui.ACCENT));
      TextView body = ui.text(m.optString("body"), 15, notice ? Ui.MUTED : Ui.FG);
      body.setTextIsSelectable(true);
      b.addView(body);
      try {
        JSONObject meta = new JSONObject(m.optString("meta", "{}"));
        if (meta.has("tokens")) b.addView(ui.text("tokens " + meta.optLong("tokens") + (meta.optBoolean("tokens_estimated") ? " (estimate)" : " (reported)") + " · " + meta.optString("model_requested") + (meta.optLong("latency_ms") > 0 ? " · " + meta.optLong("latency_ms") + " ms" : ""), 10, Ui.MUTED));
      } catch (JSONException ignored) {
      }
      if (!user && !notice && m.optString("status").equals("done"))
        for (JobProposals.Proposal p : JobProposals.parse(m.optString("body")))
          b.addView(ui.button("Review proposed job: " + Ui.clip(p.title, 40), () -> ((MainActivity) h.act()).reviewProposal(p, 0, chatId, m.optLong("id")), false));
    }
    ScrollView sv = ui.scroll(messages);
    page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
    sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
    JSONObject round = app.db.one("SELECT * FROM rounds WHERE chat=? ORDER BY id DESC LIMIT 1", chatId);
    boolean running = app.roundRunning(chatId);
    if (running) page.addView(ui.button("■  Stop this round", () -> app.stopRound(chatId), false));
    else if (round != null) page.addView(ui.text("Last round: " + round.optString("state") + (round.optString("reason").isEmpty() ? "" : " — " + round.optString("reason")) + " · " + round.optLong("turns") + " turns · " + round.optLong("tokens") + " tokens" + (round.optInt("tokens_estimated") == 1 ? " (partly estimated)" : " (reported)"), 11, Ui.MUTED));
    composer = new EditText(h.act());
    composer.setText(draft);
    composer.setTextColor(Ui.FG);
    composer.setHintTextColor(Ui.MUTED);
    composer.setHint("Message the team… (@Name to address a bot)");
    composer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    composer.setMaxLines(4);
    composer.setBackground(ui.bg(Ui.CARD, 16));
    composer.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
    page.addView(composer, new LinearLayout.LayoutParams(-1, -2));
    Button send = ui.button("Send  ↑", () -> {
      String text = composer.getText().toString().trim();
      if (text.isEmpty()) return;
      draft = "";
      app.startRound(chatId, text);
      h.refresh();
    }, true);
    send.setEnabled(!running);
    page.addView(send);
    page.addView(ui.text("Each bot answers under its own provider and policy. A bot on DroidCommand cannot take part (it needs your tap each time).", 10, Ui.MUTED));
  }

  void captureDraft() {
    if (composer != null) draft = composer.getText().toString();
  }

  private void menu(JSONObject chat, Teams.Config cfg, List<Mentions.Member> members) {
    long id = chat.optLong("id");
    ui.choose("Team chat", new String[] {"Reply mode and limits…", "Mute / unmute a member…", "Add a member…", "Remove a member…", "Delete this chat"}, w -> {
      switch (w) {
        case 0: limits(id, cfg); break;
        case 1: {
          String[] n = new String[members.size()];
          for (int i = 0; i < n.length; i++) n[i] = members.get(i).name + (members.get(i).muted ? " (muted → unmute)" : " (→ mute)");
          ui.choose("Mute / unmute", n, k -> { Teams.setMuted(app.db, id, members.get(k).id, !members.get(k).muted); h.refresh(); });
          break;
        }
        case 2: {
          List<JSONObject> others = app.db.rows("SELECT id,name FROM bots WHERE id NOT IN (SELECT bot FROM chat_members WHERE chat=?)", id);
          String[] n = new String[others.size()];
          for (int i = 0; i < n.length; i++) n[i] = others.get(i).optString("name");
          ui.choose("Add", n, k -> { try { Teams.addMember(app.db, id, others.get(k).optLong("id")); h.refresh(); } catch (IllegalArgumentException e) { ui.info("Not added", e.getMessage()); } });
          break;
        }
        case 3: {
          String[] n = new String[members.size()];
          for (int i = 0; i < n.length; i++) n[i] = members.get(i).name;
          ui.choose("Remove", n, k -> { try { Teams.removeMember(app.db, id, members.get(k).id); h.refresh(); } catch (IllegalArgumentException e) { ui.info("Not removed", e.getMessage()); } });
          break;
        }
        default:
          ui.confirm("Delete this team chat?", "Messages in it are deleted. The bots, their own conversations and memory are not.", "Delete", () -> {
            app.stopRound(id);
            app.db.update("DELETE FROM chats WHERE id=?", id);
            ((MainActivity) h.act()).closeChats();
          });
      }
    });
  }

  private void limits(long id, Teams.Config cfg) {
    LinearLayout f = ui.column();
    f.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12));
    Teams.Mode[] modes = Teams.Mode.values();
    String[] ml = new String[modes.length];
    for (int i = 0; i < ml.length; i++) ml[i] = modes[i].label;
    Spinner mode = new Spinner(h.act());
    mode.setAdapter(new ArrayAdapter<>(h.act(), android.R.layout.simple_spinner_dropdown_item, ml));
    mode.setSelection(cfg.mode.ordinal());
    f.addView(mode);
    EditText turns = ui.field(f, "Max bot turns per round (1-8)", "" + cfg.maxTurns, false), hops = ui.field(f, "Max bot-to-bot hand-offs (0-4)", "" + cfg.maxHops, false), tokens = ui.field(f, "Token budget per round", "" + cfg.tokenBudget, false), secs = ui.field(f, "Time budget per round (seconds)", "" + cfg.timeBudgetMs / 1000, false);
    ui.form("Reply mode and limits", f, "Save", () -> {
      try {
        Teams.Config c = Teams.Config.parse(cfg.json().toString());
        c.mode = modes[mode.getSelectedItemPosition()];
        c.maxTurns = Integer.parseInt(turns.getText().toString().trim());
        c.maxHops = Integer.parseInt(hops.getText().toString().trim());
        c.tokenBudget = Long.parseLong(tokens.getText().toString().trim());
        c.timeBudgetMs = Long.parseLong(secs.getText().toString().trim()) * 1000;
        Teams.saveConfig(app.db, id, Teams.Config.parse(c.json().toString()));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Limits must be whole numbers.");
      }
      h.refresh();
      return true;
    }, null);
  }
}
