import com.victorious.bots.*;
import java.util.*;
import org.json.*;

/** Victorious Framework: regression tests for comms review findings F14-F17 (backup of comms data, comms bot rules). */
public final class CommsBotsReviewFixTest {
  static JdbcDb fresh() throws Exception {
    JdbcDb db = new JdbcDb("jdbc:sqlite::memory:");
    db.exec("PRAGMA foreign_keys=OFF");
    db.tx(() -> { Migrations.upgrade(db, 0); return null; });
    db.exec("PRAGMA foreign_keys=ON");
    return db;
  }

  public static void main(String[] a) throws Exception {
    Fakes.Env e = Fakes.env();
    CommsBots cb = new CommsBots(e.db);

    // ---- F15: hours ----
    CommsBots.Config c = cb.get(e.bot);
    c.hoursStart = 99; c.hoursEnd = 99;
    T.throwsIae("F15 equal out-of-range hours are refused", () -> cb.save(c));
    c.hoursStart = 5; c.hoursEnd = 5;
    T.throwsIae("F15 equal hours are refused as ambiguous", () -> cb.save(c));
    c.hoursStart = -1; c.hoursEnd = 7;
    T.throwsIae("F15 one hour unset is refused", () -> cb.save(c));
    c.hoursStart = 22; c.hoursEnd = 6;
    cb.save(c);
    T.eq("F15 an overnight range is kept", 22, cb.get(e.bot).hoursStart);
    e.db.update("UPDATE comm_bots SET hours_start=99,hours_end=99 WHERE bot=?", e.bot);
    T.eq("F15 a bad stored pair reads as unset", -1, cb.get(e.bot).hoursStart);
    c.hoursStart = -1; c.hoursEnd = -1;

    // ---- F16: contacts and mode ----
    CommsBots.Config n = cb.get(e.bot);
    n.contacts = new ArrayList<>(Arrays.asList("a", null));
    T.throwsIae("F16 a null contact is refused, not a crash", () -> cb.save(n));
    n.contacts = new ArrayList<>(Collections.singletonList(new String(new char[81]).replace('\0', 'x')));
    T.throwsIae("F16 an over-long contact is refused, not dropped", () -> cb.save(n));
    List<String> lots = new ArrayList<>();
    for (int i = 0; i < 501; i++) lots.add("c" + i);
    n.contacts = lots;
    T.throwsIae("F16 more than 500 contacts are refused", () -> cb.save(n));
    n.contacts = new ArrayList<>();
    n.mode = null;
    T.throwsIae("F16 a missing mode is refused, not a crash", () -> cb.save(n));

    // ---- F17: activity cap on both paths; claimSend counts atomically ----
    for (int i = 0; i < 520; i++) cb.logAt(e.bot, "note", "x", 1000L + i);
    T.eq("F17 logAt keeps at most 500 rows", 500L, e.db.count("SELECT COUNT(*) n FROM comm_activity WHERE bot=?", e.bot));
    CommsBots.Config lim = cb.get(e.bot);
    lim.mode = CommsBots.Mode.APPROVAL; lim.maxPerHour = 2; lim.hoursStart = -1; lim.hoursEnd = -1; lim.contacts = new ArrayList<>();
    cb.save(lim);
    long now = 1_800_000_000_000L;
    T.check("F17 first claim allowed", cb.claimSend(e.bot, now, 0, "one").allowed);
    T.check("F17 second claim allowed", cb.claimSend(e.bot, now + 1, 0, "two").allowed);
    T.check("F17 third claim refused by the hourly limit", !cb.claimSend(e.bot, now + 2, 0, "three").allowed);
    T.eq("F17 a refused claim writes nothing", 2L, e.db.count("SELECT COUNT(*) n FROM comm_activity WHERE bot=? AND kind=?", e.bot, CommsBots.SEND_REQUESTED));

    // ---- F14: style profiles and comms bot settings survive backup and restore ----
    StyleProfiles sp = new StyleProfiles(e.db);
    long pid = sp.create("Casual me", "global", Arrays.asList("+15550100101"), "");
    sp.addVersion(pid, StyleAnalyzer.analyze(Arrays.asList(new StyleAnalyzer.Msg("yeah lol", 1), new StyleAnalyzer.Msg("omw lol", 2))), new ArrayList<String>(), "");
    sp.addExample(pid, "k sounds good", "saved");
    CommsBots.Config auto = cb.get(e.bot);
    auto.mode = CommsBots.Mode.AUTO;
    auto.profileUid = sp.get(pid).getString("uid");
    auto.contacts = new ArrayList<>(Collections.singletonList("+15550100101"));
    cb.save(auto);
    cb.assign(e.bot, "conv-1");
    String json = Backup.export(e.db).toString();
    JSONObject root = new JSONObject(json);
    T.eq("F14 style profiles are exported", 1, root.getJSONArray("style_profiles").length());
    T.eq("F14 comm bot settings are exported", 1, root.getJSONArray("comm_bots").length());
    T.check("F14 no contacts or conversation ids are exported", !json.contains("+15550100101") && !json.contains("conv-1"));
    T.check("F14 the exclusions are listed", root.getJSONArray("excluded").toString().contains("conversation assignments"));
    JdbcDb target = fresh();
    Backup.Preview pv = Backup.preview(target, json);
    T.check("F14 preview ok: " + pv.errors, pv.ok());
    T.check("F14 preview counts a new style profile", pv.counts.get("style profiles")[0] == 1);
    T.eq("F14 preview writes nothing", 0L, target.count("SELECT COUNT(*) n FROM style_profiles"));
    Backup.Preview ap = Backup.apply(target, json, Backup.OnConflict.SKIP);
    T.check("F14 restore ok: " + ap.errors, ap.ok());
    T.eq("F14 the style profile is restored", "Casual me", target.one("SELECT name FROM style_profiles").getString("name"));
    T.eq("F14 its example is restored", 1L, target.count("SELECT COUNT(*) n FROM style_examples"));
    long tb = target.one("SELECT id FROM bots WHERE uid='b-uid'").getLong("id");
    CommsBots.Config back = new CommsBots(target).get(tb);
    T.eq("F14 an Auto bot comes back in Approval mode", CommsBots.Mode.APPROVAL, back.mode);
    T.check("F14 and the owner is told", ap.warnings.toString().contains("Approval mode"));
    T.eq("F14 the bot points at the restored profile", target.one("SELECT uid FROM style_profiles").getString("uid"), back.profileUid);
    T.eq("F14 limit restored", 2, back.maxPerHour);
    Backup.Preview again = Backup.apply(target, json, Backup.OnConflict.SKIP);
    T.check("F14 restoring twice duplicates nothing", again.ok() && target.count("SELECT COUNT(*) n FROM style_profiles") == 1 && target.count("SELECT COUNT(*) n FROM comm_bots") == 1);
    JSONObject bad = new JSONObject(json);
    bad.getJSONArray("comm_bots").getJSONObject(0).put("hours_start", 30).put("hours_end", 30);
    T.check("F14 invalid comm settings in a file are refused", !Backup.preview(fresh(), bad.toString()).ok());
    T.done();
  }
}
