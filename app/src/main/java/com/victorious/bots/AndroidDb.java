package com.victorious.bots;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/** {@link Db} over Android SQLite. */
public final class AndroidDb implements Db {
  private final SQLiteDatabase db;

  public AndroidDb(SQLiteDatabase db) {
    this.db = db;
  }

  private static void bind(SQLiteStatement st, Object[] args) {
    for (int i = 0; i < args.length; i++) {
      Object v = args[i];
      int idx = i + 1;
      if (v == null) st.bindNull(idx);
      else if (v instanceof Long) st.bindLong(idx, (Long) v);
      else if (v instanceof Integer) st.bindLong(idx, (Integer) v);
      else if (v instanceof Boolean) st.bindLong(idx, ((Boolean) v) ? 1 : 0);
      else st.bindString(idx, v.toString());
    }
  }

  @Override
  public List<JSONObject> rows(String sql, Object... args) {
    String[] a = new String[args.length];
    for (int i = 0; i < a.length; i++) a[i] = args[i] == null ? null : args[i] instanceof Boolean ? (((Boolean) args[i]) ? "1" : "0") : args[i].toString();
    List<JSONObject> out = new ArrayList<>();
    try (Cursor c = db.rawQuery(sql, a)) {
      while (c.moveToNext()) {
        JSONObject row = new JSONObject();
        for (int i = 0; i < c.getColumnCount(); i++) {
          if (c.isNull(i)) continue;
          try {
            row.put(c.getColumnName(i), c.getString(i));
          } catch (JSONException e) {
            throw new IllegalStateException(e);
          }
        }
        out.add(row);
      }
    }
    return out;
  }

  @Override
  public long insert(String sql, Object... args) {
    SQLiteStatement st = db.compileStatement(sql);
    try {
      bind(st, args);
      return st.executeInsert();
    } finally {
      st.close();
    }
  }

  @Override
  public int update(String sql, Object... args) {
    SQLiteStatement st = db.compileStatement(sql);
    try {
      bind(st, args);
      return st.executeUpdateDelete();
    } finally {
      st.close();
    }
  }

  @Override
  public void exec(String sql) {
    db.execSQL(sql);
  }

  @Override
  public <T> T tx(Work<T> work) {
    db.beginTransaction();
    try {
      T r = work.run();
      db.setTransactionSuccessful();
      return r;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    } finally {
      db.endTransaction();
    }
  }
}
