import com.victorious.bots.*;
import java.util.*;
import org.json.*;

/** Victorious Framework: communication-agent configuration, the COMPANION_COMMS client codec and migration v4. */
public final class CommsTest {
  static final String RID = "req-00000001-abcdef";
  static final String NONCE = "0123456789abcdef0123456789abcdef";

  public static void main(String[] a) throws Exception {
    Fakes.Env e = Fakes.env();
    CommsBots cb = new CommsBots(e.db);

    // ---- agent configuration ----
    CommsBots.Config d = cb.get(e.bot);
    T.eq("a new communications bot starts in Approval mode", CommsBots.Mode.APPROVAL, d.mode);
    T.eq("not paused, but not authorised for anything either", false, d.paused);
    T.check("disclosure and voice approval default on", d.disclose && d.voiceNeedsApproval);
    d.mode = CommsBots.Mode.AUTO;
    d.profileUid = "prof-1";
    d.contacts = Arrays.asList("+15550100101");
    d.hoursStart = 9; d.hoursEnd = 17; d.maxPerHour = 3;
    cb.save(d);
    CommsBots.Config back = cb.get(e.bot);
    T.check("round trip", back.mode == CommsBots.Mode.AUTO && back.profileUid.equals("prof-1") && back.contacts.equals(d.contacts) && back.maxPerHour == 3);
    T.throwsIae("invalid hours refused", () -> { CommsBots.Config x = cb.get(e.bot); x.hoursStart = 30; x.hoursEnd = 2; cb.save(x); });
    T.throwsIae("invalid rate refused", () -> { CommsBots.Config x = cb.get(e.bot); x.maxPerHour = 0; cb.save(x); });
    e.db.update("UPDATE comm_bots SET mode='ROOT_EVERYTHING' WHERE bot=?", e.bot);
    T.eq("an unreadable stored mode falls back to the safest one", CommsBots.Mode.APPROVAL, cb.get(e.bot).mode);
    back.mode = CommsBots.Mode.AUTO;
    cb.save(back);

    long bot2 = e.db.insert("INSERT INTO bots(name,description,instructions,avatar,uid) VALUES('B2','d','i','x','b2')");
    cb.assign(e.bot, "conv-1");
    cb.assign(e.bot, "conv-1");
    T.throwsIae("two bots cannot own one conversation", () -> { try { cb.assign(bot2, "conv-1"); } catch (IllegalStateException ex) { throw new IllegalArgumentException(ex.getMessage()); } });
    T.eq("owner recorded", (Long) e.bot, cb.botFor("conv-1"));
    cb.release("conv-1");
    cb.assign(bot2, "conv-1");
    T.eq("released conversation can move", (Long) bot2, cb.botFor("conv-1"));

    // advisory pre-checks: 12:00 UTC, bot working hours 9-17
    long noon = 1_800_000_000_000L - (1_800_000_000_000L % 86_400_000L) + 12 * 3_600_000L;
    T.eq("inside hours", true, cb.mayRequestSend(e.bot, noon, 0).allowed);
    T.eq("outside hours (UTC+12 is midnight)", false, cb.mayRequestSend(e.bot, noon, 720).allowed);
    for (int i = 0; i < 3; i++) cb.logAt(e.bot, "send_requested", "r" + i, noon - 1000 * i);
    T.eq("hourly limit reached", false, cb.mayRequestSend(e.bot, noon, 0).allowed);
    T.eq("old requests do not count", true, cb.mayRequestSend(e.bot, noon + 2 * 3_600_000L, 0).allowed);
    cb.pause(e.bot, true);
    T.eq("a paused bot never asks", false, cb.mayRequestSend(e.bot, noon + 2 * 3_600_000L, 0).allowed);
    cb.pause(e.bot, false);
    cb.pauseAll();
    T.eq("pause all stops every bot", false, cb.mayRequestSend(e.bot, noon + 2 * 3_600_000L, 0).allowed || cb.mayRequestSend(bot2, noon, 0).allowed);
    cb.resumeAll();
    T.eq("only an explicit resume lifts it", true, cb.mayRequestSend(e.bot, noon + 2 * 3_600_000L, 0).allowed);
    CommsBots.Config manual = cb.get(e.bot); manual.mode = CommsBots.Mode.MANUAL; cb.save(manual);
    T.eq("manual assistant mode never sends", false, cb.mayRequestSend(e.bot, noon + 2 * 3_600_000L, 0).allowed);
    for (int i = 0; i < 520; i++) cb.log(e.bot, "note", "n" + i);
    T.check("activity log is bounded", cb.activity(e.bot, 200).size() == 200 && e.db.count("SELECT COUNT(*) n FROM comm_activity WHERE bot=?", e.bot) <= 500);
    e.db.update("DELETE FROM bots WHERE id=?", bot2);
    T.eq("deleting a bot releases its conversations", null, cb.botFor("conv-1"));

    // ---- client codec: requests ----
    JSONObject disc = new JSONObject(CommsCodec.discover(RID));
    T.check("discover shape", disc.getString("task").equals("discover") && disc.getInt("v") == 1 && disc.getInt("min_v") == 1);
    JSONObject call = new JSONObject(CommsCodec.call(RID, NONCE, 5000L, "communications.send_sms", Collections.singletonMap("to", "+15550100101"), "bot-uid", "approval", "text John"));
    T.eq("call expires within the contract's 5 minutes", 5000L + 120_000L, call.getLong("expires_at"));
    T.check("call carries actor + args", call.getJSONObject("actor").getString("bot_id").equals("bot-uid") && call.getJSONObject("args").getString("to").equals("+15550100101"));
    T.throwsIae("bad request id", () -> CommsCodec.call("short", NONCE, 1L, "communications.send_sms", null, null, null, ""));
    T.throwsIae("bad nonce", () -> CommsCodec.call(RID, "ABC", 1L, "communications.send_sms", null, null, null, ""));
    T.throwsIae("only communications capabilities", () -> CommsCodec.call(RID, NONCE, 1L, "device.apps.list", null, null, null, ""));
    T.eq("receipt shape", "receipt", new JSONObject(CommsCodec.receipt(RID)).getString("task"));

    // ---- client codec: replies never invent completion ----
    for (String bad : new String[] {"", "not json", "{\"v\":0,\"ok\":true}", "{\"ok\":true}", "{\"v\":1,\"ok\":false,\"state\":\"completed\"}", "{\"v\":1,\"ok\":true,\"state\":\"mystery\"}"})
      T.eq("no state justified by: " + bad, null, CommsCodec.stateFor(CommsCodec.parse(bad)));
    T.check("oversized reply rejected", !CommsCodec.parse(new String(new char[800_000]).replace('\0', 'x')).parsed);
    CommsCodec.Reply pending = CommsCodec.parse("{\"v\":1,\"ok\":true,\"state\":\"pending_approval\",\"request_id\":\"" + RID + "\",\"approval_id\":\"ab12\"}");
    T.eq("pending approval", Jobs.State.AWAITING_APPROVAL, CommsCodec.stateFor(pending));
    T.eq("approval id kept", "ab12", pending.approvalId);
    CommsCodec.Reply refused = CommsCodec.parse("{\"v\":1,\"ok\":false,\"state\":\"refused\",\"code\":\"not_granted\",\"detail\":\"Not granted: send_messages.\"}");
    T.eq("refused is failed", Jobs.State.FAILED, CommsCodec.stateFor(refused));
    CommsCodec.Reply incompat = CommsCodec.parse("{\"v\":1,\"ok\":false,\"state\":\"error\",\"code\":\"incompatible_version\",\"versions\":{\"min\":1,\"max\":1}}");
    T.check("incompatible version recognised with the server's range", incompat.incompatible() && incompat.minVersion == 1 && incompat.maxVersion == 1);
    CommsCodec.Reply caps = CommsCodec.parse("{\"v\":1,\"ok\":true,\"capabilities\":[{\"id\":\"communications.send_sms\",\"status\":\"unavailable\",\"reason\":\"VictorSMS is not the default SMS app\"},{\"id\":\"communications.list_contacts\",\"status\":\"available\"}]}");
    T.eq("available capability", true, caps.supports("communications.list_contacts"));
    T.eq("unavailable capability", false, caps.supports("communications.send_sms"));
    T.eq("reason kept for the owner", "VictorSMS is not the default SMS app", caps.capabilities.get("communications.send_sms"));
    T.eq("unlisted capability is not supported", false, caps.supports("communications.place_call"));

    // ---- through the existing Jobs path ----
    Jobs jobs = new Jobs(e.db);
    long j = jobs.create("Text John", e.bot, 0, 0, "DCA", e.dca, "communications.send_sms", new JSONObject(), 0, 0);
    jobs.markSubmitting(j);
    T.eq("garbage changes nothing", false, CommsCodec.apply(jobs, j, CommsCodec.parse("???")));
    T.eq("still 'sent, no answer yet'", Jobs.State.SUBMITTING, jobs.get(j).state);
    T.eq("pending approval is reported", true, CommsCodec.apply(jobs, j, pending));
    T.eq("job awaits the owner in droidcommand-AI", Jobs.State.AWAITING_APPROVAL, jobs.get(j).state);
    T.eq("text claiming completion in a refusal changes nothing but failure", true, CommsCodec.apply(jobs, j, CommsCodec.parse("{\"v\":1,\"ok\":false,\"state\":\"refused\",\"code\":\"denied\",\"detail\":\"completed successfully\"}")));
    T.eq("denied -> failed, never completed", Jobs.State.FAILED, jobs.get(j).state);
    T.eq("a terminal job cannot be revived by a later reply", false, CommsCodec.apply(jobs, j, CommsCodec.parse("{\"v\":1,\"ok\":true,\"state\":\"completed\",\"result\":{}}")));
    long k = jobs.create("Text Sam", e.bot, 0, 0, "DCA", e.dca, "communications.send_sms", new JSONObject(), 0, 0);
    jobs.markSubmitting(k);
    T.eq("completed from the gateway's report", true, CommsCodec.apply(jobs, k, CommsCodec.parse("{\"v\":1,\"ok\":true,\"state\":\"completed\",\"request_id\":\"" + RID + "\",\"result\":{\"message_id\":\"m1\",\"token\":\"sk-abcdefgh12345678\"}}")));
    T.eq("completed", Jobs.State.COMPLETED, jobs.get(k).state);
    T.eq("reported by the backend, not inferred", "backend", jobs.get(k).stateSource);
    T.check("secrets in results are redacted before storage", !jobs.get(k).result.contains("sk-abcdefgh12345678") && jobs.get(k).result.contains("m1"));
    long u = jobs.create("Text Ann", e.bot, 0, 0, "DCA", e.dca, "communications.send_sms", new JSONObject(), 0, 0);
    jobs.markSubmitting(u);
    T.eq("an unknown outcome is recorded as such", true, CommsCodec.apply(jobs, u, CommsCodec.parse("{\"v\":1,\"ok\":false,\"state\":\"unknown\",\"detail\":\"VictorSMS stopped answering\"}")));
    T.eq("outcome unknown, not failed and not completed", Jobs.State.UNKNOWN, jobs.get(u).state);
    T.eq("it is not repeated automatically", false, CommsCodec.apply(jobs, u, CommsCodec.parse("{\"v\":1,\"ok\":false,\"state\":\"unknown\"}")));
    T.throwsIae("an unknown job can never be sent again", () -> { try { jobs.markSubmitting(u); } catch (IllegalStateException ex) { throw new IllegalArgumentException(ex.getMessage()); } });

    // ---- migration 3 -> 4 ----
    JdbcDb v3 = new JdbcDb("jdbc:sqlite::memory:");
    v3.exec("PRAGMA foreign_keys=OFF");
    v3.tx(() -> { Migrations.upgrade(v3, 0); return null; }); // fresh = v1..v4
    JdbcDb old = new JdbcDb("jdbc:sqlite::memory:");
    old.exec("PRAGMA foreign_keys=OFF");
    old.tx(() -> { Migrations.run(old, Migrations.v1()); Migrations.run(old, Migrations.v2()); Migrations.run(old, Migrations.v3()); return null; });
    long ob = old.insert("INSERT INTO bots(name,description,instructions,avatar,uid) VALUES('Keep','d','i','x','keep-uid')");
    old.tx(() -> { Migrations.upgrade(old, 3); return null; });
    T.eq("existing bot preserved", "Keep", old.one("SELECT name n FROM bots WHERE id=?", ob).optString("n"));
    List<String> fresh = tables(v3), up = tables(old);
    T.eq("upgrade and fresh install produce the same tables", fresh, up);
    for (String t : new String[] {"style_profiles", "style_versions", "style_prefs", "style_examples", "comm_bots", "comm_assign", "comm_activity", "comm_state"}) T.check("table " + t, up.contains(t));
    T.eq("schema version", 5, Migrations.VERSION);
    T.done();
  }

  static List<String> tables(JdbcDb db) {
    List<String> out = new ArrayList<>();
    for (JSONObject r : db.rows("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '%_fts%' AND name NOT LIKE '%_content' AND name NOT LIKE '%_segdir' AND name NOT LIKE '%_segments' AND name NOT LIKE '%_stat' AND name NOT LIKE '%_docsize' ORDER BY name")) out.add(r.optString("name"));
    return out;
  }
}
