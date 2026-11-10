import com.victorious.bots.Rules;
import java.util.*;

public final class RulesTest {
  static int passed = 0;

  static void check(boolean b) {
    if (!b) throw new AssertionError();
    passed++;
  }

  static void refuses(Runnable r) {
    try {
      r.run();
      throw new AssertionError("Accepted invalid input");
    } catch (IllegalArgumentException expected) {
      passed++;
    }
  }

  public static void main(String[] args) {
    check(
        Rules.endpoint("https://example.org/v1/chat/completions")
            .equals("https://example.org/v1/chat/completions"));
    refuses(() -> Rules.endpoint("http://example.org/v1/chat/completions"));
    refuses(() -> Rules.endpoint("https://secret@example.org/v1/chat/completions"));
    refuses(() -> Rules.endpoint("https://example.org/v1/chat/completions?key=x"));
    refuses(() -> Rules.endpoint("https://example.org/v1/chat/completions#x"));
    refuses(() -> Rules.endpoint("https://example.org/other"));
    refuses(() -> Rules.endpoint("file:///v1/chat/completions"));
    refuses(() -> Rules.endpoint("https://example.org:99999/v1/chat/completions"));
    check(Rules.pin("ab:".repeat(31) + "ab").equals("AB".repeat(32)));
    refuses(() -> Rules.pin("123"));
    refuses(() -> Rules.length(" ", 80, "name"));
    refuses(() -> Rules.context(Collections.emptyList(), "a", "x".repeat(12001)));
    refuses(() -> Rules.context(Collections.emptyList(), "a".repeat(8001), "x"));
    List<Rules.Turn> history = new ArrayList<>();
    for (int i = 0; i < 20; i++)
      history.add(new Rules.Turn(i % 2 == 0 ? "user" : "assistant", "x".repeat(4000)));
    List<Rules.Turn> kept = Rules.context(history, "instructions", "latest input");
    check(kept.get(0).role.equals("system"));
    check(kept.get(kept.size() - 1).content.equals("latest input"));
    check(kept.stream().mapToInt(t -> t.content.length()).sum() <= 40000);
    check(kept.size() < history.size());
    check(kept.get(1).role.equals("user"));
    check(Rules.context(Collections.emptyList(), "i", "u").size() == 2);
    System.out.println(passed + " checks passed");
  }
}
