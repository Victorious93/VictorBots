import com.victorious.bots.*;
import java.util.*;
import org.json.*;

/** Victorious Framework: style analysis, profile versions, corrections and guidance. Synthetic authors only. */
public final class StyleTest {
  static List<StyleAnalyzer.Msg> casual(int n) {
    String[] t = {"yeah lol", "omw", "k sounds good 👍", "idk man", "brb", "nah dont think so", "lmk", "haha no way 😂", "thx!!", "ok", "cant tonight tbh", "see u there"};
    List<StyleAnalyzer.Msg> out = new ArrayList<>();
    long ts = 1_700_000_000_000L;
    for (int i = 0; i < n; i++) {
      out.add(new StyleAnalyzer.Msg(t[i % t.length], ts));
      ts += (i % 3 == 2) ? 3_600_000L : 20_000L; // bursts of three, then a gap
    }
    return out;
  }

  static List<StyleAnalyzer.Msg> formal(int n) {
    String[] t = {"Thank you for the update. I will review it today.", "Could you please confirm the time?", "I appreciate your help with this.", "Please let me know if anything changes.", "That works for me, thank you.", "I will send the document this afternoon."};
    List<StyleAnalyzer.Msg> out = new ArrayList<>();
    long ts = 1_700_000_000_000L;
    for (int i = 0; i < n; i++) { out.add(new StyleAnalyzer.Msg(t[i % t.length], ts)); ts += 4_000_000L; }
    return out;
  }

  public static void main(String[] a) throws Exception {
    // ---- analyzer ----
    StyleAnalyzer.Result c = StyleAnalyzer.analyze(casual(120)), f = StyleAnalyzer.analyze(formal(120));
    JSONObject cs = c.stats, fs = f.stats;
    T.check("casual author starts lowercase", cs.getDouble("startsLowercaseRatio") > 0.9);
    T.check("formal author does not", fs.getDouble("startsLowercaseRatio") < 0.1);
    T.check("casual is less formal than formal", cs.getInt("formality") < 35 && fs.getInt("formality") > 65);
    T.check("emoji use measured", cs.getDouble("emojiPerMessage") > 0.1 && fs.getDouble("emojiPerMessage") == 0.0);
    T.check("4-byte emoji are real emoji", cs.getJSONArray("topEmoji").toString().contains("👍"));
    T.check("slang found", cs.getJSONArray("topSlang").toString().contains("lol") && cs.getJSONArray("topSlang").toString().contains("omw"));
    T.check("formal author has no slang", fs.getJSONArray("topSlang").length() == 0);
    T.check("bursts detected from timestamps", cs.getDouble("burstRatio") > 0.5 && cs.getDouble("avgBurstSize") >= 2);
    T.eq("spaced messages are not bursts", 0.0, fs.getDouble("burstRatio"));
    T.check("period habit differs", fs.getDouble("endsPeriodRatio") > 0.5 && cs.getDouble("endsPeriodRatio") == 0.0);
    T.check("apostrophes dropped by the casual author", cs.getDouble("apostropheOmitRatio") == 1.0);
    T.check("greeting/sign-off lists exist", cs.has("greetings") && cs.has("signOffs"));
    T.check("shorter messages", cs.getDouble("avgWords") < fs.getDouble("avgWords"));
    T.eq("deterministic stats", StyleAnalyzer.analyze(casual(120)).stats.toString(), cs.toString());
    T.eq("deterministic examples", StyleAnalyzer.analyze(casual(120)).examples, c.examples);
    T.eq("confidence low", "low", StyleAnalyzer.analyze(casual(10)).confidence);
    T.eq("confidence medium", "medium", StyleAnalyzer.analyze(casual(50)).confidence);
    T.eq("confidence high", "high", StyleAnalyzer.analyze(casual(250)).confidence);
    StyleAnalyzer.Result empty = StyleAnalyzer.analyze(new ArrayList<StyleAnalyzer.Msg>(Arrays.asList(new StyleAnalyzer.Msg("   ", 0))));
    T.eq("blank input handled", 0, empty.count);
    List<StyleAnalyzer.Msg> mixed = new ArrayList<>(casual(40));
    mixed.add(new StyleAnalyzer.Msg("call me on +1 555 010 0199", 0));
    mixed.add(new StyleAnalyzer.Msg("see https://example.com/private?id=7", 0));
    mixed.add(new StyleAnalyzer.Msg("mail me at sam@example.com", 0));
    mixed.add(new StyleAnalyzer.Msg("日本語のテストです 🎌", 0));
    StyleAnalyzer.Result m = StyleAnalyzer.analyze(mixed);
    boolean leak = false;
    for (String e : m.examples) if (e.contains("http") || e.contains("@") || e.matches(".*\\d{6,}.*") || e.contains("555")) leak = true;
    T.check("examples never carry links, emails or numbers", !leak);
    T.check("at most 8 examples", m.examples.size() <= 8);
    T.check("CJK and emoji do not break analysis", m.count == 44);

    // ---- profiles ----
    Fakes.Env e = Fakes.env();
    StyleProfiles sp = new StyleProfiles(e.db);
    T.throwsIae("empty name rejected", () -> sp.create("  ", "global", null, ""));
    T.throwsIae("bad scope rejected", () -> sp.create("x", "everyone", null, ""));
    long p = sp.create("My texting", "global", Arrays.asList("+15550100101"), "bot-uid");
    T.eq("new profile default strength", 60L, sp.get(p).getLong("strength"));
    T.eq("no version yet", 0L, sp.get(p).getLong("current_version"));
    T.check("guidance works before any analysis", sp.guidance(p).getString("guidance").length() >= 0);
    int v1 = sp.addVersion(p, c, Arrays.asList("conv-1"), "first import");
    int v2 = sp.addVersion(p, f, Arrays.asList("conv-1", "conv-2"), "after update");
    T.eq("versions increment", 2, v2 - v1 + 1);
    JSONObject cmp = sp.compare(p, v1, v2);
    T.check("comparison shows real differences", cmp.getJSONObject("deltas").has("formality") && cmp.getJSONObject("deltas").getDouble("startsLowercaseRatio") < 0);
    int v3 = sp.rollbackTo(p, v1);
    T.eq("rollback adds a new version", 3, v3);
    T.eq("history is intact", 3, sp.versions(p).size());
    T.eq("restored content equals version 1", sp.version(p, 1).getString("stats"), sp.version(p, 3).getString("stats"));
    T.eq("version 2 untouched", f.stats.toString(), new JSONObject(sp.version(p, 2).getString("stats")).toString());
    T.eq("current points at the restored copy", 3L, sp.get(p).getLong("current_version"));
    T.throwsIae("rollback to a missing version", () -> sp.rollbackTo(p, 99));
    T.throwsIae("strength bounds", () -> sp.setStrength(p, 101));
    sp.setStrength(p, 0);
    T.eq("strength 0 is plain AI writing", 0L, sp.guidance(p).getLong("strength"));
    sp.setStrength(p, 85);

    // ---- corrections: the owner's words only ----
    T.eq("approving AI text unedited teaches nothing", null, sp.record(p, "sounds good", "sounds good", StyleProfiles.Action.APPROVE));
    T.eq("rejecting teaches nothing", null, sp.record(p, "x", "", StyleProfiles.Action.REJECT));
    T.eq("regenerating teaches nothing", null, sp.record(p, "x", "", StyleProfiles.Action.REGENERATE));
    T.eq("an edit does not teach unless the owner allowed it", null, sp.record(p, "Sounds good, thank you.", "sounds good thx", StyleProfiles.Action.EDIT));
    T.eq("no examples learned so far", 0, sp.examples(p).size());
    sp.setLearnFromEdits(p, true);
    T.check("an allowed edit becomes an example", sp.record(p, "Sounds good, thank you.", "sounds good thx", StyleProfiles.Action.EDIT) != null);
    T.eq("an unchanged 'edit' teaches nothing", null, sp.record(p, "same text", "same text", StyleProfiles.Action.EDIT));
    T.check("explicit save works", sp.record(p, "", "k bet 👍", StyleProfiles.Action.SAVE_AS_EXAMPLE) != null);
    long dup = sp.addExample(p, "k bet 👍", "saved");
    T.eq("no duplicate examples", 2, sp.examples(p).size());
    T.throwsIae("an example with a link is refused", () -> sp.addExample(p, "go to https://x.example now", "saved"));
    T.throwsIae("an example with a phone number is refused", () -> sp.addExample(p, "call 555 010 0199", "saved"));
    T.eq("a learned edit that contains a number is silently not learned", null, sp.record(p, "a", "meet at 5551234567", StyleProfiles.Action.EDIT));

    // ---- quick preferences are reversible ----
    JSONObject base = sp.guidance(p);
    T.check("baseline not 'very short'", !base.getString("guidance").contains("very short"));
    long shorter = sp.quick(p, StyleProfiles.Quick.SHORTER);
    T.eq("quick actions are idempotent", shorter, sp.quick(p, StyleProfiles.Quick.SHORTER));
    T.check("shorter reflected", sp.guidance(p).getString("guidance").contains("very short"));
    sp.removePref(shorter);
    T.check("removing the preference reverses it", !sp.guidance(p).getString("guidance").contains("very short"));
    sp.quick(p, StyleProfiles.Quick.FEWER_EMOJIS);
    T.check("fewer emoji reflected", sp.guidance(p).getString("guidance").contains("sparingly"));
    sp.quick(p, StyleProfiles.Quick.SPLIT_REPLIES);
    T.check("split replies reflected", sp.guidance(p).getString("guidance").contains("several short messages"));
    sp.avoid(p, "per my last message");
    sp.prefer(p, "sounds good");
    JSONObject g = sp.guidance(p);
    T.check("avoid list passed on", g.getJSONArray("avoid").toString().contains("per my last message"));
    T.check("prefer phrase passed on", g.getString("guidance").contains("Likes to say: \"sounds good\""));
    T.throwsIae("empty expression refused", () -> sp.avoid(p, "  "));
    T.check("examples capped at 10 and short", g.getJSONArray("examples").length() <= 10 && g.getJSONArray("examples").toString().length() < 4000);
    T.check("approved examples come first", g.getJSONArray("examples").getString(0).equals("sounds good thx"));
    T.eq("preserve spelling by default", true, g.getBoolean("preserve_spelling"));
    T.eq("strength passed on", 85L, g.getLong("strength"));

    // ---- export / import / duplicate / reset / delete ----
    String exported = sp.export(p);
    T.check("export carries no contact, bot or id", !exported.contains("15550100101") && !exported.contains("bot-uid") && !exported.contains(sp.get(p).getString("uid")));
    long imp = sp.importProfile(exported);
    T.eq("import round-trips the guidance", sp.guidance(p).getJSONArray("avoid").toString(), sp.guidance(imp).getJSONArray("avoid").toString());
    T.eq("import keeps strength", 85L, sp.get(imp).getLong("strength"));
    T.eq("imported profile has no contacts", "[]", sp.get(imp).getString("contacts"));
    for (String bad : new String[] {"", "not json", "{\"format\":\"other\",\"v\":1}", "{\"format\":\"victorbots-style-profile\",\"v\":2}", "{\"format\":\"victorbots-style-profile\",\"v\":1,\"strength\":500}"})
      T.throwsIae("bad import refused: " + bad, () -> sp.importProfile(bad));
    StringBuilder big = new StringBuilder("{\"format\":\"victorbots-style-profile\",\"v\":1,\"name\":\"" );
    for (int i = 0; i < 210_000; i++) big.append('a');
    T.throwsIae("oversized import refused", () -> sp.importProfile(big.append("\"}").toString()));
    long copy = sp.duplicate(p, "My texting (copy)");
    T.eq("duplicate copies the analysis", sp.version(p, 3).getString("stats"), sp.version(copy, 1).getString("stats"));
    T.eq("duplicate copies preferences", sp.prefs(p).size(), sp.prefs(copy).size());
    T.eq("duplicate does not copy contacts", "[]", sp.get(copy).getString("contacts"));
    sp.reset(copy);
    T.eq("reset clears learned preferences", 0, sp.prefs(copy).size());
    T.eq("reset clears approved examples", 0, sp.examples(copy).size());
    T.check("reset is recorded in history", sp.versions(copy).size() == 2);
    sp.delete(copy);
    T.eq("delete removes versions", 0L, e.db.count("SELECT COUNT(*) n FROM style_versions WHERE profile=?", copy));
    T.eq("delete removes the profile", null, sp.get(copy));
    T.eq("the original is untouched", true, sp.get(p) != null);
    T.done();
  }
}
