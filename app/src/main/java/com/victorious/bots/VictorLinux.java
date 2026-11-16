package com.victorious.bots;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Optional "VictorLinux PC" preset: URL building, save validation and capability discovery. Pure
 * Java. Nothing here is used unless the owner adds a VictorLinux profile; every other profile type
 * behaves exactly as before. All traffic goes through {@link Provider#open}, so HTTPS-only, the
 * per-connection certificate pin, "no redirects" and the origin check are the ones every profile uses.
 */
public final class VictorLinux {
  public static final String PRESET = "victorlinux";
  public static final int DEFAULT_PORT = 8080;
  public static final String DEFAULT_MODEL = "victor-local";
  public static final String CAPABILITIES_PATH = "/victor/capabilities";
  public static final String NO_RUNNER = "Runner not configured for this profile";

  private static final int MAX_CAPABILITIES_BYTES = 64 * 1024;

  /** A validated preset, ready for {@link Profiles#saveVictorLinux}. */
  public static final class Spec {
    public String base, model, pin;
    /** Normalised runner URL, or "" for none. */
    public String runnerUrl = "";
    /** Runner certificate pin; only set when the runner is on a different origin than the PC. */
    public String runnerPin = "";
    /** True when the runner is on another origin and so needs its own token. */
    public boolean separateRunner;
  }

  /** Normalised lower-case host, IPv6 literals bracketed. */
  public static String host(String raw) {
    String h = raw == null ? "" : raw.trim();
    if (h.isEmpty()) throw new IllegalArgumentException("Enter your PC's host name or IP address.");
    String low = h.toLowerCase(Locale.ROOT);
    if (low.startsWith("http://"))
      throw new IllegalArgumentException("VictorLinux uses HTTPS only. Plain http:// is never accepted. Enter just the host name or IP address.");
    if (low.contains("://"))
      throw new IllegalArgumentException("Enter only the host name or IP address, not a URL. VictorBots adds https:// and the port itself.");
    if (h.chars().anyMatch(c -> Character.isWhitespace(c) || c == '/' || c == '?' || c == '#' || c == '@' || c == '\\' || c < 32))
      throw new IllegalArgumentException("The host may contain only letters, digits, dots and dashes (no path, spaces, query or credentials).");
    boolean bracketed = h.startsWith("[") && h.endsWith("]");
    String inner = bracketed ? h.substring(1, h.length() - 1) : h;
    int colons = inner.length() - inner.replace(":", "").length();
    if (colons >= 2) {
      if (!inner.matches("[0-9A-Fa-f:.]+") || !inner.contains(":")) throw new IllegalArgumentException("That does not look like a valid IPv6 address.");
      return "[" + inner.toLowerCase(Locale.ROOT) + "]";
    }
    if (colons == 1 || bracketed) throw new IllegalArgumentException("Enter the host without a port. Put the port in the Port field.");
    if (inner.length() > 253 || !inner.matches("[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?") || inner.contains(".."))
      throw new IllegalArgumentException("The host may contain only letters, digits, dots and dashes.");
    return inner.toLowerCase(Locale.ROOT);
  }

  /** Blank means {@link #DEFAULT_PORT}. */
  public static int port(String raw) {
    String p = raw == null ? "" : raw.trim();
    if (p.isEmpty()) return DEFAULT_PORT;
    if (!p.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Port must be a number from 1 to 65535.");
    int v = Integer.parseInt(p);
    if (v < 1 || v > 65535) throw new IllegalArgumentException("Port must be a number from 1 to 65535.");
    return v;
  }

  /** https://host:port/v1 (the chat endpoint is Hosts.chatUrl(base), i.e. /v1/chat/completions). */
  public static String baseUrl(String hostRaw, String portRaw) {
    return Hosts.base("https://" + host(hostRaw) + ":" + port(portRaw) + "/v1");
  }

  /** Port of a stored base URL (443 when implicit); for pre-filling the edit form. */
  public static int portOf(String base) {
    try {
      int p = new java.net.URI(base).getPort();
      return p < 0 ? 443 : p;
    } catch (java.net.URISyntaxException e) {
      return DEFAULT_PORT;
    }
  }

  /** GET target for capability discovery: same origin as the profile, fixed path. */
  public static String capabilitiesUrl(String base) {
    return Hosts.origin(base) + CAPABILITIES_PATH;
  }

  /**
   * Everything that must hold before a VictorLinux profile may be saved. Throws with a message the
   * form can show next to the field.
   *
   * @param haveToken a token was typed, or a stored one is still readable for the new binding
   * @param haveRunnerToken same, for a runner on another origin
   */
  public static Spec validate(String hostRaw, String portRaw, String modelRaw, String pinRaw, boolean haveToken, String runnerUrlRaw, String runnerPinRaw, boolean haveRunnerToken) {
    Spec s = new Spec();
    s.base = baseUrl(hostRaw, portRaw);
    s.model = modelRaw == null ? "" : modelRaw.trim();
    if (s.model.isEmpty()) throw new IllegalArgumentException("Enter a model id (the default is " + DEFAULT_MODEL + ").");
    if (s.model.length() > 160) throw new IllegalArgumentException("Model identifier is too long.");
    if (!haveToken) throw new IllegalArgumentException("A VictorLinux profile needs its access token. VictorBots will not call your PC anonymously, so nothing was saved.");
    s.pin = requirePin(pinRaw, "This profile");
    String ru = runnerUrlRaw == null ? "" : runnerUrlRaw.trim();
    if (!ru.isEmpty()) {
      s.runnerUrl = Hosts.base(ru);
      if (!Hosts.origin(s.runnerUrl).equals(Hosts.origin(s.base))) {
        s.separateRunner = true;
        s.runnerPin = requirePin(runnerPinRaw, "A runner on a different address");
        if (!haveRunnerToken)
          throw new IllegalArgumentException("A runner on a different address needs its own access token. VictorBots never sends this PC's token to another address.");
      }
    }
    return s;
  }

  private static String requirePin(String raw, String who) {
    if (raw == null || raw.trim().isEmpty())
      throw new IllegalArgumentException(who + " needs the certificate SHA-256 fingerprint. VictorBots trusts only the exact certificate you pin, so nothing was saved.");
    return Rules.pin(raw);
  }

  /** Connection settings for discovery from form values; refuses to ask anything without a token and a pin. */
  public static Provider.Cfg discoveryCfg(String hostRaw, String portRaw, String pinRaw, String token) {
    Provider.Cfg c = new Provider.Cfg();
    c.base = baseUrl(hostRaw, portRaw);
    if (token == null || token.isEmpty()) throw new IllegalArgumentException("Enter the access token first. Nothing is requested anonymously.");
    c.key = token;
    c.pin = requirePin(pinRaw, "This profile");
    return c;
  }

  // ---------------------------------------------------------------- capability discovery

  /** What /victor/capabilities said, parsed defensively. Never drives any action by itself. */
  public static final class Capabilities {
    public boolean ok;
    /** TRUE/FALSE only when the server said so explicitly; null when unknown. */
    public Boolean toolCalls;
    public String name = "", version = "";
    public List<String> models = new ArrayList<>(), features = new ArrayList<>();
    public long contextLength;
    /** Why discovery failed (empty when ok). */
    public String error = "";

    /** One short human line for the form. */
    public String describe() {
      if (!ok) return "Could not detect capabilities: " + error + " The tool-call switch is left for you to set by hand.";
      StringBuilder b = new StringBuilder("Detected from the PC");
      if (!name.isEmpty()) b.append(": ").append(name).append(version.isEmpty() ? "" : " " + version);
      b.append(". Tool calls: ").append(toolCalls == null ? "not reported" : toolCalls ? "supported (switch pre-set; VictorBots still never executes tools)" : "reported as not supported (switch left as you had it)");
      if (!models.isEmpty()) b.append(". Models: ").append(String.join(", ", models.subList(0, Math.min(models.size(), 5))));
      if (contextLength > 0) b.append(". Context: ").append(contextLength).append(" tokens");
      return b.append('.').toString();
    }

    /** The "advertised" fact stored in Profile.tested, or null when the server said nothing useful. */
    public JSONObject advertised() {
      if (!ok || (toolCalls == null && features.isEmpty() && contextLength == 0)) return null;
      try {
        JSONArray caps = new JSONArray();
        for (String f : features) if (!f.equals("tools")) caps.put(f);
        if (Boolean.TRUE.equals(toolCalls)) caps.put("tools");
        JSONObject o = new JSONObject().put("capabilities", caps).put("source", CAPABILITIES_PATH).put("at", System.currentTimeMillis());
        if (contextLength > 0) o.put("context_length", contextLength);
        return o;
      } catch (JSONException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private static final String[] TOOL_KEYS = {"tool_calls", "tools", "tool_calling", "function_calling", "supports_tools", "supports_tool_calls", "toolcalls"};

  private static Boolean flag(Object v) {
    if (v instanceof Boolean) return (Boolean) v;
    if (v instanceof String) {
      String s = ((String) v).trim().toLowerCase(Locale.ROOT);
      if (s.equals("true") || s.equals("yes")) return Boolean.TRUE;
      if (s.equals("false") || s.equals("no")) return Boolean.FALSE;
    }
    if (v instanceof JSONObject) {
      JSONObject o = (JSONObject) v;
      for (String k : new String[] {"supported", "enabled", "available"}) if (o.has(k)) return flag(o.opt(k));
    }
    return null;
  }

  private static Boolean toolFlag(JSONObject o) {
    for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
      String key = it.next();
      String low = key.toLowerCase(Locale.ROOT).replace("-", "_");
      for (String t : TOOL_KEYS) if (low.equals(t)) {
        Boolean f = flag(o.opt(key));
        if (f != null) return f;
      }
    }
    return null;
  }

  private static String clean(String s) {
    StringBuilder b = new StringBuilder();
    for (char c : s.toCharArray()) {
      if (c >= 32 && c != 127) b.append(c);
      if (b.length() >= 80) break;
    }
    return b.toString().trim();
  }

  private static void names(Object v, List<String> out) {
    if (out.size() >= 20) return;
    if (v instanceof String) {
      String s = clean((String) v);
      if (!s.isEmpty()) out.add(s);
    } else if (v instanceof JSONObject) {
      names(((JSONObject) v).opt("id"), out);
    } else if (v instanceof JSONArray) {
      JSONArray a = (JSONArray) v;
      for (int i = 0; i < a.length() && out.size() < 20; i++) names(a.opt(i), out);
    }
  }

  /** Unknown fields, wrong types and missing keys are ignored; the worst outcome is "not reported". */
  public static Capabilities parse(String body) {
    Capabilities c = new Capabilities();
    JSONObject root;
    try {
      root = new JSONObject(body == null ? "" : body.trim());
    } catch (JSONException e) {
      c.error = "the reply was not a JSON object.";
      return c;
    }
    c.ok = true;
    c.name = clean(root.optString("name", root.optString("server", root.optString("service", ""))));
    c.version = clean(root.optString("version", ""));
    names(root.opt("models"), c.models);
    names(root.opt("model"), c.models);
    long ctx = root.optLong("context_length", root.optLong("context_window", root.optLong("max_context", 0)));
    c.contextLength = ctx > 0 && ctx <= 100_000_000L ? ctx : 0;
    c.toolCalls = toolFlag(root);
    for (String nest : new String[] {"capabilities", "features", "supports"}) {
      Object n = root.opt(nest);
      if (n instanceof JSONObject) {
        JSONObject o = (JSONObject) n;
        if (c.toolCalls == null) c.toolCalls = toolFlag(o);
        if (c.contextLength == 0) {
          long x = o.optLong("context_length", o.optLong("context_window", 0));
          c.contextLength = x > 0 && x <= 100_000_000L ? x : 0;
        }
        for (java.util.Iterator<String> it = o.keys(); it.hasNext() && c.features.size() < 20; ) {
          String k = it.next();
          if (Boolean.TRUE.equals(flag(o.opt(k)))) c.features.add(clean(k.toLowerCase(Locale.ROOT)));
        }
      } else if (n instanceof JSONArray) {
        JSONArray a = (JSONArray) n;
        for (int i = 0; i < a.length() && c.features.size() < 20; i++) {
          String f = clean(a.optString(i, "")).toLowerCase(Locale.ROOT);
          if (f.isEmpty()) continue;
          c.features.add(f);
          for (String t : TOOL_KEYS) if (f.replace("-", "_").equals(t) && c.toolCalls == null) c.toolCalls = Boolean.TRUE;
        }
      }
    }
    return c;
  }

  /**
   * GET /victor/capabilities over the profile's pinned TLS with its Bearer token. No redirects, finite
   * timeouts, bounded body. Never throws: a failure becomes a non-fatal note. It sends no chat text
   * and starts nothing; the result only tells the form what to pre-select.
   */
  public static Capabilities fetch(Provider.Cfg cfg, int connectMs, int readMs) {
    Capabilities fail = new Capabilities();
    if (cfg.pin.isEmpty() || cfg.key.isEmpty()) {
      fail.error = "a token and a certificate pin are both required.";
      return fail;
    }
    cfg.connectMs = connectMs;
    cfg.readMs = readMs;
    HttpsURLConnection c = null;
    try {
      c = Provider.open(capabilitiesUrl(cfg.base), cfg, null, "GET");
      int status = c.getResponseCode();
      if (status != 200) {
        String err = "";
        try (InputStream es = c.getErrorStream()) {
          if (es != null) err = new String(Provider.readBounded(es, 4096), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
        fail.error = Provider.classifyStatus(status, err, cfg).getMessage();
        return fail;
      }
      byte[] data;
      try (InputStream in = c.getInputStream()) {
        data = Provider.readBounded(in, MAX_CAPABILITIES_BYTES);
      }
      return parse(new String(data, StandardCharsets.UTF_8));
    } catch (IOException e) {
      fail.error = Provider.classifyIo(e, null, false).getMessage();
      return fail;
    } catch (RuntimeException e) {
      fail.error = Redact.snippet(String.valueOf(e.getMessage()), 160, cfg.key);
      return fail;
    } finally {
      if (c != null) c.disconnect();
    }
  }

  public static Capabilities fetch(Provider.Cfg cfg) {
    return fetch(cfg, 8000, 10000);
  }

  private VictorLinux() {}
}
