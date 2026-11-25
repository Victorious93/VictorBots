import com.victorious.bots.*;
import java.util.*;
import org.json.*;

/** Victorious Framework: regression tests for the comms review findings (comms-review.md F1-F6, F26). */
public final class CommsReviewFixTest {
  static final String RID = "req-00000001-abcdef";

  static final class Fake implements CommsClient.Transport {
    String reply;
    int sends;

    @Override
    public String exchange(String json, long timeoutMs) {
      sends++;
      return reply;
    }
  }

  public static void main(String[] a) throws Exception {
    Fake t = new Fake();
    CommsClient c = new CommsClient(t, () -> 1_800_000_000_000L);
    Map<String, String> args = new LinkedHashMap<>();
    args.put("limit", "10");

    // F1: a call reply that names no request is not applied.
    t.reply = "{\"v\":1,\"ok\":true,\"state\":\"completed\",\"result\":{}}";
    CommsCodec.Reply noId = c.call(RID, "communications.list_conversations", args, null, null, "");
    T.eq("F1 a completed reply without request_id is not accepted", false, noId.parsed);
    T.eq("F2 it is reported as an unknown outcome", "no_reply", noId.transport);
    T.eq("F2 one send only", 1, t.sends);
    // F2: someone else's reply is an unknown outcome, not an empty reply.
    t.reply = "{\"v\":1,\"ok\":true,\"state\":\"completed\",\"request_id\":\"other-request-1\",\"result\":{}}";
    T.eq("F2 a foreign reply becomes outcome unknown", Jobs.State.UNKNOWN, CommsCodec.stateFor(c.call(RID, "communications.list_conversations", args, null, null, "")));
    // A protocol error without an id (droidcommand-AI could not read one) is still shown.
    t.reply = "{\"v\":1,\"ok\":false,\"state\":\"error\",\"code\":\"invalid_request\",\"detail\":\"Bad.\"}";
    CommsCodec.Reply err = c.call(RID, "communications.list_conversations", args, null, null, "");
    T.check("an id-less protocol error is passed on", err.parsed && "invalid_request".equals(err.code));
    T.eq("and justifies no job state", null, CommsCodec.stateFor(err));
    // Discovery replies carry no request_id in droidcommand-AI; they are still read.
    t.reply = "{\"v\":1,\"ok\":true,\"capabilities\":[]}";
    T.check("discovery without request_id is still accepted", c.discover().parsed);
    t.reply = "{\"v\":1,\"ok\":true,\"request_id\":\"someone-else-1\",\"capabilities\":[]}";
    T.eq("discovery naming another request is refused", "no_reply", c.discover().transport);
    T.check("belongsTo: exact id", CommsClient.belongsTo(CommsCodec.parse("{\"v\":1,\"ok\":true,\"request_id\":\"" + RID + "\"}"), RID, true));

    // F3: versions outside 1..1 are a mismatch, never read as v1.
    CommsCodec.Reply v2 = CommsCodec.parse("{\"v\":2,\"ok\":true,\"state\":\"completed\",\"request_id\":\"" + RID + "\",\"result\":{}}");
    T.check("F3 a v2 reply is not ok", !v2.ok);
    T.check("F3 it is reported as incompatible", v2.incompatible());
    T.eq("F3 and justifies no state", null, CommsCodec.stateFor(v2));
    T.check("F3 the owner is told which versions", CommsCodec.describe(v2).contains("2 to 2"));
    T.eq("F3 a string v is not a version", false, CommsCodec.parse("{\"v\":\"1\",\"ok\":true}").parsed);

    // F4: JSON null is empty, not the word "null".
    CommsCodec.Reply nul = CommsCodec.parse("{\"v\":1,\"ok\":false,\"state\":null,\"code\":null,\"detail\":null,\"request_id\":null,\"capabilities\":[{\"id\":null,\"status\":\"available\"},{\"id\":\"communications.x\",\"status\":\"unavailable\",\"reason\":null}]}");
    T.eq("F4 null code is empty", "", nul.code);
    T.eq("F4 null state is empty", "", nul.state);
    T.eq("F4 null request id is empty", "", nul.requestId);
    T.check("F4 a null capability id is skipped", !nul.capabilities.containsKey("null") && nul.capabilities.size() == 1);
    T.eq("F4 a null reason reads as unavailable", "unavailable", nul.capabilities.get("communications.x"));

    // F5: one malformed capability entry does not discard the rest.
    CommsCodec.Reply mixed = CommsCodec.parse("{\"v\":1,\"ok\":true,\"capabilities\":[\"junk\",42,{\"id\":\"communications.list_conversations\",\"status\":\"available\"}]}");
    T.check("F5 the reply is still read", mixed.parsed);
    T.check("F5 the good entry survives", mixed.supports("communications.list_conversations"));

    // F6: reason never ends in half a surrogate pair; a null argument is refused before sending.
    String emoji = new String(new char[199]).replace('\0', 'a') + "\uD83D\uDE00tail";
    String sent = CommsCodec.call(RID, "0123456789abcdef0123456789abcdef", 1L, "communications.list_conversations", args, null, null, emoji);
    String reason = new JSONObject(sent).getString("reason");
    T.check("F6 reason is clipped to 200 or fewer", reason.length() <= 200);
    T.check("F6 reason does not end in a lone high surrogate", !Character.isHighSurrogate(reason.charAt(reason.length() - 1)));
    T.eq("F6 clip keeps short text", "abc", CommsCodec.clip("abc", 200));
    Map<String, String> bad = new HashMap<>();
    bad.put("limit", null);
    t.sends = 0;
    CommsCodec.Reply refused = c.call(RID, "communications.list_conversations", bad, null, null, "");
    T.eq("F6 a null argument value is refused", "not_sent", refused.transport);
    T.eq("F6 nothing reached the transport", 0, t.sends);

    // F26: apply() checks the id; pending_approval needs ok=true.
    Fakes.Env e = Fakes.env();
    Jobs jobs = new Jobs(e.db);
    long j = jobs.create("Read", e.bot, 0, 0, "DCA", e.dca, "communications.list_conversations", new JSONObject(), 0, 0);
    jobs.markSubmitting(j);
    T.eq("F26 a reply for another request changes nothing", false, CommsCodec.apply(jobs, j, RID, CommsCodec.parse("{\"v\":1,\"ok\":true,\"state\":\"completed\",\"request_id\":\"other-request-1\",\"result\":{}}")));
    T.eq("F26 a reply with no id changes nothing", false, CommsCodec.apply(jobs, j, RID, CommsCodec.parse("{\"v\":1,\"ok\":true,\"state\":\"completed\",\"result\":{}}")));
    T.eq("F26 nor does a null expected id", false, CommsCodec.apply(jobs, j, null, CommsCodec.parse("{\"v\":1,\"ok\":true,\"state\":\"completed\",\"request_id\":\"" + RID + "\",\"result\":{}}")));
    T.eq("F26 the job is untouched", Jobs.State.SUBMITTING, jobs.get(j).state);
    T.eq("F26 contradictory pending_approval (ok=false) justifies nothing", null, CommsCodec.stateFor(CommsCodec.parse("{\"v\":1,\"ok\":false,\"state\":\"pending_approval\",\"request_id\":\"" + RID + "\"}")));
    T.eq("F26 the matching reply applies", true, CommsCodec.apply(jobs, j, RID, CommsCodec.parse("{\"v\":1,\"ok\":true,\"state\":\"completed\",\"request_id\":\"" + RID + "\",\"result\":{}}")));
    T.eq("F26 completed", Jobs.State.COMPLETED, jobs.get(j).state);
    T.done();
  }
}
