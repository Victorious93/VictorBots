package com.victorious.bots;

import java.util.List;
import org.json.JSONObject;

/**
 * Minimal SQL surface shared by the Android SQLite adapter and the JDBC adapter used by JVM tests,
 * so schema, migrations and every store run unchanged against real SQLite in both places.
 * Arguments may be String, Long, Integer, Boolean or null. Never concatenate user text into SQL.
 */
public interface Db {
  interface Work<T> {
    T run() throws Exception;
  }

  /** Rows as JSON objects; every column is a string (null columns are absent). */
  List<JSONObject> rows(String sql, Object... args);

  /** INSERT; returns the new row id. */
  long insert(String sql, Object... args);

  /** UPDATE/DELETE; returns the number of rows changed. */
  int update(String sql, Object... args);

  /** DDL or statements without arguments. */
  void exec(String sql);

  /** Runs work atomically; nested calls join the outer transaction. */
  <T> T tx(Work<T> work);

  default JSONObject one(String sql, Object... args) {
    List<JSONObject> r = rows(sql, args);
    return r.isEmpty() ? null : r.get(0);
  }

  default long count(String sql, Object... args) {
    JSONObject r = one(sql, args);
    return r == null ? 0 : r.optLong("n");
  }
}
