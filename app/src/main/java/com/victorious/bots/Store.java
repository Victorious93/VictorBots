package com.victorious.bots;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;
import org.json.*;

public final class Store extends SQLiteOpenHelper {
  public Store(Context c) {
    super(c, "victorbots.db", null, 1);
  }

  @Override
  public void onConfigure(SQLiteDatabase db) {
    db.setForeignKeyConstraintsEnabled(true);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE bots(id INTEGER PRIMARY KEY,name TEXT NOT NULL,description TEXT NOT"
            + " NULL,instructions TEXT NOT NULL,avatar TEXT NOT NULL,image TEXT NOT NULL DEFAULT"
            + " '')");
    db.execSQL(
        "CREATE TABLE chats(id INTEGER PRIMARY KEY,bot INTEGER NOT NULL REFERENCES bots(id) ON"
            + " DELETE CASCADE,title TEXT NOT NULL,created INTEGER NOT NULL)");
    db.execSQL(
        "CREATE TABLE messages(id INTEGER PRIMARY KEY,chat INTEGER NOT NULL REFERENCES chats(id) ON"
            + " DELETE CASCADE,role TEXT NOT NULL,body TEXT NOT NULL,status TEXT NOT NULL,created"
            + " INTEGER NOT NULL)");
    db.execSQL(
        "CREATE TABLE tasks(id INTEGER PRIMARY KEY,title TEXT NOT NULL,body TEXT NOT NULL,status"
            + " TEXT NOT NULL,created INTEGER NOT NULL)");
    seed(
        db,
        "Chief of Staff",
        "Turn big goals into clear next actions",
        "You are the Victorious Framework Chief of Staff. Clarify outcomes, break work into"
            + " concrete tasks, and suggest suitable specialist bots. Distinguish verified results"
            + " from plans. Never claim delegation or execution occurred unless a tool result"
            + " proves it. Communication actions require explicit owner approval. You provide text"
            + " advice; this chat cannot execute device tools.",
        "✦");
    seed(
        db,
        "Android Engineer",
        "Build, debug, and ship reliable Android apps",
        "You are the Victorious Framework Android Engineer. Preserve existing behavior, inspect"
            + " evidence before making claims, and produce complete practical code. Never claim"
            + " builds or tests ran unless results were provided. Prefer simple maintainable"
            + " designs. Clearly state missing device or repository context.",
        "⌘");
    seed(
        db,
        "QA Auditor",
        "Find the failure before your users do",
        "You are the Victorious Framework QA Auditor. Identify reproducible defects, security"
            + " boundaries, edge cases, and focused test plans. Separate observed failures from"
            + " hypotheses. Never invent test results. Prioritize by user impact and explain fixes"
            + " clearly.",
        "◎");
    seed(
        db,
        "Knowledge Keeper",
        "Make useful knowledge easy to find",
        "You are the Victorious Framework Knowledge Keeper. Organize supplied notes into concise"
            + " reusable knowledge, retain source attribution, flag outdated or conflicting facts,"
            + " and never claim access to files or memories that were not supplied.",
        "◇");
  }

  private void seed(SQLiteDatabase db, String n, String d, String i, String a) {
    ContentValues v = new ContentValues();
    v.put("name", n);
    v.put("description", d);
    v.put("instructions", i);
    v.put("avatar", a);
    db.insertOrThrow("bots", null, v);
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int old, int next) {
    throw new IllegalStateException("Migration required");
  }

  public List<JSONObject> rows(String sql, String... args) {
    List<JSONObject> out = new ArrayList<>();
    try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
      while (c.moveToNext()) {
        JSONObject row = new JSONObject();
        for (int i = 0; i < c.getColumnCount(); i++)
          try {
            row.put(c.getColumnName(i), c.getString(i));
          } catch (JSONException e) {
            throw new IllegalStateException(e);
          }
        out.add(row);
      }
    }
    return out;
  }

  public JSONObject bot(long id) {
    List<JSONObject> r = rows("SELECT * FROM bots WHERE id=?", "" + id);
    if (r.isEmpty()) throw new IllegalArgumentException("Bot no longer exists");
    return r.get(0);
  }

  public long saveBot(long id, String n, String d, String i, String a) {
    Rules.length(n, 80, "Name");
    Rules.length(i, Rules.MAX_INSTRUCTIONS, "Instructions");
    if (d.length() > 240 || a.length() > 16)
      throw new IllegalArgumentException("Description or avatar is too long.");
    ContentValues v = new ContentValues();
    v.put("name", n.trim());
    v.put("description", d.trim());
    v.put("instructions", i.trim());
    v.put("avatar", a.isEmpty() ? "✦" : a);
    if (id == 0) return getWritableDatabase().insertOrThrow("bots", null, v);
    getWritableDatabase().update("bots", v, "id=?", new String[] {"" + id});
    return id;
  }

  public long newChat(long bot) {
    ContentValues v = new ContentValues();
    v.put("bot", bot);
    v.put("title", "New conversation");
    v.put("created", System.currentTimeMillis());
    return getWritableDatabase().insertOrThrow("chats", null, v);
  }

  public long message(long chat, String role, String body, String status) {
    ContentValues v = new ContentValues();
    v.put("chat", chat);
    v.put("role", role);
    v.put("body", body);
    v.put("status", status);
    v.put("created", System.currentTimeMillis());
    return getWritableDatabase().insertOrThrow("messages", null, v);
  }

  public void finish(long id, String body, boolean ok) {
    ContentValues v = new ContentValues();
    v.put("body", body);
    v.put("status", ok ? "done" : "error");
    v.put("role", ok ? "assistant" : "system");
    getWritableDatabase()
        .update("messages", v, "id=? AND status='pending'", new String[] {"" + id});
  }

  public boolean pending(long chat) {
    return !rows("SELECT id FROM messages WHERE chat=? AND status='pending'", "" + chat).isEmpty();
  }

  public List<Rules.Turn> history(long chat) {
    List<Rules.Turn> out = new ArrayList<>();
    for (JSONObject r :
        rows(
            "SELECT role,body FROM messages WHERE chat=? AND status='done' ORDER BY id", "" + chat))
      out.add(new Rules.Turn(r.optString("role"), r.optString("body")));
    return out;
  }

  public void task(String title, String body) {
    ContentValues v = new ContentValues();
    v.put("title", title);
    v.put("body", body);
    v.put("status", "Draft");
    v.put("created", System.currentTimeMillis());
    getWritableDatabase().insertOrThrow("tasks", null, v);
  }

  public JSONObject exportData() throws JSONException {
    return new JSONObject()
        .put("format", "Victorious Framework VictorBots")
        .put("version", 1)
        .put(
            "bots",
            new JSONArray(
                rows("SELECT id,name,description,instructions,avatar FROM bots ORDER BY id")))
        .put("chats", new JSONArray(rows("SELECT * FROM chats ORDER BY id")))
        .put("messages", new JSONArray(rows("SELECT * FROM messages ORDER BY id")))
        .put("tasks", new JSONArray(rows("SELECT * FROM tasks ORDER BY id")));
  }
}
