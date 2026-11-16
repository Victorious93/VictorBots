package com.victorious.bots;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;
import java.io.*;
import java.util.*;
import org.json.JSONException;
import org.json.JSONObject;

public final class MainActivity extends Activity implements Host {
  static final int DCA = 40, EXPORT = 41, IMAGE = 42, ATTACH = 43, IMPORT = 44, DCA_EXEC = 45, SAVE_ARTIFACT = 46;
  private BotApp app;
  private Ui ui;
  private SharedPreferences prefs;
  private LinearLayout page, body;
  private EditText composer;
  private long botId = 0, chatId = 0, imageBot = 0, subId = 0;
  private String tab = "Bots", sub = "", draft = "";
  private boolean foreground = false;
  private final List<PromptBuilder.Attachment> pending = new ArrayList<>();
  private final List<Attachments.Result> pendingMeta = new ArrayList<>();
  private ProvidersScreen providers;
  private TeamScreen teamScreen;
  private JobsScreen jobsScreen;
  private MoreScreens more;
  private byte[] artifactToSave;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    app = (BotApp) getApplication();
    ui = new Ui(this);
    prefs = getSharedPreferences("settings", 0);
    providers = new ProvidersScreen(this);
    teamScreen = new TeamScreen(this);
    jobsScreen = new JobsScreen(this);
    more = new MoreScreens(this);
    if (state != null) {
      botId = state.getLong("bot");
      chatId = state.getLong("chat");
      tab = state.getString("tab", "Bots");
      sub = state.getString("sub", "");
      subId = state.getLong("subId");
      draft = state.getString("draft", "");
      imageBot = state.getLong("imageBot");
    }
    render();
  }

  @Override
  protected void onResume() {
    super.onResume();
    foreground = true;
    app.changed = () -> {
      if (foreground) {
        captureDraft();
        render();
      }
    };
    render();
  }

  @Override
  protected void onPause() {
    captureDraft();
    foreground = false;
    app.changed = null;
    super.onPause();
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    captureDraft();
    b.putLong("bot", botId);
    b.putLong("chat", chatId);
    b.putString("tab", tab);
    b.putString("sub", sub);
    b.putLong("subId", subId);
    b.putString("draft", draft);
    b.putLong("imageBot", imageBot);
    super.onSaveInstanceState(b);
  }

  @Override
  public void onBackPressed() {
    if (botId != 0 || chatId != 0) {
      botId = chatId = 0;
      draft = "";
      pending.clear();
      pendingMeta.clear();
      render();
    } else if (!sub.isEmpty()) {
      sub = "";
      subId = 0;
      render();
    } else super.onBackPressed();
  }

  // ---- Host ----
  @Override public Activity act() { return this; }
  @Override public BotApp app() { return app; }
  @Override public Ui ui() { return ui; }
  @Override public void refresh() { captureDraft(); render(); }
  @Override public void startForResult(Intent i, int r) { startActivityForResult(i, r); }

  @Override
  public void error(Exception e) {
    if (e instanceof android.content.pm.PackageManager.NameNotFoundException) e = new IllegalStateException("Install DroidCommand AI on this device, or use a provider profile.");
    ui.info("Could not continue", e.getMessage() == null ? "The operation failed. No success has been recorded." : e.getMessage());
  }

  @Override
  public void go(String t, String s, long id) {
    tab = t;
    sub = s;
    subId = id;
    botId = chatId = 0;
    render();
  }

  private void captureDraft() {
    if (composer != null) draft = composer.getText().toString();
    teamScreen.captureDraft();
  }

  void openTeam(long id) {
    botId = 0;
    chatId = id;
    render();
  }

  void closeChats() {
    botId = chatId = 0;
    tab = "Teams";
    render();
  }

  void reviewProposal(JobProposals.Proposal p, long bot, long chat, long msg) {
    jobsScreen.reviewProposal(p, bot, chat, msg);
  }

  void newJobFromTask(String title, String body) {
    jobsScreen.newJob(0, 0, 0, title);
  }

  private void render() {
    composer = null;
    page = ui.column();
    page.setBackgroundColor(Ui.BG);
    page.setPadding(ui.dp(20), 0, ui.dp(20), 0);
    page.setOnApplyWindowInsetsListener((v, insets) -> {
      int top, bottom;
      if (android.os.Build.VERSION.SDK_INT >= 30) {
        android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
        top = bars.top;
        bottom = bars.bottom;
      } else {
        top = insets.getSystemWindowInsetTop();
        bottom = insets.getSystemWindowInsetBottom();
      }
      v.setPadding(ui.dp(20), top + ui.dp(10), ui.dp(20), bottom + ui.dp(8));
      return insets;
    });
    setContentView(page);
    page.requestApplyInsets();
    try {
      if (botId != 0) {
        chat();
        return;
      }
      if (chatId != 0) {
        teamScreen.chat(page, chatId);
        return;
      }
      LinearLayout top = ui.row();
      top.addView(ui.heading("victor / bots", 22), new LinearLayout.LayoutParams(0, -2, 1));
      top.addView(ui.text("VF", 14, Ui.ACCENT));
      page.addView(top);
      ui.gap(page, 14);
      body = ui.column();
      page.addView(ui.scroll(body), new LinearLayout.LayoutParams(-1, 0, 1));
      switch (tab) {
        case "Teams": teamScreen.list(body); break;
        case "Jobs": jobsScreen.render(body, sub, subId); break;
        case "More": more.render(body, sub, subId); break;
        default: bots();
      }
      LinearLayout nav = ui.row();
      for (String name : new String[] {"Bots", "Teams", "Jobs", "More"}) {
        Button b = ui.button(name, () -> { tab = name; sub = ""; subId = 0; render(); }, tab.equals(name));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ui.dp(48), 1);
        p.setMargins(ui.dp(3), ui.dp(10), ui.dp(3), 0);
        nav.addView(b, p);
      }
      page.addView(nav);
    } catch (RuntimeException e) {
      body = ui.column();
      page.removeAllViews();
      page.addView(ui.text("This screen could not be drawn: " + Ui.clip(String.valueOf(e.getMessage()), 200), 14, Ui.BAD));
      page.addView(ui.button("Back to bots", () -> go("Bots", "", 0), true));
    }
  }

  // ---------------------------------------------------------------- bots
  private void bots() {
    body.addView(ui.text("YOUR AI WORKSPACE", 11, Ui.ACCENT));
    body.addView(ui.heading("A bot for every\nbig idea.", 34));
    body.addView(ui.text("Give each specialist a role and a model. Keep the work yours.", 15, Ui.MUTED));
    String note = app.kv.get("migration_note", "");
    if (!note.isEmpty()) body.addView(ui.text("⚠ " + note, 13, Ui.WARN));
    Policy.Mode gm = app.profiles.globalMode();
    body.addView(ui.text("Inference policy: " + gm.label, 12, gm == Policy.Mode.ALLOW_CLOUD ? Ui.MUTED : Ui.ACCENT));
    ui.gap(body, 12);
    for (JSONObject b : app.db.rows("SELECT * FROM bots ORDER BY id")) {
      long id = b.optLong("id");
      LinearLayout c = ui.card(body);
      LinearLayout r = ui.row();
      r.addView(avatar(b), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
      LinearLayout labels = ui.column();
      labels.setPadding(ui.dp(14), 0, 0, 0);
      labels.addView(ui.heading(b.optString("name"), 19));
      Gateway.Plan plan = app.gateway.plan(id, 0, Policy.Purpose.CHAT, false, "");
      labels.addView(ui.text((plan.profile == null ? "no provider" : plan.profile.name) + " · " + (plan.model.isEmpty() ? "model not set" : plan.model) + (b.optLong("profile") == 0 ? " (default)" : ""), 10, Ui.ACCENT));
      r.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
      c.addView(r);
      ui.gap(c, 8);
      c.addView(ui.text(b.optString("description"), 14, Ui.MUTED));
      c.addView(ui.button("Open conversation  →", () -> openBot(id), false));
    }
    body.addView(ui.button("＋  Create a bot", () -> editBot(0), true));
    ui.gap(body, 10);
    body.addView(ui.text("Victorious Framework · v0.2\nChat answers are text. Only a job the backend reports completed counts as work done.", 12, Ui.MUTED));
  }

  private View avatar(JSONObject b) {
    String path = b.optString("image");
    if (!path.isEmpty() && !path.contains("/") && !path.contains("..")) {
      Bitmap bmp = BitmapFactory.decodeFile(new File(getFilesDir(), path).getPath());
      if (bmp != null) {
        ImageView img = new ImageView(this);
        img.setImageBitmap(bmp);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackground(ui.bg(0xff34442a, 14));
        img.setClipToOutline(true);
        img.setContentDescription(b.optString("name") + " avatar");
        return img;
      }
    }
    TextView t = ui.text(b.optString("avatar", "✦"), 26, Ui.ACCENT);
    t.setGravity(Gravity.CENTER);
    t.setBackground(ui.bg(0xff34442a, 14));
    return t;
  }

  private void openBot(long id) {
    botId = id;
    List<JSONObject> chats = app.db.rows("SELECT id FROM chats WHERE bot=? AND kind='dm' ORDER BY id DESC LIMIT 1", id);
    chatId = chats.isEmpty() ? app.store.newChat(id) : chats.get(0).optLong("id");
    draft = "";
    pending.clear();
    pendingMeta.clear();
    render();
  }

  /** Bot editor: identity plus per-bot provider, model, policy, reasoning and project. */
  private void editBot(long id) {
    JSONObject b = id == 0 ? new JSONObject() : app.store.bot(id);
    LinearLayout form = ui.column();
    form.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12));
    EditText name = ui.field(form, "Name", b.optString("name"), false), description = ui.field(form, "Description", b.optString("description"), false),
        icon = ui.field(form, "Avatar symbol", b.optString("avatar", "✦"), false), instructions = ui.field(form, "Instructions", b.optString("instructions"), true);
    instructions.setMinLines(5);
    EditText project = ui.field(form, "Project (bots with the same project can share project memory when you allow it)", b.optString("project"), false);
    List<Profile> all = app.profiles.all();
    String[] pn = new String[all.size() + 1];
    pn[0] = "Inherit default (" + (app.profiles.defaultProfile() == null ? "none" : app.profiles.defaultProfile().name) + ")";
    int sel = 0;
    for (int i = 0; i < all.size(); i++) {
      pn[i + 1] = all.get(i).describe();
      if (all.get(i).id == b.optLong("profile")) sel = i + 1;
    }
    form.addView(ui.text("Provider", 12, Ui.ACCENT));
    Spinner provider = new Spinner(this);
    provider.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, pn));
    provider.setSelection(sel);
    form.addView(provider);
    EditText model = ui.field(form, "Model override (blank = the profile's model)", b.optString("model"), false);
    String[] pol = {"", "pc_only", "pc_preferred"};
    String[] poln = {"Inherit the owner's policy", "Stricter: PC only for this bot", "Stricter: PC first, cloud only if I confirm"};
    form.addView(ui.text("Policy for this bot (can only be stricter than your global policy)", 12, Ui.ACCENT));
    Spinner policy = new Spinner(this);
    policy.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, poln));
    int ps = 0;
    for (int i = 0; i < pol.length; i++) if (pol[i].equals(b.optString("policy"))) ps = i;
    policy.setSelection(ps);
    form.addView(policy);
    EditText reasoning = ui.field(form, "Reasoning effort to send (blank = not sent; only if your provider supports it)", b.optString("reasoning"), false);
    ui.form(id == 0 ? "Create a specialist" : "Edit bot", form, "Save", () -> {
      long saved = app.store.saveBot(id, name.getText().toString(), description.getText().toString(), instructions.getText().toString(), icon.getText().toString());
      int pi = provider.getSelectedItemPosition();
      String re = reasoning.getText().toString().trim();
      if (!re.matches("[a-z]{0,12}")) throw new IllegalArgumentException("Reasoning effort is a short lowercase word such as low, medium or high.");
      app.db.update("UPDATE bots SET profile=?,model=?,policy=?,project=?,reasoning=? WHERE id=?", pi == 0 ? null : (Object) all.get(pi - 1).id, model.getText().toString().trim(), pol[policy.getSelectedItemPosition()], project.getText().toString().trim(), re, saved);
      render();
      return true;
    }, null);
  }

  // ---------------------------------------------------------------- DM chat
  private void chat() {
    JSONObject bot;
    try {
      bot = app.store.bot(botId);
    } catch (Exception e) {
      botId = chatId = 0;
      render();
      return;
    }
    Gateway.Plan plan = app.gateway.plan(botId, 0, Policy.Purpose.CHAT, false, "");
    LinearLayout head = ui.row();
    head.addView(ui.button("‹", () -> { botId = chatId = 0; draft = ""; pending.clear(); pendingMeta.clear(); render(); }, false), new LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)));
    LinearLayout title = ui.column();
    title.setPadding(ui.dp(12), 0, ui.dp(8), 0);
    title.addView(ui.heading(bot.optString("name"), 18));
    title.addView(ui.text(plan.describe() + " · " + plan.mode.label, 11, plan.decision.allowed ? Ui.ACCENT : Ui.WARN));
    head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    head.addView(ui.button("⋮", this::botMenu, false), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(46)));
    page.addView(head);
    ui.gap(page, 8);
    LinearLayout messages = ui.column();
    List<JSONObject> items = app.db.rows("SELECT * FROM messages WHERE chat=? ORDER BY id", chatId);
    if (items.isEmpty()) {
      LinearLayout intro = ui.card(messages);
      intro.addView(avatar(bot), new LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)));
      ui.gap(intro, 12);
      intro.addView(ui.heading("What are we working on?", 25));
      intro.addView(ui.text(bot.optString("description"), 15, Ui.MUTED));
      intro.addView(ui.text("Sent: this bot's instructions, memory you approved, and recent turns that fit the model's token budget. Older turns are omitted, and you are told how many.", 13, Ui.MUTED));
    }
    for (JSONObject m : items) messageView(messages, m, bot);
    ScrollView history = ui.scroll(messages);
    page.addView(history, new LinearLayout.LayoutParams(-1, 0, 1));
    history.post(() -> history.fullScroll(View.FOCUS_DOWN));
    JSONObject pend = app.db.one("SELECT id FROM messages WHERE chat=? AND status='pending'", chatId);
    if (pend != null) {
      long pid = pend.optLong("id");
      page.addView(ui.button(app.isInflight(pid) ? "■  Stop" : "Stop waiting locally", () -> {
        if (app.isInflight(pid)) app.stop(pid);
        else ui.confirm("Stop waiting?", "This does not cancel a request already accepted by the provider. A late response will be ignored.", "Stop waiting", () -> {
          app.db.update("UPDATE messages SET status='error',role='system',body='Stopped waiting locally. Remote completion is unknown.' WHERE chat=? AND status='pending'", chatId);
          prefs.edit().remove("dca_reply").remove("dca_chat").apply();
          render();
        });
      }, false));
    }
    if (!pending.isEmpty()) {
      StringBuilder sb = new StringBuilder("Attached: ");
      for (int i = 0; i < pendingMeta.size(); i++) sb.append(i == 0 ? "" : ", ").append(pendingMeta.get(i).name).append(pendingMeta.get(i).truncated ? " (shortened)" : "");
      page.addView(ui.text(sb.toString(), 12, Ui.ACCENT));
    }
    composer = new EditText(this);
    composer.setText(draft);
    composer.setTextSize(16);
    composer.setTextColor(Ui.FG);
    composer.setHintTextColor(Ui.MUTED);
    composer.setHint("Message your bot… (/skill-name inserts a saved workflow)");
    composer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    composer.setMaxLines(5);
    composer.setBackground(ui.bg(Ui.CARD, 16));
    composer.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
    page.addView(composer, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout actions = ui.row();
    actions.addView(ui.button("📎", this::attachMenu, false), new LinearLayout.LayoutParams(ui.dp(60), -2));
    Button send = ui.button("Send  ↑", this::send, true);
    send.setEnabled(pend == null);
    actions.addView(send, new LinearLayout.LayoutParams(0, -2, 1));
    page.addView(actions);
    page.addView(ui.text(footer(plan, items), 10, Ui.MUTED));
  }

  private String footer(Gateway.Plan plan, List<JSONObject> items) {
    StringBuilder f = new StringBuilder();
    if (plan.profile != null) f.append("Context window ").append(plan.profile.budgetTokens()).append(" tokens (").append(plan.profile.budgetBasis()).append("). ");
    for (int i = items.size() - 1; i >= 0; i--) {
      JSONObject meta;
      try { meta = new JSONObject(items.get(i).optString("meta", "{}")); } catch (JSONException e) { continue; }
      if (items.get(i).optString("status").equals("done") && meta.has("model_requested")) {
        f.append("Last reply: ").append(meta.optString("model_requested"));
        if (!meta.optString("model_reported").isEmpty() && !meta.optString("model_reported").equals(meta.optString("model_requested"))) f.append(" (server reported ").append(meta.optString("model_reported")).append(")");
        f.append(" · tokens in/out ").append(meta.has("in_tokens") ? meta.optLong("in_tokens") : "unknown").append("/").append(meta.has("out_tokens") ? meta.optLong("out_tokens") : "unknown");
        if (meta.has("latency_ms")) f.append(" · ").append(meta.optLong("latency_ms")).append(" ms");
        if (meta.has("ttft_ms")) f.append(" · first token ").append(meta.optLong("ttft_ms")).append(" ms");
        if (plan.profile != null) f.append("\n").append(UsageStats.costLabel(plan.profile, meta.has("in_tokens") ? meta.optLong("in_tokens") : null, meta.has("out_tokens") ? meta.optLong("out_tokens") : null));
        break;
      }
    }
    return f.toString();
  }

  private void messageView(LinearLayout messages, JSONObject m, JSONObject bot) {
    boolean user = m.optString("role").equals("user");
    String status = m.optString("status");
    LinearLayout bubble = ui.card(messages);
    bubble.setBackground(ui.bg(user ? 0xff283320 : Ui.CARD, 18));
    bubble.addView(ui.text(user ? "YOU" : status.equals("error") ? "NOTICE" : status.equals("pending") ? "WAITING" : bot.optString("name").toUpperCase(Locale.ROOT), 10, status.equals("error") ? Ui.WARN : Ui.ACCENT));
    TextView content = ui.text(m.optString("body"), 16, Ui.FG);
    content.setTextIsSelectable(true);
    bubble.addView(content);
    for (JSONObject a : app.db.rows("SELECT name,kind,size,truncated FROM attachments WHERE message=?", m.optLong("id")))
      bubble.addView(ui.text("📎 " + a.optString("name") + " (" + a.optString("kind") + ", " + a.optLong("size") + " bytes" + (a.optInt("truncated") == 1 ? ", shortened" : "") + ")", 11, Ui.MUTED));
    if (status.equals("done") && !user) {
      content.setOnLongClickListener(v -> { messageMenu(m); return true; });
      for (JobProposals.Proposal p : JobProposals.parse(m.optString("body")))
        bubble.addView(ui.button("Review proposed job: " + Ui.clip(p.title, 40), () -> jobsScreen.reviewProposal(p, botId, chatId, m.optLong("id")), false));
    }
    try {
      JSONObject meta = new JSONObject(m.optString("meta", "{}"));
      if (status.equals("error") && meta.has("error_category")) {
        String cat = meta.optString("error_category");
        Profile fb = app.profiles.get(app.kv.num(Profiles.FALLBACK_KEY, 0));
        if ((cat.equals("UNREACHABLE") || cat.equals("TIMEOUT")) && fb != null && app.profiles.globalMode() == Policy.Mode.PC_PREFERRED)
          bubble.addView(ui.button("Retry with " + fb.name + " (cloud) — I confirm", () -> retryOnFallback(m, fb), false));
      }
    } catch (JSONException ignored) {
    }
  }

  private void retryOnFallback(JSONObject failed, Profile fb) {
    JSONObject prev = app.db.one("SELECT body FROM messages WHERE chat=? AND role='user' ORDER BY id DESC LIMIT 1", chatId);
    if (prev == null) return;
    ui.confirm("Send this to " + Hosts.host(fb.base) + "?", "Your PC did not answer. This one request would go to the cloud provider " + fb.name + " (" + fb.model + "). Your provider may charge for it. Nothing else changes: the next message goes back to your PC.", "Send to cloud", () -> {
      draft = prev.optString("body");
      doSend(draft, fb.id, true, false);
    });
  }

  private void botMenu() {
    ui.choose("Bot & conversations", new String[] {"New conversation", "Conversation history", "Edit bot (provider, model, policy)", "Memory for this bot", "Suggest memories from this chat", "Summarise older turns", "Change avatar image", "Delete this conversation", "Delete bot"}, which -> {
      switch (which) {
        case 0: chatId = app.store.newChat(botId); draft = ""; pending.clear(); pendingMeta.clear(); render(); break;
        case 1: chooseChat(); break;
        case 2: editBot(botId); break;
        case 3: long b = botId; botId = chatId = 0; go("More", "memory", b); break;
        case 4: suggestMemories(); break;
        case 5: summarise(); break;
        case 6:
          imageBot = botId;
          startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), IMAGE);
          break;
        case 7: confirmDelete(false); break;
        default: confirmDelete(true);
      }
    });
  }

  private void suggestMemories() {
    Gateway.Plan p = app.gateway.plan(botId, 0, Policy.Purpose.MEMORY, false, "");
    if (!p.decision.allowed) {
      ui.info("Not sent", p.decision.reason + "\n\nYou can still save a memory yourself: long-press any message → Remember this.");
      return;
    }
    ui.confirm("Suggest memories?", "Your messages from this chat (up to 12) will be sent to " + Diagnostics.destination(p.profile) + " using " + p.model + " to propose short facts. Nothing is saved until you accept each one.", "Send", () -> {
      final long b = botId, c = chatId;
      app.runAsync(() -> {
        try {
          int n = new MemoryAssistant(app.db, app.gateway, app.memory).suggest(b, c);
          runOnUiThread(() -> ui.toast(n + " suggestion(s) waiting in Memory → Suggested"));
        } catch (Exception e) {
          runOnUiThread(() -> ui.info("No suggestions", String.valueOf(e.getMessage())));
        }
      });
    });
  }

  private void summarise() {
    Gateway.Plan p = app.gateway.plan(botId, 0, Policy.Purpose.SUMMARY, false, "");
    if (!p.decision.allowed) {
      ui.info("Not sent", p.decision.reason);
      return;
    }
    ui.confirm("Summarise this chat?", "The first messages of this conversation (up to 30) go to " + Diagnostics.destination(p.profile) + ". The result is stored as a labelled, model-generated summary that is included in later prompts as untrusted background.", "Send", () -> {
      final long b = botId, c = chatId;
      app.runAsync(() -> {
        try {
          new MemoryAssistant(app.db, app.gateway, app.memory).summarize(b, c, 30);
        } catch (Exception e) {
          runOnUiThread(() -> ui.info("No summary", String.valueOf(e.getMessage())));
        }
      });
    });
  }

  private void chooseChat() {
    List<JSONObject> chats = app.db.rows("SELECT * FROM chats WHERE bot=? ORDER BY id DESC", botId);
    String[] labels = new String[chats.size()];
    for (int i = 0; i < labels.length; i++) labels[i] = chats.get(i).optString("title");
    ui.choose("Conversations", labels, w -> { chatId = chats.get(w).optLong("id"); draft = ""; render(); });
  }

  private void confirmDelete(boolean whole) {
    ui.confirm(whole ? "Delete this bot and its chats?" : "Delete this conversation?", "This permanently deletes the selected local data" + (whole ? ", including this bot's memory" : "") + ".", "Delete", () -> {
      if (whole) {
        String image = app.store.bot(botId).optString("image");
        app.db.update("DELETE FROM bots WHERE id=?", botId);
        if (!image.isEmpty() && !image.contains("/")) new File(getFilesDir(), image).delete();
        botId = chatId = 0;
      } else {
        app.db.update("DELETE FROM chats WHERE id=?", chatId);
        chatId = app.store.newChat(botId);
      }
      draft = "";
      render();
    });
  }

  private void messageMenu(JSONObject m) {
    String text = m.optString("body");
    ui.choose("Use this message", new String[] {"Copy text", "Remember this (save as memory)", "Create task draft", "Create a job draft…", "Discuss with another bot"}, w -> {
      if (w == 0) copy(text);
      else if (w == 1) {
        try {
          app.memory.add("bot", botId, "", Ui.clip(text, Memory.MAX_BODY), "owner", "active", chatId, m.optLong("id"), "message in “" + app.db.one("SELECT title t FROM chats WHERE id=?", chatId).optString("t") + "”");
          ui.toast("Saved to this bot's memory");
        } catch (IllegalArgumentException e) {
          ui.info("Not saved", e.getMessage() + " Long-press, copy, shorten, then add it in Memory.");
        }
      } else if (w == 2) {
        app.store.task(text.substring(0, Math.min(80, text.length())), text);
        ui.toast("Task draft saved");
      } else if (w == 3) jobsScreen.newJob(botId, chatId, m.optLong("id"), text);
      else {
        List<JSONObject> bots = app.db.rows("SELECT * FROM bots WHERE id!=? ORDER BY id", botId);
        String[] names = new String[bots.size()];
        for (int i = 0; i < names.length; i++) names[i] = bots.get(i).optString("name");
        ui.choose("Choose a specialist", names, n -> {
          botId = bots.get(n).optLong("id");
          chatId = app.store.newChat(botId);
          draft = "Review this context and propose the next step:\n\n" + text;
          render();
        });
      }
    });
  }

  private void attachMenu() {
    List<String> items = new ArrayList<>(Arrays.asList("Choose files (text, source, logs, images)…"));
    items.add("Read from a repository connector…");
    ui.choose("Attach", items.toArray(new String[0]), w -> {
      if (w == 0) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, ATTACH);
      } else more.connectorAttach(botId, (name, text) -> {
        PromptBuilder.Attachment a = new PromptBuilder.Attachment();
        a.name = name;
        a.text = text;
        Attachments.Result r = new Attachments.Result();
        r.name = name;
        r.kind = "text";
        r.size = text.length();
        r.sha256 = Attachments.sha(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        r.text = text;
        pending.add(a);
        pendingMeta.add(r);
        render();
      });
    });
  }

  private void send() {
    String input = composer.getText().toString().trim();
    if (input.isEmpty()) return;
    doSend(input, 0, false, true);
  }

  /** @param confirmedCloud true only when this exact request was confirmed in a dialog naming the destination */
  private void doSend(String input, long profileOverride, boolean confirmedCloud, boolean askConfirm) {
    try {
      JSONObject bot = app.store.bot(botId);
      Skills.Expansion ex = Skills.expand(app.db, input, availableRequirements());
      String text = ex.text;
      Gateway.Plan plan = app.gateway.plan(botId, profileOverride, Policy.Purpose.CHAT, confirmedCloud, "");
      if (plan.profile == null) throw new IllegalStateException("No provider profile exists. Open More → Providers.");
      if (!plan.decision.allowed) {
        if (app.profiles.globalMode() == Policy.Mode.PC_PREFERRED && plan.profile.kind != Policy.Kind.PC && !confirmedCloud) {
          ui.confirm("Use " + (plan.profile.kind == Policy.Kind.CLOUD ? "a cloud provider" : "DroidCommand") + " for this one message?", "Your policy keeps work on your PC first. This message would go to " + Diagnostics.destination(plan.profile) + ". Only this request.", "Send this one", () -> doSend(input, profileOverride, true, false));
          return;
        }
        throw new IllegalStateException(plan.decision.reason);
      }
      JSONObject chatRow = app.db.one("SELECT * FROM chats WHERE id=?", chatId);
      if (plan.profile.kind == Policy.Kind.DROIDCOMMAND) {
        sendViaDroidCommand(bot, chatRow, text, input, plan);
        return;
      }
      if (plan.model.isEmpty()) throw new IllegalStateException("No model is set. Open the bot menu → Edit bot, or More → Providers.");
      PromptBuilder.Built built = new PromptBuilder(app.db, app.memory).build(bot, chatRow, plan.profile, text, pending, "");
      if (!pendingImages(built).isEmpty() && !plan.profile.images()) throw new IllegalStateException("This provider profile is not set up for images. Turn on image input in the profile only if the model supports it.");
      StringBuilder msg = new StringBuilder();
      msg.append("To: ").append(Diagnostics.destination(plan.profile)).append("\nModel: ").append(plan.model).append("\nPolicy: ").append(plan.mode.label).append("\n\n");
      msg.append(built.plan.summary()).append(".\n");
      if (!built.memory.isEmpty()) msg.append("Memory included: ").append(built.memory.size()).append(" item(s) you approved.\n");
      if (built.summaryUsed) msg.append("Includes a model-generated summary of earlier turns.\n");
      if (!ex.used.isEmpty()) msg.append("Skill: ").append(ex.used.get(0).name).append(" v").append(ex.used.get(0).version).append(" (adds instructions only; grants nothing).\n");
      if (!ex.unmet.isEmpty()) msg.append("Skill needs ").append(ex.unmet).append(" which is not available now; it will run as plain text.\n");
      if (!pending.isEmpty()) msg.append(pending.size()).append(" attached file(s) sent as quoted data.\n");
      if (plan.profile.kind == Policy.Kind.CLOUD) msg.append("\nYour provider may charge for usage.");
      Runnable go = () -> {
        try {
          if (app.db.count("SELECT COUNT(*) n FROM messages WHERE chat=? AND status='pending'", chatId) > 0) return;
          long userMsg = app.store.message(chatId, "user", input, "done");
          for (int i = 0; i < pending.size(); i++) saveAttachment(userMsg, pendingMeta.get(i));
          app.db.update("UPDATE chats SET title=? WHERE id=? AND title='New conversation'", Ui.clip(input, 65), chatId);
          long reply = app.store.message(chatId, "system", "Waiting for " + plan.profile.name + "…", "pending");
          draft = "";
          pending.clear();
          pendingMeta.clear();
          app.ask(reply, botId, built.plan.turns, Policy.Purpose.CHAT, confirmedCloud, profileOverride, plan.profile.streaming());
          render();
        } catch (Exception e) {
          error(e);
        }
      };
      if (askConfirm || plan.profile.kind == Policy.Kind.CLOUD) ui.confirm("Send to " + Hosts.host(plan.profile.base) + "?", msg.toString(), "Send", go);
      else go.run();
    } catch (Exception e) {
      error(e);
    }
  }

  private List<String> pendingImages(PromptBuilder.Built b) {
    List<String> out = new ArrayList<>();
    for (PromptBuilder.Attachment a : pending) out.addAll(a.images);
    return out;
  }

  private Set<String> availableRequirements() {
    Set<String> s = new HashSet<>(Arrays.asList("files.attach"));
    Profile pc = app.profiles.runnerProfile();
    if (pc != null && app.profiles.runnerReady(pc)) {
      s.add("pc.runner");
      s.add("pc.repo.read");
    }
    return s;
  }

  private void saveAttachment(long msg, Attachments.Result r) {
    app.db.insert("INSERT INTO attachments(message,name,mime,size,sha256,kind,body,truncated) VALUES(?,?,?,?,?,?,?,?)", msg, r.name, r.mime == null ? "" : r.mime, r.size, r.sha256, r.kind, r.kind.equals("text") ? r.text : "", r.truncated ? 1 : 0);
  }

  /** DroidCommand answers only after its own consent screen; it is never used for unattended work. */
  private void sendViaDroidCommand(JSONObject bot, JSONObject chatRow, String text, String input, Gateway.Plan plan) throws Exception {
    if (prefs.getLong("dca_reply", 0) != 0) throw new IllegalStateException("A DroidCommand request is already pending. Finish it or stop waiting in its conversation first.");
    DcaBridge.verify(this, prefs.getString("pin", DcaBridge.DEBUG_PIN));
    List<Rules.Turn> turns = Rules.context(app.store.history(chatId), bot.optString("instructions") + Memory.block(app.memory.retrieve(botId, bot.optString("project"), text, app.memory.budgetTokens(), 3.0)), text);
    Intent request = DcaBridge.request(turns);
    ui.confirm("Send to DroidCommand AI?", "This sends the bot instructions, approved memory and recent chat (character-budgeted, up to 60,000 characters; DroidCommand reports no token counts) to DroidCommand. DroidCommand then asks you to Allow, and decides which model and where text goes. VictorBots cannot verify that destination.\n\nPolicy: " + plan.mode.label, "Send", () -> {
      try {
        if (app.store.pending(chatId)) return;
        app.store.message(chatId, "user", input, "done");
        app.db.update("UPDATE chats SET title=? WHERE id=? AND title='New conversation'", Ui.clip(input, 65), chatId);
        long reply = app.store.message(chatId, "system", "Waiting for DroidCommand consent…", "pending");
        draft = "";
        prefs.edit().putLong("dca_reply", reply).putLong("dca_chat", chatId).commit();
        try {
          startActivityForResult(request, DCA);
        } catch (Exception e) {
          prefs.edit().remove("dca_reply").remove("dca_chat").apply();
          app.store.finish(reply, "DroidCommand companion interface could not be opened. Install a compatible build.", false);
          throw e;
        }
        render();
      } catch (Exception e) {
        error(e);
      }
    });
  }

  private void copy(String s) {
    ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("VictorBots", s));
    ui.toast("Copied to clipboard");
  }

  // ---------------------------------------------------------------- results
  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    try {
      if (request == DCA) {
        long reply = prefs.getLong("dca_reply", 0);
        prefs.edit().remove("dca_reply").remove("dca_chat").apply();
        if (reply != 0) {
          if (result == RESULT_OK) {
            try {
              DcaBridge.verify(this, prefs.getString("pin", DcaBridge.DEBUG_PIN));
              app.store.finish(reply, DcaBridge.result(data), true);
              app.gateway.logDroidCommand(botId, Policy.Purpose.CHAT, true, "");
              prefs.edit().putBoolean("dca_verified", true).apply();
            } catch (Exception e) {
              app.store.finish(reply, e.getMessage(), false);
              app.gateway.logDroidCommand(botId, Policy.Purpose.CHAT, false, "bad reply");
            }
          } else {
            app.store.finish(reply, "DroidCommand cancelled or refused this request. Check VictorBots registration, signing pin, and consent. No action was executed by this chat.", false);
            app.gateway.logDroidCommand(botId, Policy.Purpose.CHAT, false, "cancelled");
          }
        }
      } else if (request == DCA_EXEC) jobsScreen.onDcaResult(result, data);
      else if (result == RESULT_OK && data != null) {
        if (request == EXPORT && data.getData() != null) {
          try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
            if (out == null) throw new IOException("Could not open export destination.");
            out.write(Backup.export(app.db).toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
          }
          ui.toast("Workspace exported (no keys or tokens included)");
        } else if (request == IMAGE && data.getData() != null) {
          saveAvatar(data.getData(), imageBot);
          ui.toast("Avatar updated");
        } else if (request == ATTACH) handleAttach(data);
        else if (request == IMPORT && data.getData() != null) more.importChosen(readBounded(data.getData(), Backup.MAX_BYTES));
        else if (request == SAVE_ARTIFACT && data.getData() != null && artifactToSave != null) {
          try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
            out.write(artifactToSave);
          }
          artifactToSave = null;
          ui.toast("Saved");
        }
      }
    } catch (Exception e) {
      error(e);
    }
    render();
  }

  public void saveArtifactBytes(byte[] data, String name) {
    artifactToSave = data;
    startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, name), SAVE_ARTIFACT);
  }

  private String readBounded(Uri uri, int max) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (InputStream in = getContentResolver().openInputStream(uri)) {
      if (in == null) throw new IOException("Cannot open the file.");
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) != -1) {
        if (out.size() + n > max) throw new IOException("The file is larger than " + (max / 1048576) + " MB.");
        out.write(b, 0, n);
      }
    }
    return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
  }

  private void handleAttach(Intent data) {
    List<Uri> uris = new ArrayList<>();
    if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
    else if (data.getData() != null) uris.add(data.getData());
    StringBuilder refused = new StringBuilder();
    for (Uri u : uris) {
      if (pending.size() >= Attachments.MAX_FILES) {
        refused.append("• Only ").append(Attachments.MAX_FILES).append(" files per message.\n");
        break;
      }
      try {
        String name = displayName(u);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean cut = false;
        try (InputStream in = getContentResolver().openInputStream(u)) {
          byte[] b = new byte[8192];
          int n;
          while ((n = in.read(b)) != -1) {
            if (out.size() + n > Attachments.MAX_READ_BYTES) { cut = true; break; }
            out.write(b, 0, n);
          }
        }
        Attachments.Result r = Attachments.classify(name, out.toByteArray(), cut);
        if (r.kind.equals("unsupported")) {
          refused.append("• ").append(r.name).append(": ").append(r.reason).append('\n');
          continue;
        }
        PromptBuilder.Attachment a = new PromptBuilder.Attachment();
        a.name = r.name;
        a.text = r.text;
        a.truncated = r.truncated;
        if (r.kind.equals("image")) a.images.add(r.dataUri);
        pending.add(a);
        pendingMeta.add(r);
      } catch (Exception e) {
        refused.append("• ").append(u.getLastPathSegment()).append(": could not be read.\n");
      }
    }
    if (refused.length() > 0) ui.info("Some files were not attached", refused.toString());
  }

  private String displayName(Uri u) {
    try (android.database.Cursor c = getContentResolver().query(u, new String[] {android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (c != null && c.moveToFirst()) return Attachments.cleanName(c.getString(0));
    } catch (RuntimeException ignored) {
    }
    return "file";
  }

  private void saveAvatar(Uri uri, long id) throws Exception {
    app.store.bot(id);
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    try (InputStream in = getContentResolver().openInputStream(uri)) {
      if (in == null) throw new IOException("Cannot open image");
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) != -1) {
        if (data.size() + n > 8 * 1024 * 1024) throw new IOException("Choose an image under 8 MB.");
        data.write(b, 0, n);
      }
    }
    byte[] bytes = data.toByteArray();
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("Unsupported image");
    options.inJustDecodeBounds = false;
    options.inSampleSize = 1;
    while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2;
    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    if (bitmap == null) throw new IOException("Could not decode image");
    String name = "avatar-" + id + ".png";
    try (OutputStream out = new FileOutputStream(new File(getFilesDir(), name))) {
      if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("Could not save image");
    }
    bitmap.recycle();
    app.db.update("UPDATE bots SET image=? WHERE id=?", name, id);
  }
}
