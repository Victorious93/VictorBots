package com.victorious.bots;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import org.json.*;

public final class MainActivity extends Activity {
  private static final int BG = 0xff10120f,
      CARD = 0xff1c2019,
      FG = 0xfff0f3e9,
      MUTED = 0xffa5ae9e,
      ACCENT = 0xffb5f36c;
  private static final int DCA = 40, EXPORT = 41, IMAGE = 42;
  private BotApp app;
  private Store db;
  private SharedPreferences prefs;
  private LinearLayout page, body;
  private EditText composer;
  private long botId = 0, chatId = 0, imageBot = 0;
  private String tab = "Bots", draft = "";
  private boolean foreground = false;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    app = (BotApp) getApplication();
    db = app.store;
    prefs = getSharedPreferences("settings", 0);
    if (state != null) {
      botId = state.getLong("bot");
      chatId = state.getLong("chat");
      tab = state.getString("tab", "Bots");
      draft = state.getString("draft", "");
      imageBot = state.getLong("imageBot");
    }
    render();
  }

  @Override
  protected void onResume() {
    super.onResume();
    foreground = true;
    app.changed =
        () -> {
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
    b.putString("draft", draft);
    b.putLong("imageBot", imageBot);
    super.onSaveInstanceState(b);
  }

  @Override
  public void onBackPressed() {
    if (botId != 0) {
      botId = chatId = 0;
      draft = "";
      render();
    } else super.onBackPressed();
  }

  private void captureDraft() {
    if (composer != null) draft = composer.getText().toString();
  }

  private int dp(int n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  private GradientDrawable bg(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(1);
    return l;
  }

  private LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  private TextView text(String s, int size, int color) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setPadding(0, dp(4), 0, dp(4));
    return t;
  }

  private TextView heading(String s, int size) {
    TextView t = text(s, size, FG);
    t.setTypeface(null, Typeface.BOLD);
    return t;
  }

  private void gap(LinearLayout l, int h) {
    View v = new View(this);
    l.addView(v, new LinearLayout.LayoutParams(1, dp(h)));
  }

  private Button button(String label, Runnable run, boolean primary) {
    Button b = new Button(this);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextSize(14);
    b.setTextColor(primary ? BG : FG);
    b.setBackground(bg(primary ? ACCENT : 0xff2b3226, 16));
    b.setMinHeight(dp(46));
    b.setPadding(dp(14), dp(8), dp(14), dp(8));
    b.setOnClickListener(v -> run.run());
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48));
    p.setMargins(0, dp(6), 0, dp(6));
    b.setLayoutParams(p);
    return b;
  }

  private LinearLayout card(LinearLayout parent) {
    LinearLayout l = column();
    l.setPadding(dp(18), dp(16), dp(18), dp(16));
    l.setBackground(bg(CARD, 22));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(0, dp(6), 0, dp(8));
    parent.addView(l, p);
    return l;
  }

  private EditText field(LinearLayout parent, String label, String value, boolean multi) {
    parent.addView(text(label, 12, ACCENT));
    EditText e = new EditText(this);
    e.setText(value);
    e.setTextSize(16);
    e.setTextColor(FG);
    e.setHintTextColor(MUTED);
    e.setBackground(bg(0xff242a20, 12));
    e.setPadding(dp(12), dp(10), dp(12), dp(10));
    e.setSingleLine(!multi);
    e.setInputType(InputType.TYPE_CLASS_TEXT | (multi ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
    if (multi) {
      e.setMinLines(3);
      e.setGravity(Gravity.TOP);
    }
    parent.addView(e, new LinearLayout.LayoutParams(-1, -2));
    gap(parent, 10);
    return e;
  }

  private void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show();
  }

  private void error(Exception e) {
    if (e instanceof android.content.pm.PackageManager.NameNotFoundException)
      e =
          new IllegalStateException(
              "Install DroidCommand AI on this device, or select a direct HTTPS provider in"
                  + " Connect.");
    new AlertDialog.Builder(this)
        .setTitle("Could not continue")
        .setMessage(
            e.getMessage() == null
                ? "The operation failed. No success has been recorded."
                : e.getMessage())
        .setPositiveButton("OK", null)
        .show();
  }

  private ScrollView scroll(LinearLayout child) {
    ScrollView s = new ScrollView(this);
    s.setFillViewport(true);
    s.addView(child);
    return s;
  }

  private void render() {
    composer = null;
    page = column();
    page.setBackgroundColor(BG);
    page.setPadding(dp(20), 0, dp(20), 0);
    page.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          int top, bottom;
          if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars =
                insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            top = bars.top;
            bottom = bars.bottom;
          } else {
            top = insets.getSystemWindowInsetTop();
            bottom = insets.getSystemWindowInsetBottom();
          }
          v.setPadding(dp(20), top + dp(10), dp(20), bottom + dp(8));
          return insets;
        });
    setContentView(page);
    page.requestApplyInsets();
    if (botId != 0) {
      chat();
      return;
    }
    LinearLayout top = row();
    TextView word = heading("victor / bots", 22);
    top.addView(word, new LinearLayout.LayoutParams(0, -2, 1));
    top.addView(text("VF", 14, ACCENT));
    page.addView(top);
    gap(page, 18);
    body = column();
    ScrollView s = scroll(body);
    page.addView(s, new LinearLayout.LayoutParams(-1, 0, 1));
    if (tab.equals("Bots")) bots();
    else if (tab.equals("Tasks")) tasks();
    else connect();
    LinearLayout nav = row();
    for (String name : new String[] {"Bots", "Tasks", "Connect"}) {
      Button b =
          button(
              name,
              () -> {
                tab = name;
                render();
              },
              tab.equals(name));
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1);
      p.setMargins(dp(3), dp(10), dp(3), 0);
      nav.addView(b, p);
    }
    page.addView(nav);
  }

  private void bots() {
    body.addView(text("YOUR AI WORKSPACE", 11, ACCENT));
    body.addView(heading("A bot for every\nbig idea.", 34));
    body.addView(text("Give each specialist a role. Keep the work yours.", 15, MUTED));
    gap(body, 16);
    for (JSONObject b : db.rows("SELECT * FROM bots ORDER BY id")) {
      long id = b.optLong("id");
      LinearLayout c = card(body);
      LinearLayout r = row();
      View avatar = avatar(b);
      r.addView(avatar, new LinearLayout.LayoutParams(dp(48), dp(48)));
      LinearLayout labels = column();
      labels.setPadding(dp(14), 0, 0, 0);
      labels.addView(heading(b.optString("name"), 19));
      labels.addView(text("CUSTOM BOT  ·  PRIVATE HISTORY", 9, ACCENT));
      r.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
      c.addView(r);
      gap(c, 8);
      c.addView(text(b.optString("description"), 14, MUTED));
      c.addView(button("Open conversation  →", () -> openBot(id), false));
    }
    body.addView(button("＋  Create a bot", () -> editBot(0), true));
    gap(body, 10);
    body.addView(
        text(
            "Victorious Framework · v0.1\n"
                + "Bots answer through your selected provider. They do not execute device tools"
                + " from chat.",
            12,
            MUTED));
  }

  private View avatar(JSONObject b) {
    String path = b.optString("image");
    if (!path.isEmpty()) {
      Bitmap bmp = BitmapFactory.decodeFile(new File(getFilesDir(), path).getPath());
      if (bmp != null) {
        ImageView img = new ImageView(this);
        img.setImageBitmap(bmp);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackground(bg(0xff34442a, 14));
        img.setClipToOutline(true);
        img.setContentDescription(b.optString("name") + " avatar");
        return img;
      }
    }
    TextView t = text(b.optString("avatar", "✦"), 26, ACCENT);
    t.setGravity(Gravity.CENTER);
    t.setBackground(bg(0xff34442a, 14));
    return t;
  }

  private void openBot(long id) {
    botId = id;
    List<JSONObject> chats =
        db.rows("SELECT id FROM chats WHERE bot=? ORDER BY id DESC LIMIT 1", "" + id);
    chatId = chats.isEmpty() ? db.newChat(id) : chats.get(0).optLong("id");
    draft = "";
    render();
  }

  private void editBot(long id) {
    JSONObject b = id == 0 ? new JSONObject() : db.bot(id);
    LinearLayout form = column();
    form.setPadding(dp(20), dp(12), dp(20), dp(12));
    EditText name = field(form, "Name", b.optString("name"), false),
        description = field(form, "Description", b.optString("description"), false),
        icon = field(form, "Avatar symbol", b.optString("avatar", "✦"), false),
        instructions = field(form, "Instructions", b.optString("instructions"), true);
    instructions.setMinLines(7);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(id == 0 ? "Create a specialist" : "Edit bot")
            .setView(scroll(form))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create();
    dialog.setOnShowListener(
        v ->
            dialog
                .getButton(-1)
                .setOnClickListener(
                    w -> {
                      try {
                        db.saveBot(
                            id,
                            name.getText().toString(),
                            description.getText().toString(),
                            instructions.getText().toString(),
                            icon.getText().toString());
                        dialog.dismiss();
                        render();
                      } catch (Exception e) {
                        error(e);
                      }
                    }));
    dialog.show();
  }

  private void chat() {
    JSONObject bot;
    try {
      bot = db.bot(botId);
    } catch (Exception e) {
      botId = chatId = 0;
      render();
      return;
    }
    LinearLayout head = row();
    Button back =
        button(
            "‹",
            () -> {
              botId = chatId = 0;
              draft = "";
              render();
            },
            false);
    head.addView(back, new LinearLayout.LayoutParams(dp(46), dp(46)));
    LinearLayout title = column();
    title.setPadding(dp(12), 0, dp(8), 0);
    title.addView(heading(bot.optString("name"), 18));
    title.addView(
        text(
            prefs.getBoolean("direct", false)
                ? "HTTPS provider"
                : "DroidCommand AI · consent required",
            11,
            ACCENT));
    head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    Button menu = button("⋮", this::botMenu, false);
    head.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(46)));
    page.addView(head);
    gap(page, 10);
    LinearLayout messages = column();
    List<JSONObject> items =
        db.rows("SELECT * FROM messages WHERE chat=? ORDER BY id", "" + chatId);
    if (items.isEmpty()) {
      LinearLayout intro = card(messages);
      intro.addView(avatar(bot), new LinearLayout.LayoutParams(dp(56), dp(56)));
      gap(intro, 16);
      intro.addView(heading("What are we working on?", 25));
      intro.addView(text(bot.optString("description"), 15, MUTED));
      intro.addView(
          text(
              "Only this bot’s instructions and recent conversation are sent. Older turns are"
                  + " omitted when the 40,000-character context budget is full.",
              13,
              MUTED));
      intro.addView(
          button(
              "Help me plan my next project",
              () -> {
                draft = "Help me plan my next project. Start by asking what outcome I want.";
                render();
              },
              false));
    }
    for (JSONObject m : items) {
      boolean user = m.optString("role").equals("user");
      LinearLayout bubble = card(messages);
      bubble.setBackground(bg(user ? 0xff283320 : CARD, 18));
      String status = m.optString("status");
      bubble.addView(
          text(
              user
                  ? "YOU"
                  : status.equals("error")
                      ? "CONNECTION NOTICE"
                      : status.equals("pending")
                          ? "WAITING"
                          : bot.optString("name").toUpperCase(Locale.ROOT),
              10,
              status.equals("error") ? 0xffffbe8a : ACCENT));
      TextView content = text(m.optString("body"), 16, FG);
      content.setTextIsSelectable(true);
      bubble.addView(content);
      if (status.equals("done")) {
        content.setOnLongClickListener(
            v -> {
              messageMenu(m.optString("body"));
              return true;
            });
      }
    }
    ScrollView history = scroll(messages);
    page.addView(history, new LinearLayout.LayoutParams(-1, 0, 1));
    history.post(() -> history.fullScroll(View.FOCUS_DOWN));
    if (db.pending(chatId)) {
      page.addView(
          text("Request pending. Device actions are never inferred from replies.", 12, MUTED));
      page.addView(
          button(
              "Stop waiting locally",
              () -> {
                new AlertDialog.Builder(this)
                    .setTitle("Stop waiting?")
                    .setMessage(
                        "This does not cancel a request already accepted by the provider. A late"
                            + " response will be ignored.")
                    .setNegativeButton("Keep waiting", null)
                    .setPositiveButton(
                        "Stop waiting",
                        (d, w) -> {
                          db.getWritableDatabase()
                              .execSQL(
                                  "UPDATE messages SET status='error',role='system',body='Stopped"
                                      + " waiting locally. Remote completion is unknown.' WHERE"
                                      + " chat=? AND status='pending'",
                                  new Object[] {chatId});
                          long pendingChat = prefs.getLong("dca_chat", 0);
                          if (pendingChat == chatId)
                            prefs.edit().remove("dca_reply").remove("dca_chat").apply();
                          render();
                        })
                    .show();
              },
              false));
    }
    composer = new EditText(this);
    composer.setText(draft);
    composer.setTextSize(16);
    composer.setTextColor(FG);
    composer.setHintTextColor(MUTED);
    composer.setHint("Message your bot…");
    composer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    composer.setMaxLines(5);
    composer.setBackground(bg(CARD, 16));
    composer.setPadding(dp(14), dp(12), dp(14), dp(12));
    page.addView(composer, new LinearLayout.LayoutParams(-1, -2));
    Button send = button("Send  ↑", this::send, true);
    send.setEnabled(!db.pending(chatId));
    page.addView(send);
    page.addView(
        text(
            prefs.getString("usage", "Token usage shown when reported by the provider"),
            10,
            MUTED));
  }

  private void botMenu() {
    new AlertDialog.Builder(this)
        .setTitle("Bot & conversations")
        .setItems(
            new String[] {
              "New conversation",
              "Conversation history",
              "Edit bot",
              "Change avatar image",
              "Delete this conversation",
              "Delete bot"
            },
            (d, which) -> {
              switch (which) {
                case 0:
                  chatId = db.newChat(botId);
                  draft = "";
                  render();
                  break;
                case 1:
                  chooseChat();
                  break;
                case 2:
                  editBot(botId);
                  break;
                case 3:
                  imageBot = botId;
                  startActivityForResult(
                      new Intent(Intent.ACTION_OPEN_DOCUMENT)
                          .setType("image/*")
                          .addCategory(Intent.CATEGORY_OPENABLE),
                      IMAGE);
                  break;
                case 4:
                  confirmDelete(false);
                  break;
                case 5:
                  confirmDelete(true);
              }
            })
        .show();
  }

  private void chooseChat() {
    List<JSONObject> chats =
        db.rows("SELECT * FROM chats WHERE bot=? ORDER BY id DESC", "" + botId);
    String[] labels = new String[chats.size()];
    for (int i = 0; i < labels.length; i++) labels[i] = chats.get(i).optString("title");
    new AlertDialog.Builder(this)
        .setTitle("Conversations")
        .setItems(
            labels,
            (d, w) -> {
              chatId = chats.get(w).optLong("id");
              draft = "";
              render();
            })
        .show();
  }

  private void confirmDelete(boolean whole) {
    new AlertDialog.Builder(this)
        .setTitle(whole ? "Delete this bot and its chats?" : "Delete this conversation?")
        .setMessage("This permanently deletes the selected local data.")
        .setNegativeButton("Cancel", null)
        .setPositiveButton(
            "Delete",
            (d, w) -> {
              if (whole) {
                String image = db.bot(botId).optString("image");
                db.getWritableDatabase().delete("bots", "id=?", new String[] {"" + botId});
                if (!image.isEmpty()) new File(getFilesDir(), image).delete();
                botId = chatId = 0;
              } else {
                db.getWritableDatabase().delete("chats", "id=?", new String[] {"" + chatId});
                chatId = db.newChat(botId);
              }
              draft = "";
              render();
            })
        .show();
  }

  private void messageMenu(String text) {
    new AlertDialog.Builder(this)
        .setTitle("Use this message")
        .setItems(
            new String[] {"Copy text", "Create task draft", "Discuss with another bot"},
            (d, w) -> {
              if (w == 0) copy(text);
              else if (w == 1) {
                db.task(text.substring(0, Math.min(80, text.length())), text);
                toast("Task draft saved");
              } else {
                List<JSONObject> bots =
                    db.rows("SELECT * FROM bots WHERE id!=? ORDER BY id", "" + botId);
                String[] names = new String[bots.size()];
                for (int i = 0; i < names.length; i++) names[i] = bots.get(i).optString("name");
                new AlertDialog.Builder(this)
                    .setTitle("Choose a specialist")
                    .setItems(
                        names,
                        (x, n) -> {
                          botId = bots.get(n).optLong("id");
                          chatId = db.newChat(botId);
                          draft = "Review this context and propose the next step:\n\n" + text;
                          render();
                        })
                    .show();
              }
            })
        .show();
  }

  private void send() {
    String input = composer.getText().toString().trim();
    try {
      JSONObject bot = db.bot(botId);
      List<Rules.Turn> turns =
          Rules.context(db.history(chatId), bot.optString("instructions"), input);
      boolean direct = prefs.getBoolean("direct", false);
      String key = "",
          endpoint = prefs.getString("endpoint", ""),
          model = prefs.getString("model", "");
      Intent request = null;
      if (direct) {
        Rules.endpoint(endpoint);
        Rules.length(model, 160, "Model");
        key = new SecretStore(this).read();
      } else {
        if (prefs.getLong("dca_reply", 0) != 0)
          throw new IllegalStateException(
              "A DroidCommand request is already pending. Finish it or stop waiting in its"
                  + " conversation first.");
        DcaBridge.verify(this, prefs.getString("pin", DcaBridge.DEBUG_PIN));
        request = DcaBridge.request(turns);
      }
      final String finalKey = key;
      final Intent finalRequest = request;
      String destination = direct ? new java.net.URI(endpoint).getHost() : "DroidCommand AI";
      new AlertDialog.Builder(this)
          .setTitle("Send to " + destination + "?")
          .setMessage(
              "This sends the bot instructions and recent chat context (up to 40,000 content"
                  + " characters). "
                  + (direct
                      ? "Your provider may charge for usage."
                      : "DroidCommand will ask for consent. Its installed build must register"
                            + " VictorBots first."))
          .setNegativeButton("Cancel", null)
          .setPositiveButton(
              "Send",
              (d, w) -> {
                try {
                  if (db.pending(chatId)) return;
                  db.message(chatId, "user", input, "done");
                  db.getWritableDatabase()
                      .execSQL(
                          "UPDATE chats SET title=? WHERE id=? AND title='New conversation'",
                          new Object[] {input.substring(0, Math.min(65, input.length())), chatId});
                  long reply =
                      db.message(
                          chatId,
                          "system",
                          direct ? "Waiting for provider…" : "Waiting for DroidCommand consent…",
                          "pending");
                  draft = "";
                  if (direct) app.ask(reply, endpoint, finalKey, model, turns);
                  else {
                    prefs.edit().putLong("dca_reply", reply).putLong("dca_chat", chatId).commit();
                    try {
                      startActivityForResult(finalRequest, DCA);
                    } catch (Exception e) {
                      prefs.edit().remove("dca_reply").remove("dca_chat").apply();
                      db.finish(
                          reply,
                          "DroidCommand companion interface could not be opened. Install a"
                              + " compatible build.",
                          false);
                      throw e;
                    }
                  }
                  render();
                } catch (Exception e) {
                  error(e);
                }
              })
          .show();
    } catch (Exception e) {
      error(e);
    }
  }

  private void tasks() {
    body.addView(text("KEEP WORK VISIBLE", 11, ACCENT));
    body.addView(heading("Your next moves.", 32));
    body.addView(
        text(
            "Draft tasks, review them, then work in DroidCommand. Status changes here are manual.",
            15,
            MUTED));
    body.addView(button("＋  New task", this::newTask, true));
    List<JSONObject> tasks = db.rows("SELECT * FROM tasks ORDER BY id DESC");
    if (tasks.isEmpty()) {
      LinearLayout c = card(body);
      c.addView(heading("Space for your next idea", 21));
      c.addView(
          text("Create a task here or long-press a chat reply to save it as a draft.", 15, MUTED));
    }
    for (JSONObject t : tasks) {
      LinearLayout c = card(body);
      c.addView(text(t.optString("status").toUpperCase(Locale.ROOT), 10, ACCENT));
      c.addView(heading(t.optString("title"), 19));
      TextView detail = text(t.optString("body"), 14, MUTED);
      detail.setMaxLines(5);
      c.addView(detail);
      c.addView(button("Review task", () -> taskDetail(t), false));
    }
  }

  private void newTask() {
    LinearLayout f = column();
    f.setPadding(dp(20), dp(12), dp(20), dp(12));
    EditText title = field(f, "Title", "", false), details = field(f, "Instructions", "", true);
    AlertDialog d =
        new AlertDialog.Builder(this)
            .setTitle("New task draft")
            .setView(scroll(f))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create();
    d.setOnShowListener(
        v ->
            d.getButton(-1)
                .setOnClickListener(
                    w -> {
                      try {
                        Rules.length(title.getText().toString(), 80, "Title");
                        Rules.length(details.getText().toString(), 12000, "Instructions");
                        db.task(title.getText().toString(), details.getText().toString());
                        d.dismiss();
                        render();
                      } catch (Exception e) {
                        error(e);
                      }
                    }));
    d.show();
  }

  private void taskDetail(JSONObject t) {
    LinearLayout f = column();
    f.setPadding(dp(20), dp(12), dp(20), dp(12));
    TextView details = text(t.optString("body"), 16, FG);
    details.setTextIsSelectable(true);
    f.addView(details);
    f.addView(
        text(
            "Copy & open does not send or execute this task. Paste it into DroidCommand and review"
                + " its approvals there.",
            13,
            MUTED));
    AlertDialog d =
        new AlertDialog.Builder(this)
            .setTitle(t.optString("title"))
            .setView(scroll(f))
            .setNegativeButton("Close", null)
            .create();
    f.addView(
        button(
            "Copy & open DroidCommand",
            () -> {
              try {
                DcaBridge.verify(this, prefs.getString("pin", DcaBridge.DEBUG_PIN));
                Intent launch = getPackageManager().getLaunchIntentForPackage(DcaBridge.PACKAGE);
                if (launch == null)
                  throw new IllegalStateException("DroidCommand launcher is unavailable.");
                copy(t.optString("body"));
                startActivity(launch);
                setTaskStatus(t.optLong("id"), "Prepared for handoff");
                d.dismiss();
              } catch (Exception e) {
                error(e);
              }
            },
            true));
    f.addView(
        button(
            "Change status",
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("Manual task status")
                    .setItems(
                        new String[] {
                          "Draft", "In progress (manual)", "Done (manual)", "Cancelled (manual)"
                        },
                        (x, w) -> {
                          setTaskStatus(
                              t.optLong("id"),
                              new String[] {
                                    "Draft",
                                    "In progress (manual)",
                                    "Done (manual)",
                                    "Cancelled (manual)"
                                  }
                                  [w]);
                          d.dismiss();
                          render();
                        })
                    .show(),
            false));
    d.show();
  }

  private void setTaskStatus(long id, String status) {
    ContentValues v = new ContentValues();
    v.put("status", status);
    db.getWritableDatabase().update("tasks", v, "id=?", new String[] {"" + id});
  }

  private void copy(String s) {
    ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
        .setPrimaryClip(ClipData.newPlainText("VictorBots", s));
    toast("Copied to clipboard");
  }

  private void connect() {
    body.addView(text("ONE WORKSPACE. YOUR PROVIDER.", 11, ACCENT));
    body.addView(heading("Connect your brain.", 32));
    LinearLayout status = card(body);
    status.addView(heading("DroidCommand AI", 21));
    try {
      DcaBridge.verify(this, prefs.getString("pin", DcaBridge.DEBUG_PIN));
      status.addView(text("Installed · signing certificate matches", 14, ACCENT));
    } catch (android.content.pm.PackageManager.NameNotFoundException e) {
      status.addView(text("Not installed on this device", 14, 0xffffbe8a));
    } catch (Exception e) {
      status.addView(text("Certificate not trusted · requests blocked", 14, 0xffffbe8a));
    }
    status.addView(
        text(
            "Chat connection: "
                + (prefs.getBoolean("dca_verified", false)
                    ? "reply received previously"
                    : "not verified yet")
                + "\n"
                + "The installed DroidCommand build must include VictorBots in its caller"
                + " catalogue. A matching certificate alone does not confirm that registration.",
            13,
            MUTED));
    status.addView(
        button(
            "Show installed fingerprint",
            () -> {
              try {
                new AlertDialog.Builder(this)
                    .setTitle("DroidCommand SHA-256")
                    .setMessage(DcaBridge.fingerprint(this))
                    .setPositiveButton("OK", null)
                    .show();
              } catch (Exception e) {
                error(e);
              }
            },
            false));
    LinearLayout form = card(body);
    Switch direct = new Switch(this);
    direct.setText("Use a direct HTTPS provider");
    direct.setTextColor(FG);
    direct.setChecked(prefs.getBoolean("direct", false));
    form.addView(direct);
    form.addView(
        text(
            "Off: use DroidCommand’s configured model and credentials. On: send chat to your own"
                + " OpenAI-compatible endpoint. Subscription sign-in is handled by DroidCommand"
                + " only where supported.",
            13,
            MUTED));
    EditText endpoint =
        field(form, "Full HTTPS chat endpoint", prefs.getString("endpoint", ""), false);
    endpoint.setHint("https://your-provider/v1/chat/completions");
    EditText model = field(form, "Model identifier", prefs.getString("model", ""), false),
        key = field(form, "API key (blank keeps existing)", "", false);
    key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    EditText pin =
        field(
            form,
            "Trusted DroidCommand certificate SHA-256",
            prefs.getString("pin", DcaBridge.DEBUG_PIN),
            true);
    pin.setMinLines(2);
    form.addView(
        button(
            "Save connection",
            () -> {
              try {
                String p = Rules.pin(pin.getText().toString());
                String url = endpoint.getText().toString().trim();
                if (direct.isChecked()) {
                  Rules.endpoint(url);
                  Rules.length(model.getText().toString(), 160, "Model");
                }
                if (!url.equals(prefs.getString("endpoint", ""))
                    && key.getText().toString().isEmpty()
                    && !new SecretStore(this).read().isEmpty())
                  throw new IllegalArgumentException(
                      "The provider endpoint changed. Enter its API key or remove the old stored"
                          + " key first.");
                if (!key.getText().toString().isEmpty())
                  new SecretStore(this).save(key.getText().toString());
                prefs
                    .edit()
                    .putBoolean("direct", direct.isChecked())
                    .putString("endpoint", url)
                    .putString("model", model.getText().toString().trim())
                    .putString("pin", p)
                    .putBoolean(
                        "dca_verified",
                        p.equals(prefs.getString("pin", DcaBridge.DEBUG_PIN))
                            && prefs.getBoolean("dca_verified", false))
                    .apply();
                toast("Connection settings saved");
                render();
              } catch (Exception e) {
                error(e);
              }
            },
            true));
    form.addView(
        button(
            "Remove stored API key",
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("Remove provider key?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton(
                        "Remove",
                        (d, w) -> {
                          try {
                            new SecretStore(this).save("");
                            toast("Stored key removed");
                          } catch (Exception e) {
                            error(e);
                          }
                        })
                    .show(),
            false));
    LinearLayout data = card(body);
    data.addView(heading("Your data stays yours", 20));
    data.addView(
        text(
            "Bot profiles and chats are stored in the app’s private database. API keys are"
                + " encrypted with Android Keystore. Export contains chat content and instructions,"
                + " never API keys; avatar images are excluded.",
            13,
            MUTED));
    data.addView(
        button(
            "Export workspace JSON",
            () -> {
              Intent i =
                  new Intent(Intent.ACTION_CREATE_DOCUMENT)
                      .setType("application/json")
                      .addCategory(Intent.CATEGORY_OPENABLE)
                      .putExtra(Intent.EXTRA_TITLE, "Victorious-Framework-VictorBots.json");
              startActivityForResult(i, EXPORT);
            },
            false));
  }

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
              db.finish(reply, DcaBridge.result(data), true);
              prefs
                  .edit()
                  .putBoolean("dca_verified", true)
                  .putString(
                      "usage", "DroidCommand does not report token usage through this interface")
                  .apply();
            } catch (Exception e) {
              db.finish(reply, e.getMessage(), false);
            }
          } else
            db.finish(
                reply,
                "DroidCommand cancelled or refused this request. Check VictorBots registration,"
                    + " signing pin, and consent. No action was executed by this chat.",
                false);
        }
      } else if (result == RESULT_OK && data != null && data.getData() != null) {
        if (request == EXPORT) {
          try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
            if (out == null) throw new IOException("Could not open export destination.");
            out.write(
                db.exportData().toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
          }
          toast("Workspace exported");
        } else if (request == IMAGE) {
          saveAvatar(data.getData(), imageBot);
          toast("Avatar updated");
        }
      }
    } catch (Exception e) {
      error(e);
    }
    render();
  }

  private void saveAvatar(Uri uri, long id) throws Exception {
    db.bot(id);
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
    while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 512)
      options.inSampleSize *= 2;
    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    if (bitmap == null) throw new IOException("Could not decode image");
    String name = "avatar-" + id + ".png";
    try (OutputStream out = new FileOutputStream(new File(getFilesDir(), name))) {
      if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
        throw new IOException("Could not save image");
    }
    bitmap.recycle();
    ContentValues v = new ContentValues();
    v.put("image", name);
    db.getWritableDatabase().update("bots", v, "id=?", new String[] {"" + id});
  }
}
