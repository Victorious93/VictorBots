package com.victorious.bots;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

/**
 * Client for the VictorBots PC runner (see pc-runner/). For ordinary PC profiles: same authenticated
 * HTTPS origin and pin as the inference profile. A VictorLinux profile reaches a runner only through
 * its own runner URL ({@link Profile#runner}). It only reports what the runner says; it never infers
 * a result.
 */
public final class RunnerClient {
  private final Provider.Cfg cfg;
  private final String rb;

  /**
   * @param key the runner's access token. For ordinary PC profiles that is the profile's own token
   *     (the runner lives at /runner/v1 beside the inference API); for a VictorLinux profile with a
   *     separate runner it is the runner's own token.
   * @throws IllegalStateException {@link VictorLinux#NO_RUNNER} when the profile has no runner
   */
  public RunnerClient(Profile p, String key) {
    if (p.kind != Policy.Kind.PC) throw new IllegalArgumentException("A PC profile is required.");
    Profile.Runner r = p.runner();
    if (r == null) throw new IllegalStateException(VictorLinux.NO_RUNNER + ".");
    cfg = p.cfg(key);
    cfg.base = r.base;
    cfg.pin = r.pin;
    cfg.readMs = 30000;
    cfg.connectMs = 8000;
    rb = r.base;
  }

  /**
   * Builds a client with the credentials bound to this profile's runner. Refuses (with a message the UI
   * can show) when there is no runner, or the token is missing or no longer matches the endpoint/pin.
   */
  public static RunnerClient forProfile(Profile p, Secrets secrets) {
    if (p == null) throw new IllegalStateException("The PC profile no longer exists.");
    Profile.Runner r = p.runner();
    if (r == null) throw new IllegalStateException(VictorLinux.NO_RUNNER + ".");
    String key;
    try {
      key = secrets.read(r.secretId, r.binding);
    } catch (SecurityException e) {
      throw new IllegalStateException("The stored access token no longer matches this " + (r.separate ? "runner's" : "profile's") + " address or certificate pin. Open Providers, edit the profile and enter it again.");
    } catch (Exception e) {
      throw new IllegalStateException("The stored access token could not be decrypted. Enter it again in Providers.");
    }
    if (key.isEmpty()) throw new IllegalStateException("No access token is stored for this " + (r.separate ? "runner" : "PC profile") + ". VictorBots will not call it anonymously.");
    return new RunnerClient(p, key);
  }

  /** @param sentFlag set to true once the request body has been written (the runner may have it). */
  private JSONObject call(String method, String path, JSONObject body, boolean[] sentFlag) throws ProviderError {
    HttpsURLConnection c = null;
    boolean sent = false;
    try {
      c = Provider.open(rb + path, cfg, null, method);
      if (body != null) {
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(b.length);
        try (OutputStream o = c.getOutputStream()) {
          o.write(b);
        }
      }
      sent = true;
      if (sentFlag != null) sentFlag[0] = true;
      int status = c.getResponseCode();
      if (status < 200 || status >= 300) {
        String err = "";
        try (InputStream es = c.getErrorStream()) {
          if (es != null) err = new String(Provider.readBounded(es, 16384), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
        throw Provider.classifyStatus(status, err, cfg);
      }
      try (InputStream in = c.getInputStream()) {
        return new JSONObject(new String(Provider.readBounded(in, Provider.MAX_RESPONSE), StandardCharsets.UTF_8));
      }
    } catch (JSONException e) {
      throw new ProviderError(ProviderError.Category.MALFORMED, 200, "The runner's reply was not valid JSON.", sent);
    } catch (IOException e) {
      throw Provider.classifyIo(e, null, sent);
    } finally {
      if (c != null) c.disconnect();
    }
  }

  public JSONObject capabilities() throws ProviderError {
    JSONObject o = call("GET", "/capabilities", null, null);
    if (o.optInt("protocol") != 1) throw new ProviderError(ProviderError.Category.UNSUPPORTED, 200, "The runner speaks protocol " + o.optInt("protocol") + "; this app needs 1.", false);
    return o;
  }

  static Jobs.Remote parse(JSONObject o) throws JSONException {
    JSONObject j = o.has("job") ? o.getJSONObject("job") : o;
    Jobs.Remote r = new Jobs.Remote();
    r.id = j.optString("id");
    r.state = j.optString("state");
    r.error = j.optString("error");
    r.started = (long) (j.optDouble("started", 0) * 1000);
    r.finished = (long) (j.optDouble("finished", 0) * 1000);
    if (j.has("exit_code") && !j.isNull("exit_code")) r.exitCode = j.optInt("exit_code");
    JSONObject res = j.optJSONObject("result");
    r.resultJson = res == null ? "{}" : res.toString();
    JSONArray arts = j.optJSONArray("artifacts");
    if (arts != null) for (int i = 0; i < arts.length(); i++) r.artifacts.add(arts.getJSONObject(i));
    JSONArray ev = o.optJSONArray("events");
    if (ev != null)
      for (int i = 0; i < ev.length(); i++) {
        JSONObject e = ev.getJSONObject(i);
        r.events.add(new String[] {"" + e.getLong("seq"), "" + (long) (e.optDouble("ts", 0) * 1000), e.optString("stream", "stdout"), e.optString("text")});
      }
    r.seq = o.optLong("last_seq", j.optLong("last_seq"));
    return r;
  }

  /** @param sent out-flag: true if the request body went out, so the runner may have accepted it. */
  public Jobs.Remote submit(String requestId, String workspace, String tool, JSONObject args, String title, boolean[] sent) throws ProviderError {
    try {
      JSONObject body = new JSONObject().put("request_id", requestId).put("workspace", workspace).put("tool", tool).put("args", args).put("title", title);
      return parse(call("POST", "/jobs", body, sent));
    } catch (JSONException e) {
      throw new ProviderError(ProviderError.Category.MALFORMED, 200, "Malformed runner reply.", true);
    }
  }

  /** Looks a job up by the id the phone generated. Returns null when the runner has no such request. */
  public Jobs.Remote byRequest(String requestId) throws ProviderError {
    try {
      JSONObject o = call("GET", "/jobs?request_id=" + java.net.URLEncoder.encode(requestId, "UTF-8"), null, null);
      JSONArray a = o.optJSONArray("jobs");
      if (a == null || a.length() == 0) return null;
      return parse(a.getJSONObject(0));
    } catch (JSONException | UnsupportedEncodingException e) {
      throw new ProviderError(ProviderError.Category.MALFORMED, 200, "Malformed runner reply.", false);
    }
  }

  public Jobs.Remote events(String remoteId, long after) throws ProviderError {
    try {
      return parse(call("GET", "/jobs/" + enc(remoteId) + "/events?after=" + after, null, null));
    } catch (JSONException e) {
      throw new ProviderError(ProviderError.Category.MALFORMED, 200, "Malformed runner reply.", false);
    }
  }

  public Jobs.Remote cancel(String remoteId) throws ProviderError {
    try {
      return parse(call("POST", "/jobs/" + enc(remoteId) + "/cancel", new JSONObject(), null));
    } catch (JSONException e) {
      throw new ProviderError(ProviderError.Category.MALFORMED, 200, "Malformed runner reply.", true);
    }
  }

  public Jobs.Remote approve(String remoteId, boolean allow) throws ProviderError {
    try {
      return parse(call("POST", "/jobs/" + enc(remoteId) + "/approve", new JSONObject().put("decision", allow ? "allow" : "deny"), null));
    } catch (JSONException e) {
      throw new ProviderError(ProviderError.Category.MALFORMED, 200, "Malformed runner reply.", true);
    }
  }

  private static String enc(String s) {
    if (!s.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Bad id.");
    return s;
  }

  /** Downloads an artifact, bounded, and verifies it against the SHA-256 the runner reported. */
  public byte[] artifact(String remoteId, String name, long expectedSize, String expectedSha256, long maxBytes) throws ProviderError {
    if (!Jobs.safeName(name)) throw new IllegalArgumentException("Unsafe artifact name.");
    if (expectedSize > maxBytes) throw new IllegalArgumentException("This artifact is larger than the " + (maxBytes / 1048576) + " MB download limit.");
    HttpsURLConnection c = null;
    try {
      c = Provider.open(rb + "/jobs/" + enc(remoteId) + "/artifacts/" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20"), cfg, null, "GET");
      c.setReadTimeout(120000);
      int status = c.getResponseCode();
      if (status != 200) throw Provider.classifyStatus(status, "", cfg);
      byte[] data;
      try (InputStream in = c.getInputStream()) {
        data = Provider.readBounded(in, (int) Math.min(maxBytes, Integer.MAX_VALUE));
      }
      String got = hex(MessageDigest.getInstance("SHA-256").digest(data));
      if (!got.equalsIgnoreCase(expectedSha256) || (expectedSize > 0 && data.length != expectedSize))
        throw new ProviderError(ProviderError.Category.MALFORMED, 200, "The downloaded file does not match the size/SHA-256 the runner reported, so it was discarded.", false);
      return data;
    } catch (java.security.NoSuchAlgorithmException | UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    } catch (IOException e) {
      throw Provider.classifyIo(e, null, false);
    } finally {
      if (c != null) c.disconnect();
    }
  }

  static String hex(byte[] d) {
    StringBuilder b = new StringBuilder();
    for (byte v : d) b.append(String.format(Locale.ROOT, "%02x", v));
    return b.toString();
  }

  // ---- connectors and routines (runner-side features) ----

  public JSONObject connectors() throws ProviderError {
    return call("GET", "/connectors", null, null);
  }

  public JSONObject connectorTools(String connector, String workspace) throws ProviderError {
    if (!workspace.matches("[A-Za-z0-9_-]{1,32}")) throw new IllegalArgumentException("Bad workspace id.");
    return call("GET", "/connectors/" + enc(connector) + "/tools?workspace=" + workspace, null, null);
  }

  public JSONObject connectorCall(String connector, String tool, JSONObject args, String workspace) throws ProviderError {
    try {
      return call("POST", "/connectors/" + enc(connector) + "/call", new JSONObject().put("tool", tool).put("args", args).put("workspace", workspace), null);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  public JSONObject putRoutine(String uid, JSONObject def) throws ProviderError {
    return call("PUT", "/routines/" + enc(uid), def, null);
  }

  public JSONObject deleteRoutine(String uid) throws ProviderError {
    return call("DELETE", "/routines/" + enc(uid), null, null);
  }

  public JSONObject routineRuns(String uid) throws ProviderError {
    return call("GET", "/routines/" + enc(uid) + "/runs", null, null);
  }
}
