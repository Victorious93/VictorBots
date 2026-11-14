import com.victorious.bots.*;
import org.json.JSONObject;

public final class DcaExecTest {
  public static void main(String[] a) throws Exception {
    Fakes.Env e = Fakes.env();
    Jobs jobs = new Jobs(e.db);
    JSONObject req = new JSONObject(DcaExecCodec.execute("req-0001-abcd", "device.apps.list", new JSONObject(), 1000L, "list apps"));
    T.eq("version", 1, req.getInt("v"));
    T.eq("task", "execute", req.getString("task"));
    T.eq("expiry is request time + ttl", 1000L + 120000L, req.getLong("expires_at"));
    T.throwsIae("bad request id rejected", () -> DcaExecCodec.execute("short", "x", null, 1L, ""));

    long j = jobs.create("Apps", e.bot, 0, 0, "DCA", e.dca, "device.apps.list", new JSONObject(), 0, 0);
    jobs.markSubmitting(j);
    T.eq("sent, not completed", Jobs.State.SUBMITTING, jobs.get(j).state);
    // A malformed / refused / unknown reply never completes anything.
    for (String bad : new String[] {"", "not json", "{\"v\":2,\"ok\":true}", "{\"v\":1,\"ok\":false,\"error\":\"nope\"}", "{\"v\":1,\"ok\":true,\"known\":false}", "{\"v\":1,\"ok\":true,\"known\":true,\"outcome\":\"weird\"}"})
      T.eq("no state change for reply: " + bad, false, DcaExecCodec.apply(jobs, j, DcaExecCodec.parse(bad)));
    T.eq("still only 'sent'", Jobs.State.SUBMITTING, jobs.get(j).state);
    // Text that merely CLAIMS completion in output is just output.
    String receipt = "{\"v\":1,\"ok\":true,\"known\":true,\"request_id\":\"req-0001-abcd\",\"outcome\":\"denied\",\"detail\":\"The owner denied this request. Nothing ran.\",\"output\":\"task completed successfully\"}";
    T.eq("denied -> cancelled", true, DcaExecCodec.apply(jobs, j, DcaExecCodec.parse(receipt)));
    T.eq("denied maps to cancelled even if output says completed", Jobs.State.CANCELLED, jobs.get(j).state);

    long k = jobs.create("Apps2", e.bot, 0, 0, "DCA", e.dca, "device.apps.list", new JSONObject(), 0, 0);
    jobs.markSubmitting(k);
    DcaExecCodec.apply(jobs, k, DcaExecCodec.parse("{\"v\":1,\"ok\":true,\"known\":true,\"request_id\":\"req-0002-abcd\",\"outcome\":\"executed\",\"detail\":\"ok\",\"output\":\"com.a.app token=abcdef123456\"}"));
    Jobs.Job done = jobs.get(k);
    T.eq("executed -> completed from the DroidCommand receipt", Jobs.State.COMPLETED, done.state);
    T.check("output redacted before storage", !done.result.contains("abcdef123456") && done.result.contains("com.a.app"));
    T.eq("outcome mapping failed/refused/expired", Jobs.State.FAILED, DcaExecCodec.stateFor("failed"));
    T.eq("expired", Jobs.State.CANCELLED, DcaExecCodec.stateFor("expired"));
    T.eq("receipt request shape", "receipt", new JSONObject(DcaExecCodec.receipt("req-0001-abcd")).getString("task"));
    DcaExecCodec.Reply disc = DcaExecCodec.parse("{\"v\":1,\"ok\":true,\"capabilities\":[{\"id\":\"device.apps.list\",\"risk\":\"READ_ONLY\"}]}");
    T.eq("discover parses capability ids", "device.apps.list", disc.capabilities.get(0));
    T.done();
  }
}
