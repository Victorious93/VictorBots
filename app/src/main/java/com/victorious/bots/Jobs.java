package com.victorious.bots;

import java.util.*;
import org.json.*;

/**
 * Persistent job records. Local code can create drafts and record that a request was SENT; only a
 * backend report ({@link #applyRemote}) can move a job to queued, running, awaiting approval,
 * completed, failed or cancelled. A generated text claim, a copy/open action or queue admission
 * never produces "completed". Duplicate and late events are ignored by sequence number.
 */
public final class Jobs {
  public enum State {
    DRAFT("Draft", "Nothing has been sent anywhere."),
    PROPOSED("Proposed by a bot", "A bot suggested this. Nothing was sent; only you can approve it."),
    SUBMITTING("Sent, no answer yet", "The request was sent but the backend has not confirmed it."),
    QUEUED("Queued", "Reported by the backend."),
    RUNNING("Running", "Reported by the backend."),
    AWAITING_APPROVAL("Awaiting approval", "The backend is waiting for an approval."),
    COMPLETED("Completed", "Reported by the backend."),
    FAILED("Failed", "Reported by the backend."),
    CANCELLED("Cancelled", "Reported by the backend."),
    UNKNOWN("Outcome unknown", "The request may or may not have run. It was not repeated.");

    public final String label, meaning;

    State(String l, String m) {
      label = l;
      meaning = m;
    }

    public boolean terminal() {
      return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    public boolean backendReported() {
      return this == QUEUED || this == RUNNING || this == AWAITING_APPROVAL || this == COMPLETED || this == FAILED || this == CANCELLED;
    }
  }

  public static State parseBackend(String s) {
    if (s == null) return null;
    switch (s.toLowerCase(Locale.ROOT)) {
      case "queued": return State.QUEUED;
      case "running": return State.RUNNING;
      case "awaiting_approval": return State.AWAITING_APPROVAL;
      case "completed": return State.COMPLETED;
      case "failed": return State.FAILED;
      case "cancelled": return State.CANCELLED;
      default: return null;
    }
  }

  public static final class Job {
    public long id, bot, chat, profile, lastSeq, created, updated, started, finished, dependsOn;
    public String uid, requestId, title, backend, tool, args, remoteId, error, result, stateSource;
    public State state;

    public String statusLine() {
      return state.label + " — " + (state.backendReported() ? state.meaning.replace("backend", backend.equals("DCA") ? "DroidCommand" : "PC runner") : state.meaning);
    }
  }

  public static final int MAX_EVENTS = 2000, MAX_EVENT_CHARS = 4000;

  static Job from(JSONObject r) {
    Job j = new Job();
    j.id = r.optLong("id");
    j.uid = r.optString("uid");
    j.requestId = r.optString("request_id");
    j.title = r.optString("title");
    j.bot = r.optLong("bot");
    j.chat = r.optLong("chat");
    j.backend = r.optString("backend");
    j.profile = r.optLong("profile");
    j.tool = r.optString("tool");
    j.args = r.optString("args", "{}");
    j.state = State.valueOf(r.optString("state"));
    j.stateSource = r.optString("state_source");
    j.remoteId = r.optString("remote_id");
    j.lastSeq = r.optLong("last_seq");
    j.dependsOn = r.optLong("depends_on");
    j.created = r.optLong("created");
    j.updated = r.optLong("updated");
    j.started = r.optLong("started");
    j.finished = r.optLong("finished");
    j.error = r.optString("error");
    j.result = r.optString("result", "{}");
    return j;
  }

  private final Db db;

  public Jobs(Db db) {
    this.db = db;
  }

  public Job get(long id) {
    JSONObject r = db.one("SELECT * FROM jobs WHERE id=?", id);
    return r == null ? null : from(r);
  }

  public Job byRequest(String requestId) {
    JSONObject r = db.one("SELECT * FROM jobs WHERE request_id=?", requestId);
    return r == null ? null : from(r);
  }

  public List<Job> list(int limit) {
    List<Job> out = new ArrayList<>();
    for (JSONObject r : db.rows("SELECT * FROM jobs ORDER BY id DESC LIMIT " + Math.max(1, Math.min(limit, 200)))) out.add(from(r));
    return out;
  }

  /** A job that is not yet doing anything. A bot may only PROPOSE; the owner turns it into a draft. */
  public long create(String title, long bot, long chat, long srcMsg, String backend, long profile, String tool, JSONObject args, long proposedBy, long dependsOn) {
    Rules.length(title, 120, "Job title");
    if (!backend.equals("PC") && !backend.equals("DCA") && !backend.equals("MANUAL")) throw new IllegalArgumentException("Unknown backend.");
    if (args.toString().length() > 16000) throw new IllegalArgumentException("Job arguments are too large.");
    long now = System.currentTimeMillis();
    return db.insert(
        "INSERT INTO jobs(uid,request_id,title,bot,chat,source_msg,backend,profile,tool,args,state,state_source,depends_on,proposed_by,created,updated) VALUES(?,?,?,?,?,?,?,?,?,?,?,'local',?,?,?,?)",
        Ids.uid(), Ids.uuid(), title.trim(), bot == 0 ? null : bot, chat == 0 ? null : chat, srcMsg == 0 ? null : srcMsg, backend, profile == 0 ? null : profile, tool, args.toString(),
        proposedBy != 0 ? State.PROPOSED.name() : State.DRAFT.name(), dependsOn == 0 ? null : dependsOn, proposedBy == 0 ? null : proposedBy, now, now);
  }

  /** Owner explicitly accepts a bot's proposal. This is the only PROPOSED → DRAFT path. */
  public void ownerApprovesProposal(long id) {
    if (db.update("UPDATE jobs SET state='DRAFT',updated=? WHERE id=? AND state='PROPOSED'", System.currentTimeMillis(), id) == 0) throw new IllegalStateException("Only a proposed job can be approved.");
  }

  /** Owner tapped Run: record that a request is being sent. Fails if a dependency has not completed. */
  public void markSubmitting(long id) {
    Job j = get(id);
    if (j == null) throw new IllegalArgumentException("Job no longer exists.");
    if (j.state != State.DRAFT) throw new IllegalStateException("Only a draft can be sent (it is " + j.state.label + "). A job is never sent twice.");
    if (j.dependsOn != 0) {
      Job dep = get(j.dependsOn);
      if (dep == null || dep.state != State.COMPLETED || !dep.stateSource.equals("backend"))
        throw new IllegalStateException("This job depends on “" + (dep == null ? "a deleted job" : dep.title) + "”, which has not been reported completed by its backend.");
    }
    db.update("UPDATE jobs SET state='SUBMITTING',updated=? WHERE id=? AND state='DRAFT'", System.currentTimeMillis(), id);
  }

  /** The send failed or timed out: the backend may or may not have the job. Never retried automatically. */
  public void markUnknown(long id, String why) {
    db.update("UPDATE jobs SET state='UNKNOWN',error=?,updated=? WHERE id=? AND state IN ('SUBMITTING','QUEUED','RUNNING','AWAITING_APPROVAL','UNKNOWN')", Redact.snippet(why, 300), System.currentTimeMillis(), id);
  }

  /** The send definitely did not reach the backend (e.g. refused before any connection): back to draft. */
  public void markNotSent(long id, String why) {
    db.update("UPDATE jobs SET state='DRAFT',error=?,updated=? WHERE id=? AND state='SUBMITTING'", Redact.snippet(why, 300), System.currentTimeMillis(), id);
  }

  public static final class Remote {
    public String id = "", state = "", error = "", resultJson = "{}";
    public long seq, started, finished;
    public Integer exitCode;
    public List<long[]> eventMeta = new ArrayList<>();
    public List<String[]> events = new ArrayList<>(); // {seq, ts, stream, text}
    public List<JSONObject> artifacts = new ArrayList<>();
  }

  private static boolean allowed(State from, State to) {
    if (from == to) return true;
    if (from.terminal()) return false;
    switch (from) {
      case SUBMITTING:
      case UNKNOWN:
        return to.backendReported();
      case QUEUED:
        return to == State.RUNNING || to == State.AWAITING_APPROVAL || to.terminal();
      case AWAITING_APPROVAL:
        return to == State.RUNNING || to == State.QUEUED || to.terminal();
      case RUNNING:
        return to == State.AWAITING_APPROVAL || to.terminal();
      default:
        return false;
    }
  }

  /**
   * Applies a report from the backend. Idempotent: events at or below the stored sequence are
   * ignored, a state that would go backwards is ignored, and terminal states never change.
   * @return true if anything changed
   */
  public boolean applyRemote(long id, Remote r) {
    return db.tx(() -> {
      Job j = get(id);
      if (j == null) return false;
      State to = parseBackend(r.state);
      boolean changed = false;
      long maxSeq = j.lastSeq;
      for (String[] e : r.events) {
        long seq = Long.parseLong(e[0]);
        if (seq <= j.lastSeq) continue; // duplicate or replayed
        String text = Redact.text(e[3]);
        if (text.length() > MAX_EVENT_CHARS) text = text.substring(0, MAX_EVENT_CHARS) + "…[cut]";
        if (db.update("INSERT OR IGNORE INTO job_events(job,seq,ts,stream,text) VALUES(?,?,?,?,?)", id, seq, Long.parseLong(e[1]), e[2].equals("stderr") ? "stderr" : "stdout", text) > 0) changed = true;
        maxSeq = Math.max(maxSeq, seq);
      }
      long cut = maxSeq - MAX_EVENTS;
      if (cut > 0) db.update("DELETE FROM job_events WHERE job=? AND seq<=?", id, cut);
      if (maxSeq > j.lastSeq) {
        db.update("UPDATE jobs SET last_seq=? WHERE id=?", maxSeq, id);
        changed = true;
      }
      if (!r.id.isEmpty() && j.remoteId.isEmpty()) {
        db.update("UPDATE jobs SET remote_id=? WHERE id=?", r.id, id);
        changed = true;
      }
      if (to != null && !j.state.terminal() && allowed(j.state, to)) {
        if (to == State.COMPLETED && r.exitCode != null && r.exitCode != 0) to = State.FAILED; // never trust a state that contradicts the exit code
        if (to != j.state) {
          long now = System.currentTimeMillis();
          db.update("UPDATE jobs SET state=?,state_source='backend',updated=?,started=COALESCE(started,?),finished=?,error=?,result=? WHERE id=?",
              to.name(), now, r.started == 0 ? null : (Object) r.started, to.terminal() ? (Object) (r.finished == 0 ? now : r.finished) : null,
              Redact.snippet(r.error, 300), r.resultJson.length() > 20000 ? "{}" : r.resultJson, id);
          changed = true;
        }
      }
      if (j.state.terminal() || (to != null && to.terminal()))
        for (JSONObject a : r.artifacts) {
          String name = a.optString("name");
          if (!safeName(name)) continue;
          db.update("INSERT OR IGNORE INTO artifacts(job,remote_name,kind,size,sha256,mime,verified) VALUES(?,?,?,?,?,?,0)", id, name, a.optString("kind", "file"), a.optLong("size"), a.optString("sha256").toLowerCase(Locale.ROOT), a.optString("mime"));
        }
      return changed;
    });
  }

  /** Artifact names are plain file names: no separators, no traversal, bounded length. */
  public static boolean safeName(String n) {
    return n != null && !n.isEmpty() && n.length() <= 120 && !n.contains("/") && !n.contains("\\") && !n.contains("..") && !n.startsWith(".") && n.matches("[\\p{L}\\p{N}._ +()-]+");
  }

  public List<String[]> events(long id, int limit) {
    List<String[]> out = new ArrayList<>();
    for (JSONObject r : db.rows("SELECT seq,stream,text FROM job_events WHERE job=? ORDER BY seq DESC LIMIT " + Math.max(1, Math.min(limit, MAX_EVENTS)), id))
      out.add(0, new String[] {r.optString("seq"), r.optString("stream"), r.optString("text")});
    return out;
  }

  /** Owner cancelled locally and the backend confirmed (or the job was never sent). */
  public void cancelLocalDraft(long id) {
    db.update("UPDATE jobs SET state='CANCELLED',state_source='local',finished=?,updated=? WHERE id=? AND state IN ('DRAFT','PROPOSED')", System.currentTimeMillis(), System.currentTimeMillis(), id);
  }

  /** Jobs the monitor must keep following. */
  public List<Job> active() {
    List<Job> out = new ArrayList<>();
    for (JSONObject r : db.rows("SELECT * FROM jobs WHERE state IN ('SUBMITTING','QUEUED','RUNNING','AWAITING_APPROVAL','UNKNOWN') AND backend='PC' ORDER BY id")) out.add(from(r));
    return out;
  }

  /**
   * Process restart: a job we were sending when the process died may or may not have reached the
   * backend. It becomes UNKNOWN (reconcilable by request id), never resent.
   */
  public static void recover(Db db) {
    db.update("UPDATE jobs SET state='UNKNOWN',error='The app stopped while this request was being sent. It was not repeated; check its status.',updated=? WHERE state='SUBMITTING'", System.currentTimeMillis());
  }
}
