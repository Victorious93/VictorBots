import com.sun.net.httpserver.HttpExchange;
import com.victorious.bots.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

public final class ProviderTest {
  static final List<Rules.Turn> TURNS = Arrays.asList(new Rules.Turn("system", "be brief"), new Rules.Turn("user", "hi"));

  static Provider.Cfg cfg(TestServer s, String key, String pin) {
    Provider.Cfg c = new Provider.Cfg();
    c.base = s.base("/v1");
    c.key = key;
    c.model = "test-model";
    c.pin = pin;
    c.readMs = 4000;
    c.connectMs = 3000;
    return c;
  }

  static ProviderError.Category cat(Provider.Cfg c, boolean stream) {
    try {
      Provider.chat(c, TURNS, stream, null, null);
      return null;
    } catch (ProviderError e) {
      return e.category;
    }
  }

  public static void main(String[] a) throws Exception {
    Path dir = Files.createTempDirectory("vbtest");
    TestServer s = new TestServer(dir);
    String ok = "{\"model\":\"test-model-2025\",\"choices\":[{\"message\":{\"content\":\"hello there\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":3,\"total_tokens\":14}}";
    s.on("/v1/chat/completions", ex -> TestServer.reply(ex, 200, ok));

    // TLS trust: unpinned self-signed is rejected, correct pin accepted, wrong pin rejected.
    T.eq("unpinned self-signed rejected (TLS)", ProviderError.Category.TLS, cat(cfg(s, "k", ""), false));
    T.eq("wrong pin rejected (TLS)", ProviderError.Category.TLS, cat(cfg(s, "k", "00".repeat(32)), false));
    Provider.Reply r = Provider.chat(cfg(s, "sekret-token-1", s.fingerprint), TURNS, false, null, null);
    T.eq("pinned works: text", "hello there", r.text);
    T.eq("usage in reported", (Long) 11L, r.usage.in);
    T.eq("usage out reported", (Long) 3L, r.usage.out);
    T.eq("reported model captured", "test-model-2025", r.modelReported);
    T.check("latency measured", r.latencyMs >= 0);
    T.eq("no first-token time for non-streaming", -1L, r.firstTokenMs);
    T.eq("bearer sent to the bound origin", "Bearer sekret-token-1", s.seen.get(s.seen.size() - 1).auth);
    JSONObject sent = new JSONObject(s.seen.get(s.seen.size() - 1).body);
    T.eq("request carries model", "test-model", sent.getString("model"));
    T.check("no reasoning field unless configured", !sent.has("reasoning_effort"));
    T.check("no stream_options when not streaming", !sent.has("stream_options"));
    Provider.Cfg rc = cfg(s, "k", s.fingerprint);
    rc.reasoning = "low";
    Provider.chat(rc, TURNS, false, null, null);
    T.eq("reasoning only when owner enabled", "low", new JSONObject(s.seen.get(s.seen.size() - 1).body).getString("reasoning_effort"));

    // Missing usage is unknown, not zero.
    s.on("/v1/chat/completions", ex -> TestServer.reply(ex, 200, "{\"choices\":[{\"message\":{\"content\":\"x\"}}]}"));
    Provider.Reply nu = Provider.chat(cfg(s, "k", s.fingerprint), TURNS, false, null, null);
    T.check("absent usage is null (unknown)", nu.usage.in == null && nu.usage.out == null && nu.usage.total == null);

    // Streaming: deltas, usage in the final chunk, first-token time measured.
    s.on("/v1/chat/completions", ex -> TestServer.sse(ex, Arrays.asList(
        "{\"model\":\"m-stream\",\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}",
        "{\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}",
        "{\"choices\":[{\"delta\":{\"content\":\"lo\"},\"finish_reason\":\"stop\"}]}",
        "{\"choices\":[],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":2,\"total_tokens\":11}}",
        "[DONE]"), 60));
    StringBuilder got = new StringBuilder();
    Provider.Reply sr = Provider.chat(cfg(s, "k", s.fingerprint), TURNS, true, got::append, null);
    T.eq("stream text assembled", "Hello", sr.text);
    T.eq("sink got deltas", "Hello", got.toString());
    T.eq("stream usage", (Long) 9L, sr.usage.in);
    T.check("first-token latency measured and below total", sr.firstTokenMs >= 50 && sr.firstTokenMs <= sr.latencyMs);
    T.eq("stream model reported", "m-stream", sr.modelReported);
    T.eq("include_usage requested for stream", true, new JSONObject(s.seen.get(s.seen.size() - 1).body).getJSONObject("stream_options").getBoolean("include_usage"));

    // Cancel mid-stream tears the connection down and reports CANCELLED.
    s.on("/v1/chat/completions", ex -> TestServer.sse(ex, Arrays.asList(
        "{\"choices\":[{\"delta\":{\"content\":\"a\"}}]}",
        "{\"choices\":[{\"delta\":{\"content\":\"b\"}}]}",
        "{\"choices\":[{\"delta\":{\"content\":\"c\"}}]}",
        "{\"choices\":[{\"delta\":{\"content\":\"d\"}}]}"), 400));
    Provider.Cancel cancel = new Provider.Cancel();
    new Thread(() -> { try { Thread.sleep(600); } catch (Exception e) { } cancel.cancel(); }).start();
    try {
      Provider.chat(cfg(s, "k", s.fingerprint), TURNS, true, null, cancel);
      T.check("cancel throws", false);
    } catch (ProviderError e) {
      T.eq("cancel categorised", ProviderError.Category.CANCELLED, e.category);
    }

    // Error classification.
    s.on("/v1/chat/completions", ex -> TestServer.reply(ex, 401, "{\"error\":\"bad key sekret-token-1\"}"));
    try {
      Provider.chat(cfg(s, "sekret-token-1", s.fingerprint), TURNS, false, null, null);
    } catch (ProviderError e) {
      T.eq("401 -> AUTH", ProviderError.Category.AUTH, e.category);
      T.check("AUTH message has no key", !e.getMessage().contains("sekret"));
    }
    s.on("/v1/chat/completions", ex -> TestServer.reply(ex, 404, "{\"error\":{\"message\":\"model 'nope' not found\"}}"));
    T.eq("404 model -> MODEL_UNAVAILABLE", ProviderError.Category.MODEL_UNAVAILABLE, cat(cfg(s, "k", s.fingerprint), false));
    s.on("/v1/chat/completions", ex -> TestServer.reply(ex, 400, "{\"error\":\"unknown field reasoning_effort\"}"));
    T.eq("400 -> UNSUPPORTED", ProviderError.Category.UNSUPPORTED, cat(cfg(s, "k", s.fingerprint), false));
    s.on("/v1/chat/completions", ex -> TestServer.reply(ex, 429, "{}"));
    T.eq("429 -> RATE_LIMIT", ProviderError.Category.RATE_LIMIT, cat(cfg(s, "k", s.fingerprint), false));
    s.on("/v1/chat/completions", ex -> TestServer.reply(ex, 503, "{}"));
    T.eq("503 -> SERVER", ProviderError.Category.SERVER, cat(cfg(s, "k", s.fingerprint), false));
    s.on("/v1/chat/completions", ex -> TestServer.reply(ex, 200, "not json at all"));
    T.eq("garbage -> MALFORMED", ProviderError.Category.MALFORMED, cat(cfg(s, "k", s.fingerprint), false));
    s.on("/v1/chat/completions", ex -> TestServer.reply(ex, 200, "{\"choices\":[{\"message\":{\"content\":\"\"}}]}"));
    T.eq("empty text -> MALFORMED", ProviderError.Category.MALFORMED, cat(cfg(s, "k", s.fingerprint), false));
    s.on("/v1/chat/completions", ex -> TestServer.reply(ex, 200, "{\"choices\":[{\"message\":{\"content\":\"" + "x".repeat(2_200_000) + "\"}}]}"));
    T.eq("huge -> TOO_LARGE", ProviderError.Category.TOO_LARGE, cat(cfg(s, "k", s.fingerprint), false));
    s.on("/v1/chat/completions", ex -> { try { Thread.sleep(2500); } catch (Exception e) { } TestServer.reply(ex, 200, ok); });
    Provider.Cfg slow = cfg(s, "k", s.fingerprint);
    slow.readMs = 600;
    try {
      Provider.chat(slow, TURNS, false, null, null);
    } catch (ProviderError e) {
      T.eq("slow -> TIMEOUT", ProviderError.Category.TIMEOUT, e.category);
      T.check("timeout warns it may have been processed", e.mayHaveBeenProcessed && e.getMessage().contains("may still have processed"));
    }
    Provider.Cfg dead = cfg(s, "k", s.fingerprint);
    dead.base = "https://127.0.0.1:1/v1";
    T.eq("closed port -> UNREACHABLE", ProviderError.Category.UNREACHABLE, cat(dead, false));

    // Redirects are refused and the key never reaches the redirect target.
    TestServer other = new TestServer(Files.createTempDirectory("vbtest2"));
    other.on("/steal", ex -> TestServer.reply(ex, 200, ok));
    s.on("/v1/chat/completions", ex -> { ex.getResponseHeaders().add("Location", other.base("/steal")); ex.sendResponseHeaders(307, -1); ex.close(); });
    T.eq("redirect refused", ProviderError.Category.REDIRECT, cat(cfg(s, "sekret-token-1", s.fingerprint), false));
    T.eq("redirect target never contacted", 0, other.seen.size());

    // Models discovery, with and without it being supported.
    s.on("/v1/models", ex -> TestServer.reply(ex, 200, "{\"data\":[{\"id\":\"alpha\"},{\"id\":\"beta\"}]}"));
    T.eq("models listed", Arrays.asList("alpha", "beta"), Provider.models(cfg(s, "k", s.fingerprint)));
    s.on("/v1/models", ex -> TestServer.reply(ex, 404, "{}"));
    try {
      Provider.models(cfg(s, "k", s.fingerprint));
      T.check("models 404 throws", false);
    } catch (ProviderError e) {
      T.check("unsupported discovery is an error the UI turns into manual entry", e.category == ProviderError.Category.NOT_FOUND);
    }
    // Ollama probe.
    s.on("/api/version", ex -> TestServer.reply(ex, 200, "{\"version\":\"0.9.9\"}"));
    s.on("/api/show", ex -> TestServer.reply(ex, 200, "{\"capabilities\":[\"completion\",\"tools\"],\"model_info\":{\"general.architecture\":\"llama\",\"llama.context_length\":8192}}"));
    JSONObject info = Provider.ollamaInfo(cfg(s, "k", s.fingerprint));
    T.eq("ollama detected", "ollama", info.optString("server"));
    T.eq("ollama context length", 8192L, info.optLong("context_length"));
    T.eq("ollama tools capability advertised", "tools", info.getJSONArray("capabilities").getString(1));
    s.on("/api/version", ex -> TestServer.reply(ex, 404, "{}"));
    T.eq("non-ollama server gives null, not an error", null, Provider.ollamaInfo(cfg(s, "k", s.fingerprint)));

    // Images are refused unless the profile explicitly enables them.
    Provider.Cfg ic = cfg(s, "k", s.fingerprint);
    List<Rules.Turn> img = Arrays.asList(new Rules.Turn("user", "look", Arrays.asList("data:image/png;base64,AAAA")));
    try {
      Provider.chat(ic, img, false, null, null);
      T.check("images refused when disabled", false);
    } catch (IllegalArgumentException e) {
      T.check("images refused when disabled", true);
    }
    s.stop();
    other.stop();
    T.done();
  }
}
