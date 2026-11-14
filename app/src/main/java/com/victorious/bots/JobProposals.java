package com.victorious.bots;

import java.util.*;
import java.util.regex.*;
import org.json.*;

/**
 * Bots may SUGGEST a job by including a fenced block:
 * <pre>```victorbots-job {"backend":"PC","workspace":"main","tool":"cmd.unit-tests","title":"Run tests"}```</pre>
 * Parsing only produces a proposal for the owner to review. It creates nothing by itself and a
 * proposal grants nothing: the owner must approve it and then press Run, and the backend still
 * applies its own approvals.
 */
public final class JobProposals {
  public static final class Proposal {
    public String backend, workspace, tool, title;
    public JSONObject args = new JSONObject();
  }

  private static final Pattern BLOCK = Pattern.compile("```victorbots-job\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL);

  public static List<Proposal> parse(String reply) {
    List<Proposal> out = new ArrayList<>();
    Matcher m = BLOCK.matcher(reply);
    while (m.find() && out.size() < 3) {
      try {
        JSONObject o = new JSONObject(m.group(1));
        Proposal p = new Proposal();
        p.backend = o.optString("backend");
        p.tool = o.optString("tool");
        p.workspace = o.optString("workspace");
        p.title = o.optString("title", p.tool);
        JSONObject a = o.optJSONObject("args");
        if (a != null) p.args = a;
        if ((p.backend.equals("PC") || p.backend.equals("DCA")) && p.tool.matches("[A-Za-z0-9_.-]{1,60}") && p.title.length() <= 120 && p.workspace.length() <= 32) out.add(p);
      } catch (JSONException ignored) {
      }
    }
    return out;
  }

  private JobProposals() {}
}
