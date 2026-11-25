import com.victorious.bots.*;
import java.util.*;
import java.util.concurrent.*;

/** Victorious Framework: one outstanding request (review F7) and collecting a pending outcome (review F11). */
public final class CommsGateTest {
  public static void main(String[] a) throws Exception {
    // ---- F7: a second request while one is in flight is refused before it reaches any transport ----
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    List<String> sent = Collections.synchronizedList(new ArrayList<>());
    CommsClient slow = new CommsClient((json, timeout) -> {
      sent.add(json);
      entered.countDown();
      release.await(10, TimeUnit.SECONDS);
      return "{\"v\":1,\"ok\":true,\"capabilities\":[]}";
    });
    CommsClient other = new CommsClient((json, timeout) -> { sent.add(json); return "{\"v\":1,\"ok\":true,\"capabilities\":[]}"; });
    ExecutorService ex = Executors.newSingleThreadExecutor();
    Future<CommsCodec.Reply> first = ex.submit(slow::discover);
    T.check("first request reached the transport", entered.await(5, TimeUnit.SECONDS));
    T.check("busy while it is outstanding", CommsClient.busy());
    CommsCodec.Reply second = other.discover();
    T.eq("F7 a second request (another client) is refused", "not_sent", second.transport);
    T.check("F7 and says why", second.detail.contains("still waiting"));
    T.eq("F7 it never reached a transport", 1, sent.size());
    release.countDown();
    T.check("the first one completes normally", first.get(5, TimeUnit.SECONDS).parsed);
    T.check("not busy afterwards", !CommsClient.busy());
    T.check("the next request is allowed", other.discover().parsed);
    CommsClient boom = new CommsClient((json, timeout) -> { throw new RuntimeException("x"); });
    T.eq("a failing transport is an unknown outcome", "no_reply", boom.discover().transport);
    T.check("and releases the gate", !CommsClient.busy());
    ex.shutdownNow();

    // ---- F11: pending approval -> the same request id again; running -> a receipt ----
    List<org.json.JSONObject> calls = new ArrayList<>();
    String[] replies = new String[1];
    CommsClient c = new CommsClient((json, timeout) -> {
      org.json.JSONObject o = new org.json.JSONObject(json);
      calls.add(o);
      return replies[0].replace("$ID", o.getString("request_id"));
    });
    CommsFollowUp f = new CommsFollowUp();
    Map<String, String> args = new LinkedHashMap<>();
    args.put("limit", "10");
    replies[0] = "{\"v\":1,\"ok\":true,\"state\":\"pending_approval\",\"request_id\":\"$ID\"}";
    CommsCodec.Reply p = f.ask(c, "communications.list_conversations", args, "r");
    T.check("pending is told in words", CommsCodec.describe(p).contains("Waiting for your approval"));
    String id1 = calls.get(0).getString("request_id");
    T.eq("F11 the waiting id is remembered", id1, f.waitingId());
    replies[0] = "{\"v\":1,\"ok\":true,\"state\":\"running\",\"request_id\":\"$ID\",\"job_id\":\"j1\"}";
    f.ask(c, "communications.list_conversations", args, "r");
    T.eq("F11 the next tap re-sends the same request id", id1, calls.get(1).getString("request_id"));
    T.eq("F11 as a call", "call", calls.get(1).getString("task"));
    T.check("F11 with a fresh nonce", !calls.get(0).getString("nonce").equals(calls.get(1).getString("nonce")));
    replies[0] = "{\"v\":1,\"ok\":true,\"state\":\"completed\",\"request_id\":\"$ID\",\"result\":{\"conversations\":[]}}";
    CommsCodec.Reply done = f.ask(c, "communications.list_conversations", args, "r");
    T.eq("F11 while running the next tap asks for the receipt", "receipt", calls.get(2).getString("task"));
    T.eq("F11 of the same request", id1, calls.get(2).getString("request_id"));
    T.check("the outcome arrives", "completed".equals(done.state));
    T.eq("nothing waits any more", null, f.waitingId());
    f.ask(c, "communications.list_conversations", args, "r");
    T.check("after completion a new request id is used", !id1.equals(calls.get(3).getString("request_id")));
    replies[0] = "{\"v\":1,\"ok\":true,\"state\":\"pending_approval\",\"request_id\":\"$ID\"}";
    f.ask(c, "communications.list_conversations", args, "r");
    Map<String, String> args2 = new LinkedHashMap<>();
    args2.put("limit", "20");
    f.ask(c, "communications.list_conversations", args2, "r");
    T.check("different arguments never reuse a waiting id", !calls.get(4).getString("request_id").equals(calls.get(5).getString("request_id")));
    T.done();
  }
}
