import com.victorious.bots.*;
import com.victorious.bots.Diagnostics.*;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;

public final class DiagnosticsTest {
  public static void main(String[] a) throws Exception {
    TestServer s = new TestServer(Files.createTempDirectory("vbdiag"));
    Fakes.Env e = Fakes.env();
    e.gateway = new Gateway(e.db, e.profiles, e.secrets, Gateway.LIVE); // real client, real TLS
    Diagnostics d = new Diagnostics(e.db, e.profiles, e.secrets, e.gateway);
    String chat = "{\"model\":\"served-model-v2\",\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":24,\"completion_tokens\":1,\"total_tokens\":25}}";
    s.on("/v1/models", ex -> TestServer.reply(ex, 200, "{\"data\":[{\"id\":\"my-model\"},{\"id\":\"other\"}]}"));
    s.on("/v1/chat/completions", ex -> TestServer.reply(ex, 200, chat));
    long pc = e.profiles.save(0, "Test PC", Policy.Kind.PC, s.base("/v1"), "my-model", s.fingerprint, null);

    // Passive: saved but no token yet -> flagged; nothing contacted.
    Report r0 = d.passive(e.profiles.get(pc));
    T.eq("PC without token is flagged at the saved step", State.FAILED, r0.saved.state);
    T.eq("passive check contacted nothing", 0, s.seen.size());
    e.secrets.save(pc, e.profiles.get(pc).binding(), "tok-abcdef");
    T.eq("with token: saved OK", State.OK, d.passive(e.profiles.get(pc)).saved.state);

    // Probe: reachable + auth + model listed, but NO claim of a real reply.
    Report r1 = d.probe(e.profiles.get(pc));
    T.eq("reachable", State.OK, r1.reachable.state);
    T.eq("auth accepted", State.OK, r1.auth.state);
    T.eq("model listed", State.OK, r1.model.state);
    T.eq("probe alone does not claim a reply", State.NOT_RUN, r1.reply.state);
    T.eq("probe sent the token only to this host", "Bearer tok-abcdef", s.seen.get(0).auth);
    T.check("probe sent no chat content", s.seen.stream().noneMatch(x -> x.path.endsWith("/chat/completions")));
    T.eq("models count recorded", 2, e.profiles.get(pc).tested.getJSONObject("models").getInt("count"));

    // Model absent from the list.
    e.db.update("UPDATE profiles SET model='ghost' WHERE id=?", pc);
    T.eq("unlisted model flagged", State.FAILED, d.probe(e.profiles.get(pc)).model.state);
    e.db.update("UPDATE profiles SET model='my-model' WHERE id=?", pc);

    // Live test: real reply with measured, reported values.
    Report r2 = d.liveTest(e.profiles.get(pc), false);
    T.eq("real reply received", State.OK, r2.reply.state);
    T.eq("requested model shown", "my-model", r2.requestedModel);
    T.eq("response-reported model shown", "served-model-v2", r2.reportedModel);
    T.eq("tokens in reported", (Long) 24L, r2.inTokens);
    T.check("latency measured", r2.latencyMs >= 0);
    T.check("first token labelled not measured when not streaming", r2.usageLine().contains("not measured"));
    T.eq("text capability recorded as tested", true, e.profiles.get(pc).tested.getJSONObject("text").getBoolean("ok"));
    T.check("live test warning names destination and cost caveat", Diagnostics.liveTestWarning(e.profiles.get(pc), Policy.Mode.ALLOW_CLOUD).contains("127.0.0.1"));

    // Streaming live test records first-token time and tests streaming.
    s.on("/v1/chat/completions", ex -> TestServer.sse(ex, Arrays.asList("{\"choices\":[{\"delta\":{\"content\":\"o\"}}]}", "{\"choices\":[{\"delta\":{\"content\":\"k\"}}]}", "[DONE]"), 80));
    Report r3 = d.liveTest(e.profiles.get(pc), true);
    T.eq("stream reply", State.OK, r3.reply.state);
    T.check("first token measured", r3.firstTokenMs >= 60);
    T.eq("usage missing in stream is unknown, not zero", null, r3.inTokens);
    T.check("usage line says unknown", r3.usageLine().contains("unknown"));
    T.eq("streaming recorded as tested", true, e.profiles.get(pc).tested.getJSONObject("streaming").getBoolean("ok"));

    // Wrong credentials: host reachable, auth failed, no real reply, key not echoed.
    s.on("/v1/models", ex -> TestServer.reply(ex, 401, "{\"error\":\"invalid key tok-abcdef\"}"));
    s.on("/v1/chat/completions", ex -> TestServer.reply(ex, 401, "{\"error\":\"invalid key tok-abcdef\"}"));
    Report r4 = d.liveTest(e.profiles.get(pc), false);
    T.eq("reachable despite 401", State.OK, r4.reachable.state);
    T.eq("auth failed", State.FAILED, r4.auth.state);
    T.eq("reply failed", State.FAILED, r4.reply.state);
    StringBuilder all = new StringBuilder();
    for (Step st : r4.steps()) all.append(st.detail);
    T.check("no secret in any diagnostic text", !all.toString().contains("tok-abcdef"));

    // Unsupported model discovery: manual entry path, not a failure of the connection.
    s.on("/v1/models", ex -> TestServer.reply(ex, 404, "{}"));
    Report r5 = d.probe(e.profiles.get(pc));
    T.eq("no discovery -> model unknown, enter manually", State.UNKNOWN, r5.model.state);
    T.check("says to type the model id", r5.model.detail.contains("manually"));

    // Unreachable and wrong-pin.
    e.profiles.save(pc, "Test PC", Policy.Kind.PC, "https://127.0.0.1:1/v1", "my-model", s.fingerprint, null);
    T.eq("endpoint edit dropped the old key", false, e.secrets.has(pc));
    T.eq("no key -> probe refuses to call the PC anonymously", State.NOT_RUN, d.probe(e.profiles.get(pc)).reachable.state);
    e.secrets.save(pc, e.profiles.get(pc).binding(), "tok-abcdef");
    T.eq("unreachable", State.FAILED, d.probe(e.profiles.get(pc)).reachable.state);
    e.profiles.save(pc, "Test PC", Policy.Kind.PC, s.base("/v1"), "my-model", "AB".repeat(32), null);
    e.secrets.save(pc, e.profiles.get(pc).binding(), "tok-abcdef");
    Report r6 = d.probe(e.profiles.get(pc));
    T.eq("wrong pin fails at the TLS stage", State.FAILED, r6.reachable.state);
    T.check("says TLS", r6.reachable.detail.contains("TLS"));
    e.profiles.save(pc, "Test PC", Policy.Kind.PC, s.base("/v1"), "my-model", s.fingerprint, null);

    // Policy: a cloud profile under PC_ONLY is never contacted, not even for a probe.
    long cloud = e.profiles.save(0, "Cloud on test host", Policy.Kind.CLOUD, s.base("/v1"), "m", "", null);
    e.db.update("UPDATE profiles SET pin=? WHERE id=?", s.fingerprint, cloud); // pin only so the test server is trusted
    e.secrets.save(cloud, Hosts.origin(s.base("/v1")), "cloud-key-xx");
    e.kv.put(Profiles.GLOBAL_POLICY, Policy.Mode.PC_ONLY.id);
    int before = s.seen.size();
    Report r7 = d.liveTest(e.profiles.get(cloud), false);
    T.eq("blocked by policy", State.BLOCKED, r7.reachable.state);
    T.eq("reply step blocked too", State.BLOCKED, r7.reply.state);
    T.eq("no request reached the server", before, s.seen.size());
    e.kv.put(Profiles.GLOBAL_POLICY, Policy.Mode.ALLOW_CLOUD.id);

    // DroidCommand: passive only; never claims reachability.
    Report r8 = d.probe(e.profiles.get(e.dca));
    T.eq("DroidCommand reachability is not claimed", State.UNKNOWN, r8.reachable.state);

    // Usage/cost labelling.
    Profile sub = new Profile(1, "S", Policy.Kind.CLOUD, "https://x.example/v1", "m", "", new JSONObject("{\"billing\":\"subscription\",\"price_in_per_m\":3,\"price_out_per_m\":15}"), new JSONObject());
    String subLabel = UsageStats.costLabel(sub, 1000L, 500L);
    T.check("subscription estimate is labelled not a charge", subLabel.contains("NOT a charge") && subLabel.contains("API-equivalent"));
    Profile metered = new Profile(1, "M", Policy.Kind.CLOUD, "https://x.example/v1", "m", "", new JSONObject("{\"price_in_per_m\":3,\"price_out_per_m\":15}"), new JSONObject());
    T.check("metered estimate is labelled estimate", UsageStats.costLabel(metered, 1000L, 500L).startsWith("Estimated cost"));
    T.check("unknown usage gives no cost", UsageStats.costLabel(metered, null, null).contains("cannot be estimated"));
    T.check("no rates, no guess", UsageStats.costLabel(new Profile(1, "N", Policy.Kind.CLOUD, "https://x.example/v1", "m", "", new JSONObject(), new JSONObject()), 5L, 5L).contains("not estimated"));
    T.check("self hosted has no per-token charge", UsageStats.costLabel(e.profiles.get(pc), 5L, 5L).contains("No per-token charge"));
    T.check("totals do not count unreported usage as zero", UsageStats.totals(e.db).contains("did not report usage"));

    // Capability report keeps configured / advertised / tested apart.
    Profile pr = e.profiles.get(pc);
    List<CapabilityReport.Row> rows = CapabilityReport.build(pr);
    boolean toolsRow = false;
    for (CapabilityReport.Row row : rows) if (row.name.equals("Tool calls")) toolsRow = row.configured.equals("off") && row.advertised.contains("not reported") && row.tested.contains("not tested");
    T.eq("tools: off, not advertised, not tested - three separate answers", true, toolsRow);
    s.stop();
    T.done();
  }
}
