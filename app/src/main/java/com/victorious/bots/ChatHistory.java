package com.victorious.bots;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.json.JSONObject;

/**
 * Pure logic for "regenerate the last reply" and "edit and resend the last message". A replaced message is kept
 * in the database with status {@code replaced}: it drops out of the visible chat, of every prompt, summary and
 * memory query (they all read status 'done') and of backup exports (done and error only), but nothing is deleted.
 *
 * <p>Only the tail of a conversation can change: the last user message together with the replies after it. A
 * pending request blocks every change. Nothing here sends anything; callers still go through the Gateway.
 */
public final class ChatHistory {
  public static final String REPLACED = "replaced";

  /** The few fields of a stored message this logic needs. */
  public static final class Msg {
    public final long id;
    public final String role, status, kind, body;

    public Msg(long id, String role, String status, String kind, String body) {
      this.id = id;
      this.role = role;
      this.status = status;
      this.kind = kind == null || kind.isEmpty() ? "text" : kind;
      this.body = body == null ? "" : body;
    }

    public static Msg from(JSONObject r) {
      return new Msg(r.optLong("id"), r.optString("role"), r.optString("status"), r.optString("kind", "text"), r.optString("body"));
    }

    boolean isUserDone() {
      return role.equals("user") && status.equals("done");
    }

    /** A bot answer or a failure notice: the kind of message that follows a user message. */
    boolean isReply() {
      return !kind.equals("notice") && ((role.equals("assistant") && status.equals("done")) || status.equals("error"));
    }
  }

  /** What would be replaced and what text would be sent in its place. */
  public static final class Replacement {
    public final long userId;
    public final String text;
    /** Ids to mark replaced: the user message and everything after it, oldest first. */
    public final List<Long> ids;

    Replacement(long userId, String text, List<Long> ids) {
      this.userId = userId;
      this.text = text;
      this.ids = ids;
    }
  }

  public static List<Msg> from(List<JSONObject> rows) {
    List<Msg> out = new ArrayList<>();
    for (JSONObject r : rows) out.add(Msg.from(r));
    return out;
  }

  /** Messages still part of the conversation, in order. */
  public static List<Msg> visible(List<Msg> all) {
    List<Msg> out = new ArrayList<>();
    for (Msg m : all) if (!m.status.equals(REPLACED)) out.add(m);
    return out;
  }

  private static boolean anyPending(List<Msg> vis) {
    for (Msg m : vis) if (m.status.equals("pending")) return true;
    return false;
  }

  /**
   * Index in {@code vis} of the last user message that is followed only by replies (or by nothing), or -1.
   * The conversation must have no pending request.
   */
  private static int tailUser(List<Msg> vis) {
    if (vis.isEmpty() || anyPending(vis)) return -1;
    int i = vis.size() - 1;
    while (i >= 0 && vis.get(i).isReply()) i--;
    if (i < 0 || !vis.get(i).isUserDone()) return -1;
    return i;
  }

  private static List<Long> idsFrom(List<Msg> vis, int from) {
    List<Long> ids = new ArrayList<>();
    for (int k = from; k < vis.size(); k++) ids.add(vis.get(k).id);
    return ids;
  }

  /** Regenerate: the last message must be a bot reply or a failure; it is re-asked from the same user message. */
  public static Replacement forRetry(List<Msg> all) {
    List<Msg> vis = visible(all);
    int u = tailUser(vis);
    if (u < 0 || u == vis.size() - 1) return null;
    Msg user = vis.get(u);
    return new Replacement(user.id, user.body, idsFrom(vis, u));
  }

  /** Edit and resend: {@code userId} must be the last user message and nothing may be pending. */
  public static Replacement forEdit(List<Msg> all, long userId, String newText) {
    if (newText == null || newText.trim().isEmpty()) return null;
    List<Msg> vis = visible(all);
    int u = tailUser(vis);
    if (u < 0 || vis.get(u).id != userId) return null;
    return new Replacement(userId, newText.trim(), idsFrom(vis, u));
  }

  public static boolean canRetry(List<Msg> all) {
    return forRetry(all) != null;
  }

  /** Id of the user message that can currently be edited, or 0. */
  public static long editableUserId(List<Msg> all) {
    List<Msg> vis = visible(all);
    int u = tailUser(vis);
    return u < 0 ? 0 : vis.get(u).id;
  }

  /** Id of the last visible message when it is a bot reply that may be regenerated, else 0. */
  public static long retryableReplyId(List<Msg> all) {
    if (!canRetry(all)) return 0;
    List<Msg> vis = visible(all);
    return vis.get(vis.size() - 1).id;
  }

  /**
   * Marks the replaced messages in one transaction. Throws (and changes nothing) if any of them is not
   * a current 'done' or 'error' message of {@code chat}, so a stale screen can never replace the wrong thing.
   */
  public static void apply(final Db db, final long chat, final Replacement r) {
    db.tx(() -> {
      for (long id : r.ids) {
        int n = db.update("UPDATE messages SET status=? WHERE id=? AND chat=? AND status IN ('done','error')", REPLACED, id, chat);
        if (n != 1) throw new IllegalStateException("The conversation changed; nothing was replaced.");
      }
      return null;
    });
  }

  /** SQL fragment excluding ids from a history query; ids are numeric so it is injection-safe. */
  public static String notIn(Collection<Long> ids) {
    if (ids == null || ids.isEmpty()) return "";
    StringBuilder b = new StringBuilder(" AND id NOT IN (");
    boolean first = true;
    for (long id : ids) {
      if (!first) b.append(',');
      b.append(id);
      first = false;
    }
    return b.append(')').toString();
  }

  private ChatHistory() {}
}
