package com.victorious.bots;

import java.util.*;

/**
 * Follows backend jobs. Reconnects safely: it asks for events after the last stored sequence, so
 * replays and duplicates are harmless, and it never resubmits anything. If the backend cannot be
 * reached it leaves the job as-is (it does not guess a result).
 */
public final class JobMonitor {
  public interface Clients {
    RunnerClient forProfile(long profileId) throws Exception;
  }

  public static final class Report {
    public int polled, changed, unreachable;
    public List<String> notes = new ArrayList<>();
  }

  private final Db db;
  private final Jobs jobs;
  private final Clients clients;

  public JobMonitor(Db db, Jobs jobs, Clients clients) {
    this.db = db;
    this.jobs = jobs;
    this.clients = clients;
  }

  public Report pollOnce() {
    Report rep = new Report();
    for (Jobs.Job j : jobs.active()) {
      rep.polled++;
      try {
        RunnerClient c = clients.forProfile(j.profile);
        Jobs.Remote r;
        if (j.remoteId.isEmpty()) {
          // We never learned the runner's id (send timed out, or the app died mid-send): look it up by our request id.
          r = c.byRequest(j.requestId);
          if (r == null) {
            if (j.state == Jobs.State.UNKNOWN || j.state == Jobs.State.SUBMITTING) rep.notes.add("Runner has no record of request " + j.requestId.substring(0, 8) + "…");
            continue;
          }
          if (jobs.applyRemote(j.id, r)) rep.changed++;
          j = jobs.get(j.id);
        }
        if (j.state.terminal() || j.remoteId.isEmpty()) continue;
        r = c.events(j.remoteId, j.lastSeq);
        if (jobs.applyRemote(j.id, r)) rep.changed++;
      } catch (ProviderError e) {
        rep.unreachable++;
        rep.notes.add("Job " + j.id + ": " + e.category.label);
      } catch (Exception e) {
        rep.unreachable++;
        rep.notes.add("Job " + j.id + ": " + Redact.snippet(String.valueOf(e.getMessage()), 80));
      }
    }
    return rep;
  }

  /** The owner chose to start the job. Sends exactly once; any doubt becomes UNKNOWN, not a retry. */
  public void submit(long jobId, String workspace) {
    Jobs.Job j = jobs.get(jobId);
    jobs.markSubmitting(jobId);
    boolean[] sent = {false};
    try {
      RunnerClient c = clients.forProfile(j.profile);
      Jobs.Remote r = c.submit(j.requestId, workspace, j.tool, new org.json.JSONObject(j.args), j.title, sent);
      jobs.applyRemote(jobId, r);
    } catch (ProviderError e) {
      if (!sent[0] && !e.mayHaveBeenProcessed) jobs.markNotSent(jobId, e.category.label + ": " + e.getMessage());
      else jobs.markUnknown(jobId, e.category.label + ": " + e.getMessage());
    } catch (Exception e) {
      jobs.markNotSent(jobId, Redact.snippet(String.valueOf(e.getMessage()), 200));
    }
  }
}
