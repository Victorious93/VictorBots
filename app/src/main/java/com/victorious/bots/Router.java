package com.victorious.bots;

import java.util.*;
import org.json.*;

/** Chooses which bots answer in Chief of Staff mode. Model routing when policy allows; keywords otherwise. */
public final class Router {
  public static final class Route {
    public List<Long> speakers = new ArrayList<>();
    public String how = "", why = "";
  }

  private static final Set<String> STOP = new HashSet<>(Arrays.asList("the", "and", "for", "with", "that", "this", "you", "your", "are", "can", "how", "what", "please", "from", "have", "into", "about", "would", "could", "should"));

  private static Set<String> words(String s) {
    Set<String> out = new HashSet<>();
    for (String w : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (w.length() >= 3 && !STOP.contains(w)) out.add(w);
    return out;
  }

  /** Deterministic fallback: overlap between the message and each bot's name/description/role hint. */
  public static Route heuristic(Db db, List<Mentions.Member> members, String message, long chief) {
    Set<String> q = words(message);
    long best = -1;
    int bestScore = 0;
    List<long[]> scored = new ArrayList<>();
    for (Mentions.Member m : members) {
      if (m.muted || m.id == chief) continue;
      JSONObject b = db.one("SELECT name,description,role_hint FROM bots WHERE id=?", m.id);
      Set<String> bw = words(b.optString("name") + " " + b.optString("description") + " " + b.optString("role_hint"));
      int hits = 0;
      for (String w : q) if (bw.contains(w)) hits++;
      scored.add(new long[] {m.id, hits});
    }
    scored.sort((x, y) -> Long.compare(y[1], x[1]));
    Route r = new Route();
    r.how = "keyword match (no model call)";
    if (!scored.isEmpty() && scored.get(0)[1] > 0) {
      r.speakers.add(scored.get(0)[0]);
      if (scored.size() > 1 && scored.get(1)[1] > 0 && scored.get(1)[1] >= scored.get(0)[1]) r.speakers.add(scored.get(1)[0]);
      r.why = "best keyword overlap";
    } else {
      Mentions.Member c = null;
      for (Mentions.Member m : members) if (m.id == chief) c = m;
      if (c == null) for (Mentions.Member m : members) if (!m.muted) { c = m; break; }
      if (c != null) r.speakers.add(c.id);
      r.why = "no clear specialist; the Chief of Staff answers";
    }
    return r;
  }

  public static Route parse(String reply, List<Mentions.Member> members) {
    Route r = new Route();
    int a = reply.indexOf('{'), z = reply.lastIndexOf('}');
    if (a < 0 || z <= a) return r;
    try {
      JSONObject o = new JSONObject(reply.substring(a, z + 1));
      JSONArray ids = o.optJSONArray("speakers");
      if (ids == null) return r;
      for (int i = 0; i < ids.length() && r.speakers.size() < 3; i++) {
        long id = ids.optLong(i, -1);
        for (Mentions.Member m : members) if (m.id == id && !m.muted && !r.speakers.contains(id)) r.speakers.add(id);
      }
      r.why = o.optString("why", "");
    } catch (JSONException ignored) {
    }
    return r;
  }

  public static Route route(Db db, Gateway gateway, long chiefBot, List<Mentions.Member> members, String message) {
    StringBuilder roster = new StringBuilder();
    for (Mentions.Member m : members) {
      if (m.muted) continue;
      JSONObject b = db.one("SELECT description FROM bots WHERE id=?", m.id);
      roster.append(m.id).append(": ").append(m.name).append(" — ").append(b == null ? "" : b.optString("description")).append('\n');
    }
    Gateway.Req q = new Gateway.Req();
    q.purpose = Policy.Purpose.ROUTING;
    q.botId = chiefBot;
    q.turns = Arrays.asList(
        new Rules.Turn("system", "You route an owner's message to team members. Reply ONLY with JSON: {\"speakers\":[ids],\"why\":\"short\"}. Use ONE speaker for a simple request, at most three only if clearly different skills are needed. Never choose everyone. Members:\n" + roster),
        new Rules.Turn("user", message));
    try {
      Route r = parse(gateway.run(q).reply.text, members);
      if (!r.speakers.isEmpty()) {
        r.how = "routing model call";
        return r;
      }
    } catch (Gateway.Denied | ProviderError e) {
      Route h = heuristic(db, members, message, chiefBot);
      h.how = "keyword match (routing call not used: " + Redact.snippet(e.getMessage(), 80) + ")";
      return h;
    }
    Route h = heuristic(db, members, message, chiefBot);
    h.how = "keyword match (routing reply was unusable)";
    return h;
  }

  private Router() {}
}
