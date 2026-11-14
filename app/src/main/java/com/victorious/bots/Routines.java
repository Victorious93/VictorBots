package com.victorious.bots;

import java.time.*;
import java.util.*;
import org.json.*;

/**
 * Opt-in routines. PC routines are defined on the PC runner and run there on its clock, even when the
 * phone is off; this class only validates, mirrors the definition and displays the runner's history.
 * PHONE routines run inference through the Gateway when Android lets the app wake (inexact, never
 * promised to the minute). A run is "succeeded" only after its reply was stored; it is never marked
 * successful when scheduled or started.
 */
public final class Routines {
  public static final long GRACE_MS = 90_000;

  public static void validate(String target, String tz, String scheduleJson, String argsJson) {
    if (!target.equals("PC") && !target.equals("PHONE")) throw new IllegalArgumentException("Target must be PC or PHONE.");
    try {
      ZoneId.of(tz);
    } catch (DateTimeException e) {
      throw new IllegalArgumentException("Unknown time zone “" + tz + "”.");
    }
    JSONObject s;
    try {
      s = new JSONObject(scheduleJson);
      if (!argsJson.isEmpty()) new JSONObject(argsJson);
    } catch (JSONException e) {
      throw new IllegalArgumentException("Schedule and arguments must be JSON objects.");
    }
    String t = s.optString("type");
    if (t.equals("interval")) {
      int m = s.optInt("minutes", 0), min = target.equals("PHONE") ? 15 : 5;
      if (m < min || m > 7 * 24 * 60) throw new IllegalArgumentException("Interval must be " + min + " minutes to 7 days" + (target.equals("PHONE") ? " (Android does not run phone work more often than every 15 minutes)." : "."));
    } else if (t.equals("daily") || t.equals("weekly")) {
      if (!s.optString("time").matches("([01]\\d|2[0-3]):[0-5]\\d")) throw new IllegalArgumentException("Time must be HH:MM.");
      if (t.equals("weekly")) {
        JSONArray d = s.optJSONArray("days");
        if (d == null || d.length() == 0) throw new IllegalArgumentException("Choose at least one weekday.");
        for (int i = 0; i < d.length(); i++) if (d.optInt(i, -1) < 0 || d.optInt(i, -1) > 6) throw new IllegalArgumentException("Weekdays are 0 (Monday) to 6 (Sunday).");
      }
    } else throw new IllegalArgumentException("Schedule type must be daily, weekly or interval.");
    if (target.equals("PC")) {
      try {
        String ws = new JSONObject(argsJson.isEmpty() ? "{}" : argsJson).optString("workspace");
        if (!ws.matches("[A-Za-z0-9_-]{1,32}")) throw new IllegalArgumentException("A PC routine needs a workspace id.");
      } catch (JSONException e) {
        throw new IllegalArgumentException("Bad arguments.");
      }
    }
  }

  /** Next run strictly after {@code afterMs}. A nonexistent local time moves forward; an ambiguous one runs once. */
  public static long nextAfter(String scheduleJson, String tz, long afterMs) {
    try {
      JSONObject s = new JSONObject(scheduleJson);
      String t = s.getString("type");
      if (t.equals("interval")) return afterMs + s.getLong("minutes") * 60_000L;
      ZoneId z = ZoneId.of(tz);
      String[] hm = s.getString("time").split(":");
      int hh = Integer.parseInt(hm[0]), mm = Integer.parseInt(hm[1]);
      Set<Integer> days = new HashSet<>();
      if (t.equals("weekly")) {
        JSONArray d = s.getJSONArray("days");
        for (int i = 0; i < d.length(); i++) days.add(d.getInt(i));
      } else for (int i = 0; i < 7; i++) days.add(i);
      LocalDate start = Instant.ofEpochMilli(afterMs).atZone(z).toLocalDate();
      for (int add = 0; add < 9; add++) {
        LocalDate d = start.plusDays(add);
        if (!days.contains(d.getDayOfWeek().getValue() - 1)) continue;
        long ts = ZonedDateTime.of(d, LocalTime.of(hh, mm), z).toInstant().toEpochMilli();
        if (ts > afterMs) return ts;
      }
      throw new IllegalArgumentException("No next run found.");
    } catch (JSONException e) {
      throw new IllegalArgumentException("Bad schedule.");
    }
  }

  public static String describe(String scheduleJson, String tz) {
    try {
      JSONObject s = new JSONObject(scheduleJson);
      switch (s.getString("type")) {
        case "interval": return "every " + s.getInt("minutes") + " min";
        case "daily": return "daily at " + s.getString("time") + " " + tz;
        default: {
          String[] n = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
          StringBuilder b = new StringBuilder();
          JSONArray d = s.getJSONArray("days");
          for (int i = 0; i < d.length(); i++) b.append(i == 0 ? "" : ",").append(n[d.getInt(i)]);
          return b + " at " + s.getString("time") + " " + tz;
        }
      }
    } catch (JSONException e) {
      return "invalid schedule";
    }
  }

  public static long create(Db db, String name, long bot, String target, long profile, String instruction, String tool, String argsJson, String tz, String scheduleJson, String overlap, String missed) {
    Rules.length(name, 80, "Routine name");
    validate(target, tz, scheduleJson, argsJson);
    if (target.equals("PHONE")) Rules.length(instruction, 4000, "Instruction");
    return db.insert("INSERT INTO routines(uid,name,bot,target,profile,instruction,tool,args,tz,schedule,enabled,overlap,missed,created) VALUES(?,?,?,?,?,?,?,?,?,?,0,?,?,?)",
        Ids.uid(), name.trim(), bot, target, profile == 0 ? null : profile, instruction == null ? "" : instruction, tool == null ? "" : tool, argsJson.isEmpty() ? "{}" : argsJson, tz, scheduleJson, overlap.equals("queue") ? "queue" : "skip", missed.equals("run_once") ? "run_once" : "skip", System.currentTimeMillis());
  }

  /** Definition mirrored to the PC runner (it runs the schedule). Never includes keys. */
  public static JSONObject pcDefinition(JSONObject r) throws JSONException {
    JSONObject args = new JSONObject(r.optString("args", "{}"));
    return new JSONObject().put("name", r.optString("name")).put("workspace", args.optString("workspace")).put("tool", r.optString("tool")).put("args", args.optJSONObject("tool_args") == null ? new JSONObject() : args.getJSONObject("tool_args"))
        .put("schedule", new JSONObject(r.optString("schedule"))).put("tz", r.optString("tz")).put("overlap", r.optString("overlap")).put("missed", r.optString("missed")).put("enabled", r.optInt("enabled") == 1);
  }

  public static void setEnabled(Db db, long id, boolean on) {
    JSONObject r = db.one("SELECT * FROM routines WHERE id=?", id);
    if (r == null) throw new IllegalArgumentException("Routine no longer exists.");
    Long next = on && r.optString("target").equals("PHONE") ? nextAfter(r.optString("schedule"), r.optString("tz"), System.currentTimeMillis()) : null;
    db.update("UPDATE routines SET enabled=?,next_run=? WHERE id=?", on ? 1 : 0, next, id);
  }

  public interface PhoneRunner {
    /** Runs the instruction through the Gateway and stores the reply. Returns the stored message id. Throws on any failure. */
    long run(JSONObject routine) throws Exception;
  }

  public static final class Tick {
    public int started, succeeded, failed, missed, overlap;
  }

  /** Runs due PHONE routines. Safe to call repeatedly: a scheduled instant is handled at most once. */
  public static Tick tick(Db db, long now, PhoneRunner runner) {
    Tick t = new Tick();
    for (JSONObject r : db.rows("SELECT * FROM routines WHERE enabled=1 AND target='PHONE' AND next_run IS NOT NULL AND next_run<=? ORDER BY next_run", now)) {
      long id = r.optLong("id"), scheduled = r.optLong("next_run");
      long next = nextAfter(r.optString("schedule"), r.optString("tz"), now);
      db.update("UPDATE routines SET next_run=? WHERE id=?", next, id);
      if (now - scheduled > GRACE_MS && r.optString("missed").equals("skip")) {
        db.update("INSERT OR IGNORE INTO routine_runs(routine,scheduled,started,finished,status,error) VALUES(?,?,?,?,'missed',?)", id, scheduled, now, now, "The phone was not awake at the scheduled time; skipped by policy.");
        t.missed++;
        continue;
      }
      if (db.count("SELECT COUNT(*) n FROM routine_runs WHERE routine=? AND status='started'", id) > 0 && r.optString("overlap").equals("skip")) {
        db.update("INSERT OR IGNORE INTO routine_runs(routine,scheduled,started,finished,status,error) VALUES(?,?,?,?,'skipped_overlap',?)", id, scheduled, now, now, "The previous run had not finished.");
        t.overlap++;
        continue;
      }
      if (db.update("INSERT OR IGNORE INTO routine_runs(routine,scheduled,started,status) VALUES(?,?,?,'started')", id, scheduled, now) == 0) continue; // replay of an already handled instant
      t.started++;
      try {
        runner.run(r);
        db.update("UPDATE routine_runs SET status='succeeded',finished=? WHERE routine=? AND scheduled=? AND status='started'", System.currentTimeMillis(), id, scheduled);
        t.succeeded++;
      } catch (Exception e) {
        db.update("UPDATE routine_runs SET status='failed',finished=?,error=? WHERE routine=? AND scheduled=? AND status='started'", System.currentTimeMillis(), Redact.snippet(String.valueOf(e.getMessage()), 300), id, scheduled);
        t.failed++;
      }
    }
    return t;
  }

  /** After a process death a 'started' run has an unknown result; it is reported as such, never as success. */
  public static void recover(Db db) {
    db.update("UPDATE routine_runs SET status='unknown',finished=?,error='The app stopped while this run was in progress. Whether it completed is unknown.' WHERE status='started'", System.currentTimeMillis());
  }

  private Routines() {}
}
