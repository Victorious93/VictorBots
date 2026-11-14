package com.victorious.bots;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** SQLite helper. All logic lives in {@link Db}-based classes; this adds lifecycle and legacy accessors. */
public final class Store extends SQLiteOpenHelper {
  public Store(Context c) {
    super(c, "victorbots.db", null, Migrations.VERSION);
  }

  /** Foreign keys stay off while migrating (table rebuild) and are switched on in onOpen. */
  @Override
  public void onConfigure(SQLiteDatabase db) {
    db.setForeignKeyConstraintsEnabled(false);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    Db d = new AndroidDb(db);
    Migrations.upgrade(d, 0);
    Seeds.bots(d);
    Seeds.profiles(d);
    Skills.seed(d);
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int old, int next) {
    Db d = new AndroidDb(db);
    Migrations.upgrade(d, old);
    Seeds.profiles(d);
    Skills.seed(d);
  }

  @Override
  public void onOpen(SQLiteDatabase db) {
    if (db.isReadOnly()) return;
    db.setForeignKeyConstraintsEnabled(true);
    try (android.database.Cursor c = db.rawQuery("PRAGMA foreign_key_check", null)) {
      if (c.getCount() > 0) throw new IllegalStateException("Database integrity check failed (foreign keys).");
    }
  }

  /** Always the current writable database. */
  public Db db() {
    return new AndroidDb(getWritableDatabase());
  }

  // ---- convenience wrappers used by the UI ----

  public List<JSONObject> rows(String sql, String... args) {
    return db().rows(sql, (Object[]) args);
  }

  public JSONObject bot(long id) {
    JSONObject r = db().one("SELECT * FROM bots WHERE id=?", id);
    if (r == null) throw new IllegalArgumentException("Bot no longer exists");
    return r;
  }

  public long saveBot(long id, String n, String d, String i, String a) {
    Rules.length(n, 80, "Name");
    Rules.length(i, Rules.MAX_INSTRUCTIONS, "Instructions");
    if (d.length() > 240 || a.length() > 16) throw new IllegalArgumentException("Description or avatar is too long.");
    Db db = db();
    if (id == 0) return db.insert("INSERT INTO bots(name,description,instructions,avatar,uid) VALUES(?,?,?,?,?)", n.trim(), d.trim(), i.trim(), a.isEmpty() ? "✦" : a, Ids.uid());
    db.update("UPDATE bots SET name=?,description=?,instructions=?,avatar=? WHERE id=?", n.trim(), d.trim(), i.trim(), a.isEmpty() ? "✦" : a, id);
    return id;
  }

  public long newChat(long bot) {
    return db().insert("INSERT INTO chats(bot,title,created,uid) VALUES(?,?,?,?)", bot, "New conversation", System.currentTimeMillis(), Ids.uid());
  }

  public long message(long chat, String role, String body, String status) {
    return message(chat, role, body, status, 0, "{}");
  }

  public long message(long chat, String role, String body, String status, long author, String meta) {
    return db().insert("INSERT INTO messages(chat,role,body,status,created,author,meta) VALUES(?,?,?,?,?,?,?)", chat, role, body, status, System.currentTimeMillis(), author, meta);
  }

  public void finish(long id, String body, boolean ok) {
    finish(id, body, ok, "{}");
  }

  /** Completes a pending message exactly once; late or duplicate completions are ignored. */
  public boolean finish(long id, String body, boolean ok, String meta) {
    return db().update("UPDATE messages SET body=?,status=?,role=?,meta=? WHERE id=? AND status='pending'", body, ok ? "done" : "error", ok ? "assistant" : "system", meta, id) > 0;
  }

  public boolean pending(long chat) {
    return db().count("SELECT COUNT(*) n FROM messages WHERE chat=? AND status='pending'", chat) > 0;
  }

  public List<Rules.Turn> history(long chat) {
    List<Rules.Turn> out = new ArrayList<>();
    for (JSONObject r : db().rows("SELECT role,body FROM messages WHERE chat=? AND status='done' ORDER BY id", chat)) out.add(new Rules.Turn(r.optString("role"), r.optString("body")));
    return out;
  }

  public void task(String title, String body) {
    db().insert("INSERT INTO tasks(title,body,status,created,uid) VALUES(?,?,?,?,?)", title, body, "Draft", System.currentTimeMillis(), Ids.uid());
  }

  public JSONObject exportData() throws JSONException {
    return Backup.export(db());
  }

  public JSONArray list(String sql, Object... args) {
    return new JSONArray(db().rows(sql, args));
  }
}
