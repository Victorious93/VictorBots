import com.victorious.bots.*;
import java.io.IOException;
import java.util.*;
import org.json.*;

/** Victorious Framework: the COMPANION_COMMS client's rules with a fake transport. The Android transport itself is not run here. */
public final class CommsClientTest {
  static final class Fake implements CommsClient.Transport {
    final List<String> sent = new ArrayList<>();
    Exception fail;
    String reply = "{\"v\":1,\"ok\":true,\"state\":\"completed\",\"result\":{}}";

    @Override
    public String exchange(String json, long timeoutMs) throws Exception {
      sent.add(json);
      if (fail != null) throw fail;
      return reply;
    }
  }

  static final String RID = "req-00000001-abcdef";

  public static void main(String[] a) throws Exception {
    Fake t = new Fake();
    long[] now = {1_800_000_000_000L};
    CommsClient c = new CommsClient(t, () -> now[0]);

    // ---- what is sent ----
    t.reply = "{\"v\":1,\"ok\":true,\"capabilities\":[{\"id\":\"communications.read_conversation\",\"status\":\"available\"},{\"id\":\"communications.send_sms\",\"status\":\"unavailable\",\"reason\":\"VictorSMS does not provide this through the assistant connector.\"}]}";
    CommsCodec.Reply d = c.discover();
    JSONObject dj = new JSONObject(t.sent.get(0));
    T.eq("discover task", "discover", dj.getString("task"));
    T.eq("discover version range", 1, dj.getInt("min_v"));
    T.check("discover request id is well formed", dj.getString("request_id").matches("vb-[0-9a-f]{24}"));
    T.check("discovery is parsed", d.parsed && d.ok && d.supports("communications.read_conversation"));

    t.sent.clear();
    t.reply = "{\"v\":1,\"ok\":true,\"state\":\"completed\",\"request_id\":\"" + RID + "\",\"result\":{\"count\":1}}";
    Map<String, String> args = new LinkedHashMap<>();
    args.put("conversation", "0123456789abcdef0123456789abcdef");
    args.put("limit", "20");
    CommsCodec.Reply r1 = c.call(RID, "communications.read_conversation", args, "bot-1", "approval", "owner asked");
    CommsCodec.Reply r2 = c.call(RID, "communications.read_conversation", args, "bot-1", "approval", "owner asked");
    JSONObject c1 = new JSONObject(t.sent.get(0)), c2 = new JSONObject(t.sent.get(1));
    T.check("a nonce is 32 lowercase hex characters", c1.getString("nonce").matches("[0-9a-f]{32}"));
    T.check("a fresh nonce for every send, even for the same request id", !c1.getString("nonce").equals(c2.getString("nonce")));
    T.eq("timestamp comes from the clock", now[0], c1.getLong("ts"));
    T.eq("it expires after the ttl", now[0] + CommsCodec.TTL_MS, c1.getLong("expires_at"));
    T.eq("arguments are sent as given", "20", c1.getJSONObject("args").getString("limit"));
    T.eq("the bot is named", "bot-1", c1.getJSONObject("actor").getString("bot_id"));
    T.eq("the mode is a claim", "approval", c1.getJSONObject("actor").getString("mode"));
    T.check("a reply for this request is accepted", r1.parsed && r1.ok && "completed".equals(r1.state));
    T.eq("completed is what it says", Jobs.State.COMPLETED, CommsCodec.stateFor(r2));

    // ---- nothing is retried, nothing is invented ----
    Fake bad = new Fake();
    CommsClient cb = new CommsClient(bad, () -> now[0]);
    bad.fail = new CommsClient.NotSentException("DroidCommand certificate does not match the configured pin.");
    CommsCodec.Reply ns = cb.call(RID, "communications.send_sms", args, null, null, "");
    T.eq("not sent: one attempt only", 1, bad.sent.size());
    T.eq("not sent is labelled as such", "not_sent", ns.transport);
    T.eq("a request that never left goes back to a draft", Jobs.State.DRAFT, CommsCodec.stateFor(ns));
    T.check("the owner is told nothing was sent", CommsCodec.describe(ns).contains("Nothing was sent") && CommsCodec.describe(ns).contains("pin"));

    bad.sent.clear();
    bad.fail = new CommsClient.NoReplyException("No reply within 30 s.");
    CommsCodec.Reply nr = cb.call(RID, "communications.send_sms", args, null, null, "");
    T.eq("no reply: still one attempt, never resent", 1, bad.sent.size());
    T.eq("no reply is labelled as such", "no_reply", nr.transport);
    T.eq("a request that may have run is an unknown outcome", Jobs.State.UNKNOWN, CommsCodec.stateFor(nr));
    T.check("the owner is told it may have run", CommsCodec.describe(nr).contains("may have run"));

    bad.sent.clear();
    bad.fail = new IOException("connection reset");
    CommsCodec.Reply io = cb.call(RID, "communications.send_sms", args, null, null, "");
    T.eq("a failure after hand-over is also an unknown outcome", "no_reply", io.transport);
    T.check("the raw exception text is not shown as if droidcommand-AI said it", !CommsCodec.describe(io).contains("connection reset"));

    bad.sent.clear();
    bad.fail = null;
    CommsCodec.Reply wrongCap = cb.call(RID, "shell.exec", args, null, null, "");
    CommsCodec.Reply wrongId = cb.call("x", "communications.send_sms", args, null, null, "");
    T.eq("a non-communications capability is refused before anything is sent", "not_sent", wrongCap.transport);
    T.eq("a malformed request id is refused before anything is sent", "not_sent", wrongId.transport);
    T.eq("neither reached the transport", 0, bad.sent.size());

    // ---- a reply for something else is never applied ----
    bad.reply = "{\"v\":1,\"ok\":true,\"state\":\"completed\",\"request_id\":\"someone-elses-request\",\"result\":{}}";
    CommsCodec.Reply foreign = cb.call(RID, "communications.send_sms", args, null, null, "");
    T.eq("another request's answer is discarded", false, foreign.parsed);
    T.eq("and justifies no state", null, CommsCodec.stateFor(foreign));
    bad.reply = null;
    T.eq("no text at all is not an answer", false, cb.call(RID, "communications.send_sms", args, null, null, "").parsed);

    // ---- what the owner reads ----
    CommsCodec.Reply np = CommsCodec.parse("{\"v\":1,\"ok\":false,\"state\":\"refused\",\"code\":\"not_paired\",\"detail\":\"Pair this app in droidcommand-AI first.\"}");
    T.check("not paired says where to fix it", CommsCodec.describe(np).contains("Connected apps") && CommsCodec.describe(np).contains("Nothing ran"));
    String ok = CommsCodec.describe(d);
    T.check("discovery counts what is available", ok.contains("1 of 2"));
    T.check("discovery names what is not, with the reason", ok.contains("send_sms") && ok.contains("does not provide this"));
    T.check("it never claims anything was sent", ok.contains("nothing has been sent"));
    T.check("an unreadable reply is said to be unreadable", CommsCodec.describe(CommsCodec.parse("???")).contains("could not read"));
    T.check("a version mismatch names both ranges", CommsCodec.describe(CommsCodec.parse("{\"v\":1,\"ok\":false,\"code\":\"incompatible_version\",\"versions\":{\"min\":2,\"max\":3}}")).contains("2 to 3"));

    // ---- the list of shared conversations, in words ----
    CommsCodec.Reply two = CommsCodec.parse("{\"v\":1,\"ok\":true,\"state\":\"completed\",\"result\":{\"conversations\":[{\"id\":\"a\",\"title\":\"Sam\",\"message_count\":42},{\"id\":\"b\",\"title\":\"\",\"message_count\":3}],\"count\":2}}");
    String shown = CommsCodec.describeConversations(two);
    T.check("it names the shared conversations with their sizes", shown.contains("2 conversations") && shown.contains("Sam · 42 messages") && shown.contains("(no title) · 3 messages"));
    T.check("it says nothing was sent or changed", shown.contains("nothing was sent or changed"));
    String longTitle = "x".repeat(200);
    CommsCodec.Reply wide = CommsCodec.parse("{\"v\":1,\"ok\":true,\"state\":\"completed\",\"result\":{\"conversations\":[{\"id\":\"a\",\"title\":\"" + longTitle + "\",\"message_count\":1}],\"count\":1}}");
    T.check("a very long title is clipped, not dumped on the screen", !CommsCodec.describeConversations(wide).contains("x".repeat(100)));
    CommsCodec.Reply none = CommsCodec.parse("{\"v\":1,\"ok\":true,\"state\":\"completed\",\"result\":{\"conversations\":[],\"count\":0}}");
    T.check("an empty list says how to share one", CommsCodec.describeConversations(none).contains("Let my assistant read this conversation"));
    CommsCodec.Reply denied = CommsCodec.parse("{\"v\":1,\"ok\":false,\"state\":\"refused\",\"code\":\"not_granted\",\"detail\":\"Not granted: read_messages.\"}");
    T.check("a missing grant says where the owner allows it", CommsCodec.describeConversations(denied).contains("Allow an app") && CommsCodec.describeConversations(denied).contains("Nothing was read"));
    T.check("a refusal from the other app is passed on in words", CommsCodec.describeConversations(CommsCodec.parse("{\"v\":1,\"ok\":false,\"state\":\"refused\",\"code\":\"backend_unavailable\",\"detail\":\"VictorSMS has not granted droidcommand-AI the Archive read permission.\"}")).contains("Archive read permission"));
    T.check("nothing sent is reported as such", CommsCodec.describeConversations(CommsCodec.notSent("pin")).contains("Nothing was sent"));
    T.check("a list of the wrong shape is not pretended to be one", CommsCodec.describeConversations(CommsCodec.parse("{\"v\":1,\"ok\":true,\"state\":\"completed\",\"result\":{\"x\":1}}")).contains("not with a conversation list"));

    // ---- through the jobs, like every other backend report ----
    Fakes.Env e = Fakes.env();
    Jobs jobs = new Jobs(e.db);
    long j = jobs.create("Text John", e.bot, 0, 0, "DCA", e.dca, "communications.send_sms", new JSONObject(), 0, 0);
    jobs.markSubmitting(j);
    T.eq("nothing left the app: back to a draft", true, CommsCodec.apply(jobs, j, ns));
    T.eq("the job is a draft again", Jobs.State.DRAFT, jobs.get(j).state);
    long k = jobs.create("Text Sam", e.bot, 0, 0, "DCA", e.dca, "communications.send_sms", new JSONObject(), 0, 0);
    jobs.markSubmitting(k);
    T.eq("no reply: recorded as unknown", true, CommsCodec.apply(jobs, k, nr));
    T.eq("outcome unknown", Jobs.State.UNKNOWN, jobs.get(k).state);
    T.eq("an unknown job is never re-sent", false, CommsCodec.apply(jobs, k, nr));
    long m = jobs.create("Text Ann", e.bot, 0, 0, "DCA", e.dca, "communications.send_sms", new JSONObject(), 0, 0);
    T.eq("a draft that was never sent is not 'sent back' to a draft", false, CommsCodec.apply(jobs, m, ns));
    jobs.markSubmitting(m);
    CommsCodec.apply(jobs, m, CommsCodec.parse("{\"v\":1,\"ok\":true,\"state\":\"completed\",\"result\":{}}"));
    T.eq("a completed job is not pulled back by a late not-sent report", false, CommsCodec.apply(jobs, m, ns));
    T.eq("it stays completed", Jobs.State.COMPLETED, jobs.get(m).state);
    T.done();
  }
}
