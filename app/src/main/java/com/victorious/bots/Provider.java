package com.victorious.bots;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.*;
import org.json.*;

/**
 * Bounded HTTPS client for OpenAI-compatible chat backends. Never follows redirects, never retries,
 * never logs credentials, and never executes tool calls (it only relays them; see ToolLoop). Only features the owner configured are sent.
 */
public final class Provider {
  public static final int MAX_RESPONSE = 2 * 1024 * 1024;

  public static final class Cfg {
    public String base = "", key = "", model = "", pin = "", reasoning = "";
    public int connectMs = 15000, readMs = 90000;
    /** Whether the profile enables sending image parts. */
    public boolean images;
    /** OpenAI-style function tool definitions to offer, or null. Set only by Gateway. */
    public JSONArray tools;
  }

  /** Token counts are null when the provider did not report them: "unknown", never zero. */
  public static final class Usage {
    public Long in, out, total;
  }

  public static final class Reply {
    public String text = "", modelReported = "", finish = "";
    public Usage usage = new Usage();
    public long latencyMs, firstTokenMs = -1;
    public boolean streamed;
    public List<JSONObject> toolCalls = new ArrayList<>();
  }

  public interface Sink {
    void delta(String text);
  }

  /** Cooperative cancellation that also tears down the in-flight connection. */
  public static final class Cancel {
    private volatile boolean cancelled;
    private volatile HttpURLConnection conn;

    public void cancel() {
      cancelled = true;
      HttpURLConnection c = conn;
      if (c != null) c.disconnect();
    }

    public boolean isCancelled() {
      return cancelled;
    }
  }

  static HttpsURLConnection open(String url, Cfg cfg, Cancel cancel, String method)
      throws IOException {
    URL u = new URL(url);
    if (!"https".equals(u.getProtocol()))
      throw new ProviderError(ProviderError.Category.UNSUPPORTED, 0, "HTTPS is required.", false);
    if (!Hosts.origin(url).equals(Hosts.origin(cfg.base)))
      throw new IllegalStateException("Request origin differs from the profile endpoint.");
    HttpsURLConnection c = (HttpsURLConnection) u.openConnection();
    if (!cfg.pin.isEmpty()) {
      c.setSSLSocketFactory(Tls.pinnedFactory(cfg.pin));
      c.setHostnameVerifier(Tls.PIN_ONLY);
    }
    c.setInstanceFollowRedirects(false);
    c.setConnectTimeout(cfg.connectMs);
    c.setReadTimeout(cfg.readMs);
    c.setRequestMethod(method);
    c.setRequestProperty("Accept", "application/json, text/event-stream");
    if (!cfg.key.isEmpty()) {
      if (cfg.key.indexOf('\n') >= 0 || cfg.key.indexOf('\r') >= 0)
        throw new IllegalArgumentException("Invalid API key.");
      c.setRequestProperty("Authorization", "Bearer " + cfg.key);
    }
    if (cancel != null) cancel.conn = c;
    return c;
  }

  static byte[] readBounded(InputStream in, int max) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] b = new byte[8192];
    int n;
    while ((n = in.read(b)) != -1) {
      if (out.size() + n > max)
        throw new ProviderError(
            ProviderError.Category.TOO_LARGE, 0, "Response exceeds the size limit.", true);
      out.write(b, 0, n);
    }
    return out.toByteArray();
  }

  static ProviderError classifyStatus(int status, String body, Cfg cfg) {
    String snippet = Redact.snippet(body, 240, cfg.key);
    String low = body == null ? "" : body.toLowerCase(Locale.ROOT);
    if (status >= 300 && status < 400)
      return new ProviderError(
          ProviderError.Category.REDIRECT,
          status,
          "The server redirected the request. Redirects are refused so your key is never sent to"
              + " another address.",
          false);
    if (status == 401 || status == 403)
      return new ProviderError(
          ProviderError.Category.AUTH,
          status,
          "The server rejected the credentials (HTTP " + status + "). Check the API key/token.",
          false);
    if (status == 429)
      return new ProviderError(
          ProviderError.Category.RATE_LIMIT, status, "Rate limited (HTTP 429). " + snippet, false);
    if (status == 404 && low.contains("model"))
      return new ProviderError(
          ProviderError.Category.MODEL_UNAVAILABLE,
          status,
          "The server does not know this model. " + snippet,
          false);
    if (status == 404)
      return new ProviderError(
          ProviderError.Category.NOT_FOUND,
          status,
          "Endpoint not found (HTTP 404). Check the base URL path (usually ends in /v1). " + snippet,
          false);
    if (status == 400 || status == 422) {
      boolean model = low.contains("model") && (low.contains("not found") || low.contains("does not exist") || low.contains("unknown") || low.contains("invalid"));
      return new ProviderError(
          model ? ProviderError.Category.MODEL_UNAVAILABLE : ProviderError.Category.UNSUPPORTED,
          status,
          (model ? "The model was rejected. " : "The server rejected a request field. ") + snippet,
          false);
    }
    if (status >= 500)
      return new ProviderError(
          ProviderError.Category.SERVER, status, "Server error (HTTP " + status + "). " + snippet, true);
    return new ProviderError(
        ProviderError.Category.SERVER, status, "Unexpected HTTP " + status + ". " + snippet, true);
  }

  static ProviderError classifyIo(IOException e, Cancel cancel, boolean sent) {
    if (e instanceof ProviderError) return (ProviderError) e;
    if (cancel != null && cancel.isCancelled())
      return new ProviderError(
          ProviderError.Category.CANCELLED, 0, "Stopped. The provider may still have processed it.", sent);
    if (e instanceof SocketTimeoutException)
      return new ProviderError(
          ProviderError.Category.TIMEOUT,
          0,
          sent
              ? "Timed out waiting for the reply. The provider may still have processed the"
                  + " request; nothing was retried."
              : "Timed out connecting. Nothing was sent.",
          sent);
    if (e instanceof SSLException)
      return new ProviderError(
          ProviderError.Category.TLS,
          0,
          "TLS failed: " + Redact.snippet(e.getMessage(), 160) + ". For a private certificate, pin its"
              + " SHA-256 fingerprint in the profile.",
          false);
    if (e instanceof UnknownHostException || e instanceof ConnectException || e instanceof NoRouteToHostException)
      return new ProviderError(
          ProviderError.Category.UNREACHABLE,
          0,
          "Could not reach the host (" + e.getClass().getSimpleName() + "). Check the address, VPN/Wi-Fi"
              + " and that the server is listening.",
          false);
    return new ProviderError(
        ProviderError.Category.UNREACHABLE,
        0,
        "Connection failed: " + Redact.snippet(e.getMessage(), 160),
        sent);
  }

  private static JSONArray messages(List<Rules.Turn> turns, boolean images) throws JSONException {
    JSONArray arr = new JSONArray();
    for (Rules.Turn t : turns) {
      JSONObject m = new JSONObject().put("role", t.role);
      if (t.role.equals("tool")) {
        m.put("tool_call_id", t.toolCallId).put("content", t.content);
        arr.put(m);
        continue;
      }
      if (t.toolCalls != null) {
        m.put("content", t.content.isEmpty() ? JSONObject.NULL : t.content).put("tool_calls", t.toolCalls);
        arr.put(m);
        continue;
      }
      if (!t.images.isEmpty()) {
        if (!images)
          throw new IllegalArgumentException(
              "This provider profile is not configured for images. Enable images only for a model"
                  + " that supports them.");
        JSONArray parts = new JSONArray().put(new JSONObject().put("type", "text").put("text", t.content));
        for (String url : t.images)
          parts.put(
              new JSONObject()
                  .put("type", "image_url")
                  .put("image_url", new JSONObject().put("url", url)));
        m.put("content", parts);
      } else m.put("content", t.content);
      arr.put(m);
    }
    return arr;
  }

  public static Reply chat(
      Cfg cfg, List<Rules.Turn> turns, boolean stream, Sink sink, Cancel cancel) throws ProviderError {
    Rules.length(cfg.model, 160, "Model");
    long start = System.nanoTime();
    boolean sent = false;
    HttpsURLConnection c = null;
    try {
      c = open(Hosts.chatUrl(cfg.base), cfg, cancel, "POST");
      c.setDoOutput(true);
      c.setRequestProperty("Content-Type", "application/json");
      JSONObject req = new JSONObject().put("model", cfg.model).put("messages", messages(turns, cfg.images)).put("stream", stream);
      if (stream) req.put("stream_options", new JSONObject().put("include_usage", true));
      if (!cfg.reasoning.isEmpty()) req.put("reasoning_effort", cfg.reasoning);
      if (cfg.tools != null && cfg.tools.length() > 0) req.put("tools", cfg.tools).put("tool_choice", "auto");
      byte[] body = req.toString().getBytes(StandardCharsets.UTF_8);
      c.setFixedLengthStreamingMode(body.length);
      try (OutputStream o = c.getOutputStream()) {
        o.write(body);
      }
      sent = true;
      int status = c.getResponseCode();
      if (status < 200 || status >= 300) {
        String err = "";
        try (InputStream es = c.getErrorStream()) {
          if (es != null) err = new String(readBounded(es, 16384), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
        throw classifyStatus(status, err, cfg);
      }
      Reply r = new Reply();
      r.streamed = stream;
      try (InputStream in = c.getInputStream()) {
        if (stream) readSse(in, r, sink, start, cancel);
        else readJson(in, r);
      }
      r.latencyMs = (System.nanoTime() - start) / 1_000_000;
      if (r.text.trim().isEmpty() && r.toolCalls.isEmpty())
        throw new ProviderError(
            ProviderError.Category.MALFORMED, 200, "The provider returned no text.", true);
      return r;
    } catch (ProviderError e) {
      if (cancel != null && cancel.isCancelled() && e.category != ProviderError.Category.CANCELLED)
        throw classifyIo(new IOException("cancelled"), cancel, sent);
      throw e;
    } catch (JSONException e) {
      throw new ProviderError(
          ProviderError.Category.MALFORMED, 200, "The reply was not valid OpenAI-style JSON.", true);
    } catch (IOException e) {
      throw classifyIo(e, cancel, sent);
    } finally {
      if (c != null) c.disconnect();
    }
  }

  private static void readJson(InputStream in, Reply r) throws IOException, JSONException {
    JSONObject root = new JSONObject(new String(readBounded(in, MAX_RESPONSE), StandardCharsets.UTF_8));
    JSONObject msg = root.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
    Object content = msg.opt("content");
    if (content instanceof String) r.text = (String) content;
    r.modelReported = root.optString("model", "");
    r.finish = root.getJSONArray("choices").getJSONObject(0).optString("finish_reason", "");
    JSONArray tc = msg.optJSONArray("tool_calls");
    if (tc != null) for (int i = 0; i < tc.length(); i++) r.toolCalls.add(tc.getJSONObject(i));
    parseUsage(root.optJSONObject("usage"), r.usage);
  }

  private static void parseUsage(JSONObject u, Usage out) {
    if (u == null) return;
    if (u.has("prompt_tokens") && !u.isNull("prompt_tokens")) out.in = u.optLong("prompt_tokens");
    if (u.has("completion_tokens") && !u.isNull("completion_tokens")) out.out = u.optLong("completion_tokens");
    if (u.has("total_tokens") && !u.isNull("total_tokens")) out.total = u.optLong("total_tokens");
  }

  private static void readSse(InputStream in, Reply r, Sink sink, long start, Cancel cancel)
      throws IOException, JSONException {
    BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    StringBuilder text = new StringBuilder();
    int total = 0;
    String line;
    boolean done = false;
    while ((line = br.readLine()) != null) {
      if (cancel != null && cancel.isCancelled()) break;
      total += line.length() + 1;
      if (total > MAX_RESPONSE)
        throw new ProviderError(ProviderError.Category.TOO_LARGE, 0, "Response exceeds the size limit.", true);
      if (!line.startsWith("data:")) continue;
      String data = line.substring(5).trim();
      if (data.isEmpty()) continue;
      if (data.equals("[DONE]")) {
        done = true;
        break;
      }
      JSONObject chunk;
      try {
        chunk = new JSONObject(data);
      } catch (JSONException e) {
        throw new ProviderError(ProviderError.Category.MALFORMED, 200, "A streamed chunk was not valid JSON.", true);
      }
      if (chunk.has("error"))
        throw new ProviderError(
            ProviderError.Category.SERVER, 200, "The server reported an error mid-stream: "
                + Redact.snippet(chunk.get("error").toString(), 200), true);
      if (r.modelReported.isEmpty()) r.modelReported = chunk.optString("model", "");
      parseUsage(chunk.optJSONObject("usage"), r.usage);
      JSONArray choices = chunk.optJSONArray("choices");
      if (choices == null || choices.length() == 0) continue;
      JSONObject ch = choices.getJSONObject(0);
      String fin = ch.optString("finish_reason", "");
      if (!fin.isEmpty() && !fin.equals("null")) r.finish = fin;
      JSONObject delta = ch.optJSONObject("delta");
      if (delta == null) continue;
      String piece = delta.isNull("content") ? "" : delta.optString("content", "");
      if (!piece.isEmpty()) {
        if (r.firstTokenMs < 0) r.firstTokenMs = (System.nanoTime() - start) / 1_000_000;
        text.append(piece);
        if (text.length() > MAX_RESPONSE)
          throw new ProviderError(ProviderError.Category.TOO_LARGE, 0, "Response exceeds the size limit.", true);
        if (sink != null) sink.delta(piece);
      }
      JSONArray tc = delta.optJSONArray("tool_calls");
      if (tc != null) for (int i = 0; i < tc.length(); i++) mergeToolDelta(r.toolCalls, tc.getJSONObject(i));
    }
    r.text = text.toString();
    if (cancel != null && cancel.isCancelled())
      throw new ProviderError(ProviderError.Category.CANCELLED, 0, "Stopped.", true);
    if (!done && text.length() == 0 && r.toolCalls.isEmpty())
      throw new ProviderError(ProviderError.Category.MALFORMED, 200, "The stream ended without data.", true);
  }

  /**
   * Streamed tool calls arrive as fragments keyed by "index": the first carries id and function name,
   * later ones append to function.arguments. Reassemble them into whole OpenAI tool_call objects.
   */
  static void mergeToolDelta(List<JSONObject> calls, JSONObject d) throws JSONException, ProviderError {
    int idx = d.optInt("index", calls.size());
    if (idx < 0 || idx > 31) throw new ProviderError(ProviderError.Category.MALFORMED, 200, "Tool call index out of range.", true);
    while (calls.size() <= idx)
      calls.add(new JSONObject().put("type", "function").put("id", "").put("function", new JSONObject().put("name", "").put("arguments", "")));
    JSONObject c = calls.get(idx);
    if (d.has("id") && !d.isNull("id")) c.put("id", d.optString("id"));
    JSONObject f = d.optJSONObject("function");
    if (f == null) return;
    JSONObject cf = c.getJSONObject("function");
    if (f.has("name") && !f.isNull("name")) cf.put("name", cf.optString("name") + f.optString("name"));
    if (f.has("arguments") && !f.isNull("arguments")) {
      String a = cf.optString("arguments") + f.optString("arguments");
      if (a.length() > MAX_RESPONSE) throw new ProviderError(ProviderError.Category.TOO_LARGE, 0, "Response exceeds the size limit.", true);
      cf.put("arguments", a);
    }
  }

  /** GET {base}/models → ids. Models listing is optional on some servers (then enter the id manually). */
  public static List<String> models(Cfg cfg) throws ProviderError {
    HttpsURLConnection c = null;
    try {
      c = open(Hosts.modelsUrl(cfg.base), cfg, null, "GET");
      int status = c.getResponseCode();
      if (status < 200 || status >= 300) {
        String err = "";
        try (InputStream es = c.getErrorStream()) {
          if (es != null) err = new String(readBounded(es, 16384), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
        throw classifyStatus(status, err, cfg);
      }
      JSONObject root;
      try (InputStream in = c.getInputStream()) {
        root = new JSONObject(new String(readBounded(in, MAX_RESPONSE), StandardCharsets.UTF_8));
      }
      JSONArray data = root.optJSONArray("data");
      if (data == null) data = root.optJSONArray("models");
      if (data == null)
        throw new ProviderError(ProviderError.Category.MALFORMED, 200, "The model list had no data array.", false);
      List<String> ids = new ArrayList<>();
      for (int i = 0; i < data.length() && ids.size() < 500; i++) {
        JSONObject m = data.optJSONObject(i);
        String id = m == null ? "" : m.optString("id", m.optString("name", ""));
        if (!id.isEmpty()) ids.add(id);
      }
      return ids;
    } catch (JSONException e) {
      throw new ProviderError(ProviderError.Category.MALFORMED, 200, "The model list was not valid JSON.", false);
    } catch (IOException e) {
      throw classifyIo(e, null, false);
    } finally {
      if (c != null) c.disconnect();
    }
  }

  /**
   * Ollama-native probe on the same origin: /api/version, then /api/show for capabilities and the
   * model's maximum context. Returns null when the server is not Ollama-shaped (no error: optional).
   */
  public static JSONObject ollamaInfo(Cfg cfg) {
    HttpsURLConnection c = null;
    try {
      String api = Hosts.apiBase(cfg.base);
      c = open(api + "/version", cfg, null, "GET");
      if (c.getResponseCode() != 200) return null;
      JSONObject v;
      try (InputStream in = c.getInputStream()) {
        v = new JSONObject(new String(readBounded(in, 65536), StandardCharsets.UTF_8));
      }
      if (!v.has("version")) return null;
      c.disconnect();
      JSONObject out = new JSONObject().put("server", "ollama").put("version", v.optString("version"));
      if (!cfg.model.isEmpty()) {
        c = open(api + "/show", cfg, null, "POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        byte[] body = new JSONObject().put("model", cfg.model).toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream o = c.getOutputStream()) {
          o.write(body);
        }
        if (c.getResponseCode() == 200) {
          JSONObject show;
          try (InputStream in = c.getInputStream()) {
            show = new JSONObject(new String(readBounded(in, MAX_RESPONSE), StandardCharsets.UTF_8));
          }
          if (show.optJSONArray("capabilities") != null) out.put("capabilities", show.getJSONArray("capabilities"));
          JSONObject mi = show.optJSONObject("model_info");
          if (mi != null) {
            Iterator<String> it = mi.keys();
            while (it.hasNext()) {
              String k = it.next();
              if (k.endsWith(".context_length")) {
                out.put("context_length", mi.optLong(k));
                break;
              }
            }
          }
        }
      }
      return out;
    } catch (Exception e) {
      return null;
    } finally {
      if (c != null) c.disconnect();
    }
  }

  private Provider() {}
}
