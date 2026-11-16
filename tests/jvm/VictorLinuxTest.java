import com.sun.net.httpserver.HttpExchange;
import com.victorious.bots.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** The optional VictorLinux preset: URLs, save validation, capability discovery, runner split, pin binding. */
public final class VictorLinuxTest {
  static final String PIN_A = "AB".repeat(32), PIN_B = "CD".repeat(32), PIN_C = "EF".repeat(32);

  static String msg(T.Thrower t) {
    try {
      t.run();
      return null;
    } catch (Exception e) {
      return String.valueOf(e.getMessage());
    }
  }

  static boolean says(String name, T.Thrower t, String... parts) {
    String m = msg(t);
    boolean ok = m != null;
    for (String p : parts) ok = ok && m.toLowerCase(Locale.ROOT).contains(p.toLowerCase(Locale.ROOT));
    if (!ok) System.out.println("  message was: " + m);
    T.check(name, ok);
    return ok;
  }

  static long vl(Fakes.Env e, String name, String host, String port, String pin, String token) {
    return e.profiles.saveVictorLinux(0, name, host, port, "victor-local", pin, token, "", "", "", null);
  }

  public static void main(String[] args) throws Exception {
    urls();
    saveValidation();
    pinBinding();
    migration();
    capabilitiesParsing();
    discoveryOverTls();
    runnerSeparation();
    separateRunnerOverTls();
    backupAndStandalone();
    T.done();
  }

  // ------------------------------------------------------------------ 1. URL building
  static void urls() {
    T.eq("default port 8080", "https://pc.lan:8080/v1", VictorLinux.baseUrl("pc.lan", ""));
    T.eq("blank port == default", "https://pc.lan:8080/v1", VictorLinux.baseUrl("pc.lan", "  "));
    T.eq("custom port", "https://192.168.1.50:9443/v1", VictorLinux.baseUrl("192.168.1.50", "9443"));
    T.eq("host is lower-cased and trimmed", "https://box.ts.net:8080/v1", VictorLinux.baseUrl("  BOX.ts.net ", ""));
    T.eq("ipv6 literal is bracketed", "https://[fd00::1]:8080/v1", VictorLinux.baseUrl("fd00::1", ""));
    T.eq("chat endpoint", "https://pc.lan:8080/v1/chat/completions", Hosts.chatUrl(VictorLinux.baseUrl("pc.lan", "")));
    T.eq("models endpoint", "https://pc.lan:8080/v1/models", Hosts.modelsUrl(VictorLinux.baseUrl("pc.lan", "")));
    T.eq("capabilities endpoint", "https://pc.lan:8080/victor/capabilities", VictorLinux.capabilitiesUrl(VictorLinux.baseUrl("pc.lan", "")));
    T.eq("capabilities endpoint ignores base path", "https://pc.lan:8443/victor/capabilities", VictorLinux.capabilitiesUrl("https://pc.lan:8443/some/v1"));
    T.eq("prefilled model", "victor-local", VictorLinux.DEFAULT_MODEL);
    T.eq("port parse of stored base", 9443, VictorLinux.portOf("https://pc.lan:9443/v1"));

    for (String bad : new String[] {"", "   ", "http://pc.lan", "HTTP://pc.lan", "https://pc.lan", "ftp://pc.lan", "pc.lan:8080", "pc.lan/v1", "pc lan", "user@pc.lan", "pc.lan?x=1", "pc.lan#f", "pc..lan", "-pc.lan", "pc_lan", "[pc.lan]", "gg::zz"})
      T.throwsIae("host refused: '" + bad + "'", () -> VictorLinux.baseUrl(bad, ""));
    says("http:// host is refused with an HTTPS explanation", () -> VictorLinux.host("http://pc.lan"), "https only");
    says("pasted host:port points at the Port field", () -> VictorLinux.host("pc.lan:8080"), "port");
    for (String bad : new String[] {"0", "65536", "99999", "abc", "80a", "-1", "8 0", "123456"})
      T.throwsIae("port refused: '" + bad + "'", () -> VictorLinux.port(bad));
    T.eq("port 65535 ok", 65535, VictorLinux.port("65535"));
    T.eq("port 1 ok", 1, VictorLinux.port("1"));
    // Whatever the preset builds still obeys Hosts.base (https only).
    T.eq("built base is already normalised", VictorLinux.baseUrl("pc.lan", ""), Hosts.base(VictorLinux.baseUrl("pc.lan", "")));
  }

  // ------------------------------------------------------------------ 2. save validation
  static void saveValidation() throws Exception {
    Fakes.Env e = Fakes.env();
    int before = e.profiles.all().size();

    says("no token -> refused", () -> vl(e, "VictorLinux", "pc.lan", "", PIN_A, ""), "token");
    says("null token -> refused", () -> vl(e, "VictorLinux", "pc.lan", "", PIN_A, null), "token");
    says("no pin -> refused", () -> vl(e, "VictorLinux", "pc.lan", "", "", "tok-1234567890"), "fingerprint");
    says("blank pin -> refused", () -> vl(e, "VictorLinux", "pc.lan", "", "   ", "tok-1234567890"), "fingerprint");
    says("malformed pin -> refused", () -> vl(e, "VictorLinux", "pc.lan", "", "12:34", "tok-1234567890"), "64 hexadecimal");
    says("http:// -> refused", () -> vl(e, "VictorLinux", "http://pc.lan", "", PIN_A, "tok-1234567890"), "https");
    says("other URL forms -> refused", () -> vl(e, "VictorLinux", "https://pc.lan:8080/v1", "", PIN_A, "tok-1234567890"), "host");
    says("bad port -> refused", () -> vl(e, "VictorLinux", "pc.lan", "70000", PIN_A, "tok-1234567890"), "port");
    says("blank model -> refused", () -> e.profiles.saveVictorLinux(0, "VictorLinux", "pc.lan", "", "  ", PIN_A, "tok-1234567890", "", "", "", null), "model");
    says("blank name -> refused", () -> e.profiles.saveVictorLinux(0, "  ", "pc.lan", "", "m", PIN_A, "tok-1234567890", "", "", "", null), "name");
    T.eq("nothing was written by any refused save", before, e.profiles.all().size());

    long id = vl(e, "VictorLinux", "PC.lan", "", PIN_A.toLowerCase(Locale.ROOT), "tok-1234567890");
    Profile p = e.profiles.get(id);
    T.eq("kind is PC", Policy.Kind.PC, p.kind);
    T.eq("base built from host and default port", "https://pc.lan:8080/v1", p.base);
    T.eq("model prefilled", "victor-local", p.model);
    T.eq("pin stored normalised", PIN_A, p.pin);
    T.check("marked as the preset", p.isVictorLinux());
    T.eq("billing defaults to own hardware", "self_hosted", p.caps.optString("billing"));
    T.eq("token stored under origin+pin binding", "tok-1234567890", e.secrets.read(id, "https://pc.lan:8080|pin=" + PIN_A));
    T.eq("token is NOT readable under the bare origin", false, e.secrets.usable(id, "https://pc.lan:8080"));
    T.eq("config for requests", "https://pc.lan:8080/v1", p.cfg("k").base);
    T.eq("pin reaches the TLS layer", PIN_A, p.cfg("k").pin);
    T.eq("model editable", "my-model", e.profiles.get(e.profiles.saveVictorLinux(id, "VictorLinux", "pc.lan", "", "my-model", PIN_A, "", "", "", "", null)).model);
    T.eq("blank token keeps the stored one when address and pin are unchanged", true, e.secrets.usable(id, e.profiles.get(id).binding()));
    T.throwsIae("duplicate name refused", () -> vl(e, "victorlinux", "other.lan", "", PIN_A, "tok-abcdefghij"));
    T.throwsIae("a normal PC profile cannot be edited through the preset", () -> e.profiles.saveVictorLinux(e.pc, "My PC", "pc.lan", "", "m", PIN_A, "tok-1234567890", "", "", "", null));
    T.throwsIae("a cloud profile cannot be edited through the preset", () -> e.profiles.saveVictorLinux(e.cloud, "x", "pc.lan", "", "m", PIN_A, "tok-1234567890", "", "", "", null));

    // The generic save path enforces the same invariants when it sees the preset marker.
    JSONObject marker = new JSONObject().put("preset", "victorlinux");
    T.throwsIae("preset without a pin refused even via Profiles.save", () -> e.profiles.save(0, "Sneaky", Policy.Kind.PC, "https://x.lan:8080/v1", "m", "", marker));
    T.throwsIae("preset on a cloud profile refused", () -> e.profiles.save(0, "Sneaky2", Policy.Kind.CLOUD, "https://x.example/v1", "m", "", marker));
    T.throwsIae("http base refused by the shared rule", () -> e.profiles.save(0, "Sneaky3", Policy.Kind.PC, "http://x.lan:8080/v1", "m", PIN_A, marker));

    // End to end through the gateway: the request carries the token for exactly this binding.
    e.db.update("UPDATE bots SET profile=?,policy='' WHERE id=?", id, e.bot);
    e.kv.put(Profiles.GLOBAL_POLICY, Policy.Mode.PC_ONLY.id);
    Gateway.Req r = new Gateway.Req();
    r.purpose = Policy.Purpose.CHAT;
    r.botId = e.bot;
    r.turns = Arrays.asList(new Rules.Turn("system", "s"), new Rules.Turn("user", "hello world this is text"));
    e.db.update("UPDATE bots SET model='' WHERE id=?", e.bot);
    e.gateway.run(r);
    T.eq("gateway sends to the preset endpoint with its token and model", "https://pc.lan:8080/v1|tok-1234567890|my-model", e.calls.get(e.calls.size() - 1));
  }

  // ------------------------------------------------------------------ 5. pin change invalidates the key binding
  static void pinBinding() throws Exception {
    T.eq("pc binding without a pin is the origin (unchanged)", "https://pc.lan:8443", Profile.binding(Policy.Kind.PC, "https://pc.lan:8443/v1", ""));
    T.eq("pc binding with a pin includes it", "https://pc.lan:8443|pin=" + PIN_A, Profile.binding(Policy.Kind.PC, "https://pc.lan:8443/v1", PIN_A));
    T.eq("cloud binding ignores the pin (unchanged)", "https://api.example.com:443", Profile.binding(Policy.Kind.CLOUD, "https://api.example.com/v1", PIN_A));
    T.check("different pins give different bindings", !Profile.binding(Policy.Kind.PC, "https://pc.lan:8443/v1", PIN_A).equals(Profile.binding(Policy.Kind.PC, "https://pc.lan:8443/v1", PIN_B)));

    Fakes.Env e = Fakes.env();
    long id = vl(e, "VictorLinux", "pc.lan", "", PIN_A, "old-token-123456");
    String oldBinding = e.profiles.get(id).binding();
    T.eq("token readable for pin A", "old-token-123456", e.secrets.read(id, oldBinding));

    says("changing the pin without a token is refused", () -> e.profiles.saveVictorLinux(id, "VictorLinux", "pc.lan", "", "victor-local", PIN_B, "", "", "", "", null), "enter the access token again");
    T.eq("the refused edit changed nothing", PIN_A, e.profiles.get(id).pin);
    T.eq("old token still intact after refused edit", "old-token-123456", e.secrets.read(id, oldBinding));

    e.profiles.saveVictorLinux(id, "VictorLinux", "pc.lan", "", "victor-local", PIN_B, "new-token-654321", "", "", "", null);
    String newBinding = e.profiles.get(id).binding();
    T.check("binding changed with the pin", !oldBinding.equals(newBinding));
    T.eq("new token readable for pin B", "new-token-654321", e.secrets.read(id, newBinding));
    T.throwsIae("old binding is refused outright", () -> e.secrets.read(id, oldBinding));

    // Generic edit path (a plain PC profile with a pin): changing only the pin drops the stored key.
    long gp = e.profiles.save(0, "Plain PC", Policy.Kind.PC, "https://plain.lan:8443/v1", "m", PIN_A, null);
    e.secrets.save(gp, e.profiles.get(gp).binding(), "plain-token-1234");
    e.profiles.save(gp, "Plain PC", Policy.Kind.PC, "https://plain.lan:8443/v1", "m", PIN_B, null);
    T.eq("pin-only change on a plain PC profile removes the key", false, e.secrets.has(gp));
    e.secrets.save(gp, e.profiles.get(gp).binding(), "plain-token-1234");
    e.profiles.save(gp, "Plain PC renamed", Policy.Kind.PC, "https://plain.lan:8443/v1", "other-model", PIN_B, null);
    T.eq("rename/model edit keeps the key", true, e.secrets.usable(gp, e.profiles.get(gp).binding()));
    e.profiles.save(gp, "Plain PC renamed", Policy.Kind.PC, "https://plain.lan:8443/v1", "other-model", "", null);
    T.eq("removing the pin also removes the key (PC-and-pin identity changed)", false, e.secrets.has(gp));
    // A cloud profile is untouched by pin logic.
    T.eq("cloud key unaffected", true, e.secrets.usable(e.cloud, "https://api.example.com:443"));
    e.profiles.save(e.cloud, "OpenAI-style", Policy.Kind.CLOUD, "https://api.example.com/v1", "other", "", null);
    T.eq("cloud edit on same origin keeps its key (unchanged behaviour)", true, e.secrets.has(e.cloud));
  }

  // ------------------------------------------------------------------ 4. migration of keys stored before pins were bound
  static void migration() throws Exception {
    Fakes.Env e = Fakes.env();
    // A 0.2.0 PC profile with a pin whose token was stored under the bare origin.
    long old = e.profiles.save(0, "Pinned PC (0.2.0)", Policy.Kind.PC, "https://old.lan:8443/v1", "m", PIN_A, null);
    e.secrets.save(old, "https://old.lan:8443", "legacy-token-1234");
    Profile p = e.profiles.get(old);
    T.eq("legacy key is present", true, e.secrets.has(old));
    T.eq("but it is not usable under the new binding", false, e.secrets.usable(old, p.binding()));
    T.throwsIae("reading it refuses (no crash, no plaintext)", () -> e.secrets.read(old, p.binding()));
    e.profiles.setDefault(old);
    e.db.update("UPDATE bots SET profile=?,policy='' WHERE id=?", old, e.bot);
    e.kv.put(Profiles.GLOBAL_POLICY, Policy.Mode.PC_ONLY.id);
    Gateway.Req r = new Gateway.Req();
    r.purpose = Policy.Purpose.CHAT;
    r.botId = e.bot;
    r.turns = Arrays.asList(new Rules.Turn("system", "s"), new Rules.Turn("user", "hello world this is text"));
    e.calls.clear();
    String denied = null;
    try {
      e.gateway.run(r);
    } catch (Gateway.Denied d) {
      denied = d.getMessage();
    }
    T.check("gateway refuses and asks for the key again", denied != null && denied.toLowerCase(Locale.ROOT).contains("enter the key again"));
    T.eq("  nothing was sent", 0, e.calls.size());
    Diagnostics d = new Diagnostics(e.db, e.profiles, e.secrets, e.gateway);
    Diagnostics.Report rep = d.passive(p);
    T.eq("diagnostics flags it at the saved step", Diagnostics.State.FAILED, rep.saved.state);
    T.check("diagnostics says to enter it again", rep.saved.detail.contains("enter it again"));
    says("runner client refuses with a re-enter message", () -> RunnerClient.forProfile(p, e.secrets), "no longer matches");
    T.eq("runner not offered as ready until re-entered", false, e.profiles.runnerReady(p));
    // Re-entering under the new binding works and nothing else changed.
    e.secrets.save(old, p.binding(), "fresh-token-5678");
    T.eq("re-entered token usable", true, e.secrets.usable(old, p.binding()));
    T.eq("runner ready after re-entry", true, e.profiles.runnerReady(e.profiles.get(old)));
    // PC profiles without a pin (the common 0.2.0 case) need no migration at all.
    T.eq("pin-less PC key written by 0.2.0 still usable", true, e.secrets.usable(e.pc, e.profiles.get(e.pc).binding()));
  }

  // ------------------------------------------------------------------ 3a. capabilities JSON parsing
  static void capabilitiesParsing() {
    VictorLinux.Capabilities c = VictorLinux.parse("{\"tool_calls\":true}");
    T.check("top-level tool_calls true", c.ok && Boolean.TRUE.equals(c.toolCalls));
    c = VictorLinux.parse("{\"capabilities\":{\"tool_calls\":true,\"vision\":false,\"streaming\":true}}");
    T.check("nested capabilities object", Boolean.TRUE.equals(c.toolCalls));
    T.check("only truthy features listed", c.features.contains("streaming") && c.features.contains("tool_calls") && !c.features.contains("vision"));
    c = VictorLinux.parse("{\"capabilities\":[\"chat\",\"Tools\"]}");
    T.check("capabilities array containing tools", Boolean.TRUE.equals(c.toolCalls));
    c = VictorLinux.parse("{\"features\":{\"function_calling\":{\"supported\":true}}}");
    T.check("supported-object form", Boolean.TRUE.equals(c.toolCalls));
    c = VictorLinux.parse("{\"supports\":{\"tool-calling\":\"yes\"}}");
    T.check("string yes and dashed key", Boolean.TRUE.equals(c.toolCalls));
    c = VictorLinux.parse("{\"tool_calls\":false}");
    T.check("explicit false is reported as false", c.ok && Boolean.FALSE.equals(c.toolCalls));
    T.check("false describes as not supported and leaves the switch", c.describe().contains("not supported") && c.describe().contains("left as you had it"));
    c = VictorLinux.parse("{\"capabilities\":[\"chat\"]}");
    T.check("array without tools is unknown, not false", c.ok && c.toolCalls == null);
    c = VictorLinux.parse("{\"name\":\"VictorLinux\",\"version\":\"1.2\",\"models\":[\"victor-local\",{\"id\":\"second\"},7,null],\"context_length\":32768,\"unknown_field\":{\"x\":[1,2,3]},\"tools\":[\"a\",\"b\"]}");
    T.check("unknown fields ignored, rest parsed", c.ok && c.name.equals("VictorLinux") && c.version.equals("1.2") && c.contextLength == 32768 && c.models.equals(Arrays.asList("victor-local", "second")));
    T.check("a tools list is not mistaken for support", c.toolCalls == null);
    T.check("describe names what was detected", c.describe().contains("VictorLinux 1.2") && c.describe().contains("32768") && c.describe().contains("not reported"));
    c = VictorLinux.parse("{\"tool_calls\":\"maybe\",\"models\":5,\"context_length\":\"lots\",\"capabilities\":7,\"name\":{\"a\":1}}");
    T.check("wrong types are ignored", c.ok && c.toolCalls == null && c.models.isEmpty() && c.contextLength == 0);
    c = VictorLinux.parse("{\"context_length\":-5}");
    T.eq("negative context ignored", 0L, c.contextLength);
    c = VictorLinux.parse("{\"name\":\"Evil\\u0007\\nname" + "x".repeat(300) + "\"}");
    T.check("control characters stripped and length bounded", c.name.length() <= 80 && c.name.chars().allMatch(ch -> ch >= 32));
    for (String bad : new String[] {"", "   ", "not json", "[]", "[1,2]", "\"str\"", "null", "{", "<html>", "{\"a\":"}) {
      VictorLinux.Capabilities b = VictorLinux.parse(bad);
      T.check("garbage rejected softly: '" + bad + "'", !b.ok && !b.error.isEmpty() && b.toolCalls == null && b.describe().contains("by hand"));
    }
    T.check("null body does not throw", !VictorLinux.parse(null).ok);
    T.check("advertised() carries tools only when the server said so", VictorLinux.parse("{\"tool_calls\":true}").advertised().optJSONArray("capabilities").toString().contains("tools"));
    T.check("advertised() empty when the server said nothing", VictorLinux.parse("{\"name\":\"x\"}").advertised() == null);
    T.check("advertised() does not claim tools on false", !VictorLinux.parse("{\"tool_calls\":false}").advertised().optJSONArray("capabilities").toString().contains("tools"));
    // The advertisement feeds the existing report as "advertised", never as "tested".
    JSONObject tested = new JSONObject().put("advertised", VictorLinux.parse("{\"tool_calls\":true}").advertised());
    Profile p = new Profile(1, "x", Policy.Kind.PC, "https://x.lan:8080/v1", "m", PIN_A, new JSONObject(), tested);
    CapabilityReport.Row tools = null;
    for (CapabilityReport.Row row : CapabilityReport.build(p)) if (row.name.equals("Tool calls")) tools = row;
    T.check("report shows advertised yes", tools != null && tools.advertised.startsWith("yes"));
    T.check("report still says not tested and configured off", tools != null && tools.tested.startsWith("not tested") && tools.configured.equals("off"));
  }

  // ------------------------------------------------------------------ 3b. discovery over real pinned TLS
  static void discoveryOverTls() throws Exception {
    Path dir = Files.createTempDirectory("vltest");
    TestServer s = new TestServer(dir);
    String base = s.base("/v1");
    s.on("/victor/capabilities", ex -> TestServer.reply(ex, 200, "{\"name\":\"VictorLinux\",\"capabilities\":{\"tool_calls\":true},\"models\":[\"victor-local\"]}"));

    Provider.Cfg cfg = VictorLinux.discoveryCfg("127.0.0.1", "" + s.port, s.fingerprint, "cap-token-12345");
    T.eq("discovery config targets the same origin", Hosts.origin(base), Hosts.origin(cfg.base));
    VictorLinux.Capabilities c = VictorLinux.fetch(cfg);
    T.check("discovery succeeded and found tool calls", c.ok && Boolean.TRUE.equals(c.toolCalls));
    T.eq("one request, GET to the fixed path", "GET /victor/capabilities", s.seen.get(s.seen.size() - 1).method + " " + s.seen.get(s.seen.size() - 1).path);
    T.eq("Bearer token sent", "Bearer cap-token-12345", s.seen.get(s.seen.size() - 1).auth);
    T.eq("exactly one request", 1, s.seen.size());

    // Wrong pin: TLS failure becomes a note, not an exception, and no request is served.
    s.seen.clear();
    VictorLinux.Capabilities wp = VictorLinux.fetch(VictorLinux.discoveryCfg("127.0.0.1", "" + s.port, PIN_B, "cap-token-12345"));
    T.check("wrong pin -> non-fatal note", !wp.ok && wp.error.contains("TLS") && wp.describe().contains("by hand"));
    T.eq("no request reached the handler with a wrong pin", 0, s.seen.size());
    T.check("token is not in the note", !wp.describe().contains("cap-token-12345"));

    // Missing token / pin: refused before any network.
    T.throwsIae("discovery without token refused", () -> VictorLinux.discoveryCfg("127.0.0.1", "" + s.port, s.fingerprint, ""));
    T.throwsIae("discovery without pin refused", () -> VictorLinux.discoveryCfg("127.0.0.1", "" + s.port, "", "tok"));
    T.throwsIae("discovery over http refused", () -> VictorLinux.discoveryCfg("http://127.0.0.1", "" + s.port, s.fingerprint, "tok"));
    Provider.Cfg raw = new Provider.Cfg();
    raw.base = base;
    raw.key = "tok";
    T.check("fetch without a pin makes no request", !VictorLinux.fetch(raw).ok && s.seen.isEmpty());

    // HTTP errors.
    s.on("/victor/capabilities", ex -> TestServer.reply(ex, 401, "{\"error\":\"nope cap-token-12345\"}"));
    VictorLinux.Capabilities un = VictorLinux.fetch(cfg);
    T.check("401 -> note about credentials, token redacted", !un.ok && un.error.toLowerCase(Locale.ROOT).contains("rejected") && !un.error.contains("cap-token-12345"));
    s.on("/victor/capabilities", ex -> TestServer.reply(ex, 404, "{\"error\":\"not found\"}"));
    T.check("404 -> note (older gateway without discovery)", !VictorLinux.fetch(cfg).ok);
    s.on("/victor/capabilities", ex -> TestServer.reply(ex, 200, "<html>not json</html>"));
    T.check("malformed 200 -> note", !VictorLinux.fetch(cfg).ok);
    s.on("/victor/capabilities", ex -> TestServer.reply(ex, 200, "x".repeat(200_000)));
    T.check("oversized body bounded -> note", !VictorLinux.fetch(cfg).ok);

    // Redirects are refused and never followed.
    s.seen.clear();
    s.on("/victor/capabilities", ex -> {
      ex.getResponseHeaders().add("Location", s.base("/elsewhere"));
      ex.sendResponseHeaders(302, -1);
      ex.close();
    });
    s.on("/elsewhere", ex -> TestServer.reply(ex, 200, "{\"tool_calls\":true}"));
    VictorLinux.Capabilities rd = VictorLinux.fetch(cfg);
    T.check("redirect -> refused note", !rd.ok && rd.error.toLowerCase(Locale.ROOT).contains("redirect"));
    boolean followed = false;
    for (TestServer.Seen x : s.seen) if (x.path.equals("/elsewhere")) followed = true;
    T.eq("redirect target never requested", false, followed);
    T.eq("only the one request was made", 1, s.seen.size());

    // Finite timeout.
    s.on("/victor/capabilities", ex -> {
      try {
        Thread.sleep(3000);
      } catch (InterruptedException ignored) {
      }
      TestServer.reply(ex, 200, "{}");
    });
    long t0 = System.currentTimeMillis();
    VictorLinux.Capabilities to = VictorLinux.fetch(cfg, 1000, 400);
    long took = System.currentTimeMillis() - t0;
    T.check("slow server -> timeout note, bounded wait (" + took + " ms)", !to.ok && to.error.toLowerCase(Locale.ROOT).contains("timed out") && took < 2500);

    // Only the capabilities path was ever touched during discovery: no chat, no tools.
    boolean other = false;
    for (TestServer.Seen x : s.seen) if (!x.path.equals("/victor/capabilities") && !x.path.equals("/elsewhere")) other = true;
    T.eq("discovery never touched chat/tool endpoints", false, other);
    // Unreachable host: note, no exception.
    Provider.Cfg dead = VictorLinux.discoveryCfg("127.0.0.1", "1", PIN_A, "tok");
    T.check("unreachable -> note, no exception", !VictorLinux.fetch(dead, 1000, 1000).ok);
    s.stop();
  }

  // ------------------------------------------------------------------ 3. runner separation and fallback
  static void runnerSeparation() throws Exception {
    Fakes.Env e = Fakes.env();
    // Default (ordinary) PC profile: unchanged layout and credentials.
    Profile plain = e.profiles.get(e.pc);
    Profile.Runner pr = plain.runner();
    T.eq("ordinary PC profile keeps /runner/v1 beside the API", "https://192.168.1.20:8443/runner/v1", pr.base);
    T.eq("ordinary PC runner base == Hosts.runnerBase", Hosts.runnerBase(plain.base), pr.base);
    T.eq("ordinary PC runner shares the profile's secret slot", plain.id, pr.secretId);
    T.eq("ordinary PC runner shares the binding", plain.binding(), pr.binding);
    T.eq("ordinary PC runner is not 'separate'", false, pr.separate);
    T.eq("ordinary PC profile is not the preset", false, plain.isVictorLinux());
    T.eq("cloud profile has no runner", null, e.profiles.get(e.cloud).runner());

    // VictorLinux without a runner URL.
    long id = vl(e, "VictorLinux", "vl.lan", "", PIN_A, "vl-token-1234567");
    Profile p = e.profiles.get(id);
    T.eq("preset with no runner URL has no runner", null, p.runner());
    says("RunnerClient refuses with the exact message", () -> new RunnerClient(p, "x"), "Runner not configured for this profile");
    says("forProfile refuses with the exact message", () -> RunnerClient.forProfile(p, e.secrets), "Runner not configured for this profile");
    T.eq("exact message constant", "Runner not configured for this profile", VictorLinux.NO_RUNNER);
    T.eq("not ready", false, e.profiles.runnerReady(p));
    T.eq("the inference host is not guessed as a runner", null, p.runner());
    // Jobs/routines use the first PC profile that HAS a runner; the plain PC (older id) wins here.
    T.eq("runnerProfile prefers a profile with a runner", e.pc, e.profiles.runnerProfile().id);
    e.profiles.delete(e.pc);
    T.eq("with only a runner-less preset, runnerProfile returns it so the UI can explain", id, e.profiles.runnerProfile().id);
    T.eq("byKind unchanged semantics (first PC)", id, e.profiles.byKind(Policy.Kind.PC).id);

    // Same-origin runner URL: shares token and pin with the PC.
    long same = e.profiles.saveVictorLinux(id, "VictorLinux", "vl.lan", "", "victor-local", PIN_A, "", "https://vl.lan:8080/runner/v1/", "", "", null);
    Profile ps = e.profiles.get(same);
    T.eq("same-origin runner URL is normalised", "https://vl.lan:8080/runner/v1", ps.caps.optString("runner_url"));
    Profile.Runner rs = ps.runner();
    T.check("same origin shares credentials", rs != null && !rs.separate && rs.secretId == id && rs.binding.equals(ps.binding()) && rs.pin.equals(PIN_A));
    T.eq("runner base is exactly what the owner typed (no /runner/v1 guessing)", "https://vl.lan:8080/runner/v1", rs.base);
    T.eq("ready: token readable", true, e.profiles.runnerReady(ps));
    T.eq("no stray runner_pin stored for same origin", "", ps.caps.optString("runner_pin"));

    // Separate-origin runner: needs its own pin and token; never gets the PC token.
    says("separate runner without its own pin refused", () -> e.profiles.saveVictorLinux(id, "VictorLinux", "vl.lan", "", "victor-local", PIN_A, "", "https://vl.lan:8443/runner/v1", "", "rt-123456789", null), "fingerprint");
    says("separate runner without its own token refused", () -> e.profiles.saveVictorLinux(id, "VictorLinux", "vl.lan", "", "victor-local", PIN_A, "", "https://vl.lan:8443/runner/v1", PIN_B, "", null), "own access token");
    says("http runner refused", () -> e.profiles.saveVictorLinux(id, "VictorLinux", "vl.lan", "", "victor-local", PIN_A, "", "http://vl.lan:8443/runner/v1", PIN_B, "rt-123456789", null), "https");
    T.eq("refused runner edits left the profile as it was", "https://vl.lan:8080/runner/v1", e.profiles.get(id).caps.optString("runner_url"));
    e.profiles.saveVictorLinux(id, "VictorLinux", "vl.lan", "", "victor-local", PIN_A, "", "https://vl.lan:8443/runner/v1", PIN_B, "rt-123456789", null);
    Profile pr2 = e.profiles.get(id);
    Profile.Runner rr = pr2.runner();
    T.check("separate runner is flagged", rr.separate && rr.secretId == -id && rr.pin.equals(PIN_B));
    T.check("runner binding has its own origin and pin", rr.binding.equals("https://vl.lan:8443|pin=" + PIN_B));
    T.eq("runner token stored in its own slot", "rt-123456789", e.secrets.read(-id, rr.binding));
    T.eq("PC token untouched", "vl-token-1234567", e.secrets.read(id, pr2.binding()));
    T.check("tokens differ per slot", !e.secrets.read(-id, rr.binding).equals(e.secrets.read(id, pr2.binding())));
    T.eq("runner ready", true, e.profiles.runnerReady(pr2));
    T.eq("blank runner token keeps the stored one on an unrelated edit", true, e.profiles.runnerReady(e.profiles.get(e.profiles.saveVictorLinux(id, "VictorLinux", "vl.lan", "", "edited-model", PIN_A, "", "https://vl.lan:8443/runner/v1", PIN_B, "", null))));
    // Changing the runner's pin without a new token is refused; with one it is stored.
    says("runner pin change without token refused", () -> e.profiles.saveVictorLinux(id, "VictorLinux", "vl.lan", "", "victor-local", PIN_A, "", "https://vl.lan:8443/runner/v1", PIN_C, "", null), "own access token");
    e.profiles.saveVictorLinux(id, "VictorLinux", "vl.lan", "", "victor-local", PIN_A, "", "https://vl.lan:8443/runner/v1", PIN_C, "rt-new-987654321", null);
    T.eq("new runner token readable for new runner pin", "rt-new-987654321", e.secrets.read(-id, e.profiles.get(id).runner().binding));
    // Going back to same-origin (or no runner) drops the separate runner token.
    e.profiles.saveVictorLinux(id, "VictorLinux", "vl.lan", "", "victor-local", PIN_A, "", "", "", "", null);
    T.eq("clearing the runner URL removes its token", false, e.secrets.has(-id));
    T.eq("and the profile has no runner again", null, e.profiles.get(id).runner());
    T.eq("PC token still intact", true, e.secrets.usable(id, e.profiles.get(id).binding()));
    // Delete clears both slots.
    e.profiles.saveVictorLinux(id, "VictorLinux", "vl.lan", "", "victor-local", PIN_A, "", "https://other.lan:8443/runner/v1", PIN_B, "rt-zzzzzzzz1", null);
    T.eq("runner slot exists before delete", true, e.secrets.has(-id));
    e.profiles.delete(id);
    T.check("delete removes both tokens", !e.secrets.has(id) && !e.secrets.has(-id));
  }

  /** A real separate runner origin: it gets its own token and pin, and the PC server sees none of it. */
  static void separateRunnerOverTls() throws Exception {
    TestServer pcServer = new TestServer(Files.createTempDirectory("vltest-pc"));
    TestServer runner = new TestServer(Files.createTempDirectory("vltest-runner"));
    runner.on("/runner/v1/capabilities", ex -> TestServer.reply(ex, 200, "{\"protocol\":1,\"workspaces\":[],\"tools\":[]}"));

    Fakes.Env e = Fakes.env();
    long id = e.profiles.saveVictorLinux(0, "VL", "127.0.0.1", "" + pcServer.port, "victor-local", pcServer.fingerprint, "pc-secret-token-1", runner.base("/runner/v1"), runner.fingerprint, "runner-secret-tok-2", null);
    Profile p = e.profiles.get(id);
    T.check("runner is on a different origin", p.runner().separate);
    RunnerClient rc = RunnerClient.forProfile(p, e.secrets);
    JSONObject caps = rc.capabilities();
    T.eq("runner reached over its own pinned TLS", 1, caps.getInt("protocol"));
    T.eq("runner got the runner token", "Bearer runner-secret-tok-2", runner.seen.get(runner.seen.size() - 1).auth);
    T.eq("runner path is the one the owner configured", "/runner/v1/capabilities", runner.seen.get(runner.seen.size() - 1).path);
    T.eq("PC server saw no request at all", 0, pcServer.seen.size());
    for (TestServer.Seen x : runner.seen) T.check("runner never saw the PC token", x.auth == null || !x.auth.contains("pc-secret-token-1"));

    // The runner's pin is its own: a wrong runner pin fails TLS even though the PC pin is right.
    e.profiles.saveVictorLinux(id, "VL", "127.0.0.1", "" + pcServer.port, "victor-local", pcServer.fingerprint, "", runner.base("/runner/v1"), PIN_A, "runner-secret-tok-2", null);
    try {
      RunnerClient.forProfile(e.profiles.get(id), e.secrets).capabilities();
      T.check("wrong runner pin rejected", false);
    } catch (ProviderError x) {
      T.eq("wrong runner pin rejected", ProviderError.Category.TLS, x.category);
    }
    // No runner token stored -> refused without any request.
    e.secrets.remove(-id);
    int seen = runner.seen.size();
    says("missing runner token refused", () -> RunnerClient.forProfile(e.profiles.get(id), e.secrets), "runner", "anonymous");
    T.eq("no request made", seen, runner.seen.size());
    pcServer.stop();
    runner.stop();
  }

  // ------------------------------------------------------------------ backup round trip and the standalone guarantee
  static void backupAndStandalone() throws Exception {
    Fakes.Env a = Fakes.env();
    int others = a.profiles.all().size();
    long defaultBefore = a.profiles.defaultProfile().id;
    long id = a.profiles.saveVictorLinux(0, "VictorLinux", "vl.lan", "9443", "victor-local", PIN_A, "export-secret-token-1", "https://run.lan:8443/runner/v1", PIN_B, "export-runner-token-2", null);
    T.eq("adding the preset adds exactly one profile", others + 1, a.profiles.all().size());
    T.eq("existing profiles untouched: PC", "https://192.168.1.20:8443/v1", a.profiles.get(a.pc).base);
    T.eq("existing profiles untouched: PC key", "pc-token-5678", a.secrets.read(a.pc, a.profiles.get(a.pc).binding()));
    T.eq("existing profiles untouched: cloud key", "cloud-key-1234", a.secrets.read(a.cloud, "https://api.example.com:443"));
    T.eq("existing PC profile still has the historical runner", "https://192.168.1.20:8443/runner/v1", a.profiles.get(a.pc).runner().base);
    T.eq("saving the preset does not change the default profile", defaultBefore, a.profiles.defaultProfile().id);

    String json = Backup.export(a.db).toString();
    T.check("export carries no token", !json.contains("export-secret-token") && !json.contains("export-runner-token"));
    T.check("export keeps the preset marker and runner URL", json.contains("victorlinux") && json.contains("https://run.lan:8443/runner/v1"));
    Fakes.Env b = Fakes.env();
    Backup.Preview ap = Backup.apply(b.db, json, Backup.OnConflict.SKIP);
    T.check("restore ok: " + ap.errors, ap.ok());
    Profile q = b.profiles.all().stream().filter(x -> x.name.equals("VictorLinux")).findFirst().orElse(null);
    T.check("preset survives a restore", q != null && q.isVictorLinux() && q.pin.equals(PIN_A) && q.base.equals("https://vl.lan:9443/v1"));
    T.check("runner survives a restore as a separate runner with its own pin", q != null && q.runner().separate && q.runner().pin.equals(PIN_B));
    T.eq("restored profile has no token (must be re-entered)", false, q != null && b.secrets.has(q.id));

    // Hostile imports of the preset marker are rejected rather than downgraded.
    JSONObject root = new JSONObject(json);
    JSONArray profs = root.getJSONArray("profiles");
    for (int i = 0; i < profs.length(); i++) {
      JSONObject o = profs.getJSONObject(i);
      if (o.getString("name").equals("VictorLinux")) {
        o.put("name", "NoPin").put("pin", "");
      }
    }
    Fakes.Env c = Fakes.env();
    Backup.Preview bad = Backup.apply(c.db, root.toString(), Backup.OnConflict.SKIP);
    T.check("preset marker without a pin is rejected on import", !bad.ok() && bad.errors.toString().contains("pinned certificate"));
    for (int i = 0; i < profs.length(); i++) {
      JSONObject o = profs.getJSONObject(i);
      if (o.getString("name").equals("NoPin")) {
        o.put("name", "BadRunner").put("pin", PIN_A);
        o.getJSONObject("caps").put("runner_url", "http://run.lan:8443/runner/v1");
      }
    }
    Fakes.Env d = Fakes.env();
    Backup.Preview bad2 = Backup.apply(d.db, root.toString(), Backup.OnConflict.SKIP);
    T.check("http runner URL rejected on import", !bad2.ok());

    // Standalone: with no VictorLinux profile nothing in the profile model mentions it.
    Fakes.Env plain = Fakes.env();
    boolean anyPreset = false;
    for (Profile x : plain.profiles.all()) anyPreset |= x.isVictorLinux();
    T.eq("default install has no preset profile", false, anyPreset);
    T.eq("default install: runnerProfile == byKind(PC)", plain.profiles.byKind(Policy.Kind.PC).id, plain.profiles.runnerProfile().id);
    T.eq("default install: PC runner ready exactly as before", true, plain.profiles.runnerReady(plain.profiles.get(plain.pc)));
  }
}
