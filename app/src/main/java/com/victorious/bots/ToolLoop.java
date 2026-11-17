package com.victorious.bots;

import java.util.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Lets a bot's model call read-only connector tools during one owner-initiated chat request.
 *
 * <p>Boundaries (each has a test in ToolLoopTest):
 * <ul>
 *   <li>Every model request still goes through {@link Gateway}; this class never talks to a provider.
 *   <li>Only tools that are on the runner's read-only allow-list <em>and</em> were enabled for this bot
 *       with "bot may call" switched on are ever described to the model or executed. A name the model
 *       invents, or a tool outside the list, is answered with an error and never reaches the runner.
 *   <li>Tool output is untrusted data ({@link Untrusted}); it cannot change policy, start jobs or
 *       complete anything. Bounded: rounds, calls, argument and result size, no repeated identical call.
 *   <li>The model only ever <em>requests</em>; the runner executes, redacts and bounds. No write or exec
 *       tool is reachable here: those stay Jobs, which the owner approves.
 *   <li>Owner-initiated chat only: Gateway ignores tools for every other purpose.
 * </ul>
 */
public final class ToolLoop {
  public static final int MAX_ROUNDS = 6, MAX_CALLS = 8, MAX_ARG_CHARS = 8000, MAX_RESULT_CHARS = 16000, MAX_TOTAL_CHARS = 60000;

  /** Runner access, implemented by RunnerClient on Android and by a fake in tests. */
  public interface Host {
    /** Runner's {@code GET /connectors/{id}/tools}: {"tools":[{name,description,input_schema}]}. */
    JSONObject tools(String connector, String workspace) throws Exception;

    /** Runner's {@code POST /connectors/{id}/call}: {"is_error":bool,"text":string}. */
    JSONObject call(String connector, String tool, JSONObject args, String workspace) throws Exception;
  }

  /** One enabled connector for one bot. */
  public static final class Binding {
    public final String connector, workspace;
    public final Set<String> allowed;

    public Binding(String connector, String workspace, Set<String> allowed) {
      this.connector = connector;
      this.workspace = workspace;
      this.allowed = allowed;
    }
  }

  /** What happened, for the owner to see under the reply. Never contains tool output. */
  public static final class Call {
    public String name = "", note = "";
    public boolean ok;
    public long ms;
    public int chars;
  }

  public static final class Outcome {
    public Gateway.Result last;
    public final List<Call> trace = new ArrayList<>();
    public String notice = "";
    public int rounds;
    public Long inTokens = 0L, outTokens = 0L;
    public long latencyMs;

    public JSONArray traceJson() {
      JSONArray a = new JSONArray();
      for (Call c : trace)
        try {
          a.put(new JSONObject().put("tool", c.name).put("ok", c.ok).put("ms", c.ms).put("chars", c.chars).put("note", c.note));
        } catch (JSONException e) {
          throw new IllegalStateException(e);
        }
      return a;
    }
  }

  private static final class Target {
    final Binding b;
    final String tool;

    Target(Binding b, String tool) {
      this.b = b;
      this.tool = tool;
    }
  }

  /** Connectors the owner enabled for this bot with model use switched on and a workspace chosen. */
  public static List<Binding> bindings(Db db, long botId) {
    List<Binding> out = new ArrayList<>();
    if (botId == 0) return out;
    for (JSONObject r : db.rows("SELECT connector,workspace,tools FROM bot_connectors WHERE bot=? AND auto=1 AND workspace<>''", botId)) {
      Set<String> allowed = new LinkedHashSet<>();
      try {
        JSONArray t = new JSONArray(r.optString("tools"));
        for (int i = 0; i < t.length(); i++) allowed.add(t.optString(i));
      } catch (JSONException e) {
        continue;
      }
      if (!allowed.isEmpty()) out.add(new Binding(r.optString("connector"), r.optString("workspace"), allowed));
    }
    return out;
  }

  private final Gateway gateway;
  private final Host host;

  public ToolLoop(Gateway gateway, Host host) {
    this.gateway = gateway;
    this.host = host;
  }

  /** Fetches the schemas the model may see: runner list ∩ this bot's enabled tools. */
  Map<String, Target> offer(List<Binding> bindings, JSONArray defs, Outcome out) {
    Map<String, Target> map = new LinkedHashMap<>();
    for (Binding b : bindings) {
      JSONObject listed;
      try {
        listed = host.tools(b.connector, b.workspace);
      } catch (Exception e) {
        out.notice += "Tools of “" + b.connector + "” were not available (" + Redact.snippet(String.valueOf(e.getMessage()), 100) + "). ";
        continue;
      }
      JSONArray arr = listed.optJSONArray("tools");
      if (arr == null) continue;
      for (int i = 0; i < arr.length(); i++) {
        JSONObject t = arr.optJSONObject(i);
        if (t == null) continue;
        String tool = t.optString("name");
        String name = b.connector + "__" + tool;
        if (!b.allowed.contains(tool) || !name.matches("[A-Za-z0-9_-]{1,64}") || map.containsKey(name) || map.size() >= 32) continue;
        JSONObject schema = t.optJSONObject("input_schema");
        if (schema == null || !"object".equals(schema.optString("type"))) schema = new JSONObject();
        try {
          if (!schema.has("type")) schema.put("type", "object").put("properties", new JSONObject());
          defs.put(
              new JSONObject()
                  .put("type", "function")
                  .put("function", new JSONObject().put("name", name).put("description", "Read-only. " + clip(t.optString("description"), 600)).put("parameters", schema)));
        } catch (JSONException e) {
          continue;
        }
        map.put(name, new Target(b, tool));
      }
    }
    return map;
  }

  public Outcome run(Gateway.Req base, List<Binding> bindings) throws ProviderError {
    Outcome out = new Outcome();
    JSONArray defs = new JSONArray();
    Map<String, Target> offered = bindings.isEmpty() ? new HashMap<String, Target>() : offer(bindings, defs, out);
    if (offered.isEmpty()) {
      base.tools = null;
      return finish(out, gateway.run(base));
    }
    List<Rules.Turn> turns = new ArrayList<>(base.turns);
    Set<String> seen = new HashSet<>();
    int calls = 0, total = 0;
    for (int round = 1; ; round++) {
      boolean last = round >= MAX_ROUNDS || calls >= MAX_CALLS || total >= MAX_TOTAL_CHARS;
      Gateway.Req q = copy(base, turns);
      q.tools = last ? null : defs;
      Gateway.Result res = gateway.run(q);
      add(out, res);
      List<JSONObject> wanted = res.reply.toolCalls;
      if (wanted.isEmpty() || last) {
        if (!wanted.isEmpty()) out.notice += "The model asked for more tools after the limit; the request was not run. ";
        return finish(out, res);
      }
      JSONArray echo = new JSONArray();
      for (JSONObject c : wanted) echo.put(c);
      Rules.Turn asked = new Rules.Turn("assistant", res.reply.text);
      asked.toolCalls = echo;
      turns.add(asked);
      for (int i = 0; i < wanted.size(); i++) {
        if (base.cancel != null && base.cancel.isCancelled()) throw new ProviderError(ProviderError.Category.CANCELLED, 0, "Stopped.", true);
        JSONObject c = wanted.get(i);
        String id = c.optString("id"), name = "", argText = "{}";
        JSONObject fn = c.optJSONObject("function");
        if (fn != null) {
          name = fn.optString("name");
          argText = fn.optString("arguments", "{}");
          if (argText.trim().isEmpty()) argText = "{}";
        }
        Call tc = new Call();
        tc.name = name;
        String content;
        Target t = offered.get(name);
        JSONObject args = null;
        try {
          args = new JSONObject(argText);
        } catch (JSONException ignored) {
        }
        String key = name + "|" + (args == null ? argText : canonical(args));
        if (id.isEmpty()) {
          content = "Error: the tool call had no id.";
          tc.note = "no id";
        } else if (t == null) {
          content = "Error: no such tool is available. Available: " + offered.keySet();
          tc.note = "not offered";
        } else if (args == null || argText.length() > MAX_ARG_CHARS) {
          content = "Error: arguments must be one JSON object of at most " + MAX_ARG_CHARS + " characters.";
          tc.note = "bad arguments";
        } else if (calls >= MAX_CALLS) {
          content = "Not run: the per-request tool-call limit (" + MAX_CALLS + ") was reached. Answer with what you have.";
          tc.note = "limit";
        } else if (!seen.add(key)) {
          content = "Not run again: identical call already made in this request; use its earlier result.";
          tc.note = "duplicate";
        } else {
          calls++;
          long t0 = System.currentTimeMillis();
          try {
            JSONObject r = host.call(t.b.connector, t.tool, args, t.b.workspace);
            String text = r.optString("text");
            if (text.length() > MAX_RESULT_CHARS) text = text.substring(0, MAX_RESULT_CHARS) + "\n[output cut at " + MAX_RESULT_CHARS + " characters]";
            tc.ok = !r.optBoolean("is_error");
            tc.chars = text.length();
            total += text.length();
            content = Untrusted.wrap(t.b.connector + ":" + t.tool, (tc.ok ? "" : "[tool reported an error]\n") + text);
            if (!tc.ok) tc.note = "tool error";
          } catch (Exception e) {
            String why = Redact.snippet(String.valueOf(e.getMessage()), 160);
            content = "Error: the tool could not be run (" + why + ").";
            tc.note = "failed: " + why;
          }
          tc.ms = System.currentTimeMillis() - t0;
        }
        Rules.Turn answer = new Rules.Turn("tool", content);
        answer.toolCallId = id;
        turns.add(answer);
        out.trace.add(tc);
      }
    }
  }

  private static Gateway.Req copy(Gateway.Req b, List<Rules.Turn> turns) {
    Gateway.Req q = new Gateway.Req();
    q.purpose = b.purpose;
    q.botId = b.botId;
    q.profileOverride = b.profileOverride;
    q.turns = new ArrayList<>(turns);
    q.stream = b.stream;
    q.sink = b.sink;
    q.cancel = b.cancel;
    q.ownerConfirmed = b.ownerConfirmed;
    q.modelOverride = b.modelOverride;
    return q;
  }

  private static void add(Outcome o, Gateway.Result r) {
    o.rounds++;
    o.latencyMs += r.reply.latencyMs;
    o.inTokens = r.reply.usage.in == null || o.inTokens == null ? null : o.inTokens + r.reply.usage.in;
    o.outTokens = r.reply.usage.out == null || o.outTokens == null ? null : o.outTokens + r.reply.usage.out;
  }

  private static Outcome finish(Outcome o, Gateway.Result r) {
    if (o.rounds == 0) add(o, r);
    o.last = r;
    return o;
  }

  private static String clip(String s, int n) {
    return s.length() <= n ? s : s.substring(0, n);
  }

  /** Stable text for duplicate detection (key order does not matter). */
  static String canonical(JSONObject o) {
    TreeMap<String, String> m = new TreeMap<>();
    Iterator<String> it = o.keys();
    while (it.hasNext()) {
      String k = it.next();
      m.put(k, String.valueOf(o.opt(k)));
    }
    return m.toString();
  }
}
