/** Tiny assertion harness: each check prints PASS/FAIL; process exits non-zero on any failure. */
public final class T {
  public static int passed, failed;

  public static void check(String name, boolean ok) {
    if (ok) {
      passed++;
      System.out.println("PASS " + name);
    } else {
      failed++;
      System.out.println("FAIL " + name);
    }
  }

  public static void eq(String name, Object want, Object got) {
    boolean ok = want == null ? got == null : want.equals(got);
    if (!ok) System.out.println("  want=" + want + " got=" + got);
    check(name, ok);
  }

  public interface Thrower {
    void run() throws Exception;
  }

  public static void throwsIae(String name, Thrower t) {
    try {
      t.run();
      check(name + " (expected exception)", false);
    } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
      check(name, true);
    } catch (Exception e) {
      System.out.println("  unexpected " + e);
      check(name, false);
    }
  }

  public static void done() {
    System.out.println("\n" + passed + " passed, " + failed + " failed");
    if (failed > 0) System.exit(1);
  }

  private T() {}
}
