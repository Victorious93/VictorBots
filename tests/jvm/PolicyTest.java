import com.victorious.bots.*;
import com.victorious.bots.Policy.*;

public final class PolicyTest {
  public static void main(String[] a) {
    // Exhaustive matrix: no combination may let cloud/DroidCommand traffic through a stricter mode.
    for (Purpose p : Purpose.values()) {
      for (Kind k : Kind.values()) {
        T.eq("LOCAL_ONLY refuses " + p + "/" + k, false, Policy.decide(Mode.LOCAL_ONLY, k, p, true).allowed);
        boolean pcOnly = Policy.decide(Mode.PC_ONLY, k, p, true).allowed;
        T.eq("PC_ONLY " + p + "/" + k + " only PC (even if 'confirmed')", k == Kind.PC, pcOnly);
        T.eq("ALLOW_CLOUD allows " + p + "/" + k, true, Policy.decide(Mode.ALLOW_CLOUD, k, p, false).allowed);
        boolean pref = Policy.decide(Mode.PC_PREFERRED, k, p, true).allowed;
        boolean want = k == Kind.PC || p.ownerInitiated;
        T.eq("PC_PREFERRED confirmed " + p + "/" + k, want, pref);
        T.eq("PC_PREFERRED unconfirmed " + p + "/" + k, k == Kind.PC, Policy.decide(Mode.PC_PREFERRED, k, p, false).allowed);
      }
    }
    for (Purpose p : new Purpose[] {Purpose.ROUTING, Purpose.MEMORY, Purpose.SUMMARY, Purpose.TITLE, Purpose.ROUTINE, Purpose.TEAM})
      T.eq("background purpose " + p + " is not owner-initiated", false, p.ownerInitiated);
    // A bot can tighten but never loosen the owner's policy.
    T.eq("bot tightens", Mode.PC_ONLY, Policy.effective(Mode.ALLOW_CLOUD, "pc_only"));
    T.eq("bot cannot loosen", Mode.PC_ONLY, Policy.effective(Mode.PC_ONLY, "allow_cloud"));
    T.eq("unknown override ignored", Mode.PC_ONLY, Policy.effective(Mode.PC_ONLY, "bogus"));
    T.eq("empty inherits", Mode.PC_PREFERRED, Policy.effective(Mode.PC_PREFERRED, ""));
    T.eq("local-only is honestly unusable", false, Mode.LOCAL_ONLY.usable);
    T.check("denial explains and says nothing was sent", Policy.decide(Mode.PC_ONLY, Kind.CLOUD, Purpose.CHAT, false).reason.contains("Nothing was sent"));

    // Endpoint rules.
    T.eq("base strips /chat/completions", "https://api.example.com/v1", Hosts.base("https://API.example.com/v1/chat/completions"));
    T.eq("base keeps port and path", "https://pc.lan:8443/v1", Hosts.base("https://pc.lan:8443/v1/"));
    T.throwsIae("http refused", () -> Hosts.base("http://pc.lan/v1"));
    T.throwsIae("credentials in URL refused", () -> Hosts.base("https://user:pw@pc.lan/v1"));
    T.throwsIae("query refused", () -> Hosts.base("https://pc.lan/v1?key=abc"));
    T.throwsIae("fragment refused", () -> Hosts.base("https://pc.lan/v1#x"));
    T.eq("origin default port", "https://pc.lan:443", Hosts.origin("https://pc.lan/v1"));
    T.check("different origin differs", !Hosts.origin("https://a.example/v1").equals(Hosts.origin("https://b.example/v1")));
    T.check("port is part of origin", !Hosts.origin("https://a.example:8443/v1").equals(Hosts.origin("https://a.example/v1")));
    T.check("localhost warns it is the phone", Hosts.pcHint("https://localhost:8443/v1").contains("this phone"));
    T.check("public host warns", Hosts.pcHint("https://example.com/v1").contains("private"));
    T.eq("tailscale host is quiet", "", Hosts.pcHint("https://box.tail1234.ts.net/v1"));
    T.eq("rfc1918 is quiet", "", Hosts.pcHint("https://192.168.1.20:8443/v1"));
    T.eq("runner base", "https://pc.lan:8443/runner/v1", Hosts.runnerBase("https://pc.lan:8443/v1"));

    // Redaction.
    T.check("redacts known key", !Redact.text("failed with key abcd1234efgh", "abcd1234efgh").contains("abcd1234"));
    T.check("redacts bearer", !Redact.text("Authorization: Bearer abcdef123456").contains("abcdef123456"));
    T.check("redacts key=value", !Redact.text("api_key=hunter2hunter2").contains("hunter2"));
    T.check("redacts sk- style", !Redact.text("used sk-abcdefghijklmnop here").contains("abcdefghijkl"));

    // Token-aware budgeting.
    java.util.List<Rules.Turn> h = new java.util.ArrayList<>();
    for (int i = 0; i < 20; i++) {
      h.add(new Rules.Turn("user", repeat("u", 300)));
      h.add(new Rules.Turn("assistant", repeat("a", 300)));
    }
    Rules.Plan p = Rules.plan(h, "sys", "current question", 2000, 500, 3.0, "test");
    T.check("some older turns omitted", p.omitted > 0);
    T.eq("current input is last", "current question", p.turns.get(p.turns.size() - 1).content);
    T.eq("system is first", "sys", p.turns.get(0).content);
    T.eq("first kept history turn is a user turn", "user", p.turns.get(1).role);
    T.check("estimate within available", p.estimatedInputTokens <= 1500);
    T.check("summary names reserve and omission", p.summary().contains("reserved") && p.summary().contains("omitted"));
    T.throwsIae("essential content that cannot fit is refused, not clipped", () -> Rules.plan(h, repeat("s", 9000), "q", 2000, 500, 3.0, "t"));
    T.throwsIae("oversized current message refused", () -> Rules.plan(h, "sys", repeat("q", 9000), 2000, 500, 3.0, "t"));
    Rules.Plan big = Rules.plan(h, "sys", "q", 200000, 4000, 3.0, "t");
    T.eq("everything fits in a big window", 0, big.omitted);
    T.done();
  }

  static String repeat(String s, int n) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < n; i++) b.append(s);
    return b.toString();
  }
}
