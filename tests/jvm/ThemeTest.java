import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Theme guard for the fleet neon palette. Scans app/src/main (java + res) for colour literals and fails on the old
 * lime/olive colours, on any light background, on colours defined outside Ui.java / res/values/colors.xml, and on
 * contrast regressions; also asserts the three palette colours are present. Run from the repo root (scripts/test-jvm.sh).
 */
public final class ThemeTest {
  static final Path MAIN = Paths.get("app/src/main");
  static final int NAVY = 0x0B0F1A, CYAN = 0x00E5FF, MAGENTA = 0xFF2BD6;

  /** Lime / olive tones used by the pre-neon theme (6-digit RGB). */
  static final Set<Integer> BANNED =
      new HashSet<>(Arrays.asList(0x10120F, 0x1C2019, 0xF0F3E9, 0xA5AE9E, 0xB5F36C, 0xFFBE8A, 0xFF8A8A, 0x2B3226, 0x242A20, 0x34442A, 0x283320, 0x151A12));

  /** Colour roles that are drawn as text/foreground; every other colour is a background. */
  static final Set<String> TEXT_ROLES = new HashSet<>(Arrays.asList("FG", "MUTED", "ACCENT", "ACCENT2", "WARN", "BAD"));

  static final double BG_MAX_LUMINANCE = 0.10;
  static final double AA_BODY = 4.5;

  static final Pattern HEX_JAVA = Pattern.compile("(?<![0-9A-Za-z_])0[xX]([0-9a-fA-F]{8})(?![0-9A-Za-z_])");
  static final Pattern HEX_HASH = Pattern.compile("#([0-9a-fA-F]{8}|[0-9a-fA-F]{6})(?![0-9A-Za-z_])");
  static final Pattern ANDROID_LIGHT =
      Pattern.compile("Color\\.(parseColor|rgb|argb|hsv|WHITE|LTGRAY|GRAY|CYAN|MAGENTA|YELLOW|RED|GREEN|BLUE|DKGRAY|BLACK)\\b|@android:color/|android\\.R\\.color\\.|\\?android:attr/colorBackground|Theme\\.(Material\\.Light|Holo\\.Light|DeviceDefault\\.Light|Light)");
  static final Pattern CONST = Pattern.compile("\\b([A-Z][A-Z0-9_]*)\\s*=\\s*0[xX]([0-9a-fA-F]{8})\\b");
  static final Pattern BG_CALL = Pattern.compile("\\b(?:bg|setBackground|setBackgroundColor|setColor|setCardBackgroundColor)\\s*\\(");

  static final class Hit {
    final String file;
    final int line;
    final int rgb;

    Hit(String file, int line, int rgb) {
      this.file = file;
      this.line = line;
      this.rgb = rgb;
    }

    public String toString() {
      return file + ":" + line + " #" + String.format("%06X", rgb);
    }
  }

  static double channel(int c8) {
    double c = c8 / 255.0;
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  }

  static double luminance(int rgb) {
    return 0.2126 * channel((rgb >> 16) & 255) + 0.7152 * channel((rgb >> 8) & 255) + 0.0722 * channel(rgb & 255);
  }

  static double contrast(int a, int b) {
    double x = luminance(a), y = luminance(b);
    return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
  }

  /** A background is acceptable when it is a dark surface or exactly one of the two neon accents. */
  static boolean acceptableBackground(int rgb) {
    return luminance(rgb) <= BG_MAX_LUMINANCE || rgb == CYAN || rgb == MAGENTA;
  }

  static List<Hit> literals(String file, String text) {
    List<Hit> out = new ArrayList<>();
    String[] lines = text.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      Matcher m = HEX_JAVA.matcher(lines[i]);
      while (m.find()) out.add(new Hit(file, i + 1, (int) (Long.parseLong(m.group(1), 16) & 0xFFFFFF)));
      m = HEX_HASH.matcher(lines[i]);
      while (m.find()) {
        String h = m.group(1);
        out.add(new Hit(file, i + 1, (int) (Long.parseLong(h.length() == 8 ? h.substring(2) : h, 16))));
      }
    }
    return out;
  }

  static String read(Path p) throws IOException {
    return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
  }

  static List<Path> sources() throws IOException {
    try (Stream<Path> s = Files.walk(MAIN)) {
      return s.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".xml")).sorted().collect(Collectors.toList());
    }
  }

  static Map<String, Integer> uiConstants(String ui) {
    Map<String, Integer> m = new HashMap<>();
    Matcher c = CONST.matcher(ui);
    while (c.find()) m.put(c.group(1), (int) (Long.parseLong(c.group(2), 16) & 0xFFFFFF));
    return m;
  }

  static Map<String, Integer> xmlColors(String xml) {
    Map<String, Integer> m = new HashMap<>();
    Matcher c = Pattern.compile("<color\\s+name=\"vb_([a-z0-9_]+)\"\\s*>#([0-9a-fA-F]{6,8})</color>").matcher(xml);
    while (c.find()) {
      String h = c.group(2);
      m.put(c.group(1).toUpperCase(Locale.ROOT), (int) Long.parseLong(h.length() == 8 ? h.substring(2) : h, 16));
    }
    return m;
  }

  /** Names passed to bg()/setBackground*() on a line; used to prove each background role is a dark surface or accent. */
  static List<String> backgroundRoles(String line) {
    Set<String> out = new LinkedHashSet<>();
    Matcher call = BG_CALL.matcher(line);
    while (call.find()) {
      Matcher id = Pattern.compile("\\b(?:Ui\\.)?([A-Z][A-Z0-9_]+)\\b").matcher(line.substring(call.end()));
      while (id.find()) out.add(id.group(1));
    }
    return new ArrayList<>(out);
  }

  static void selfTest() {
    T.check("luminance: white is light", luminance(0xFFFFFF) > 0.9);
    T.check("guard: white background rejected", !acceptableBackground(0xFFFFFF));
    T.check("guard: light grey background rejected", !acceptableBackground(0xEEEEEE));
    T.check("guard: old lime rejected", !acceptableBackground(0xB5F36C));
    T.check("guard: navy accepted", acceptableBackground(NAVY));
    T.check("guard: cyan fill accepted", acceptableBackground(CYAN));
    T.check("guard: magenta fill accepted", acceptableBackground(MAGENTA));
    T.eq("scan finds 0xff literal", 1, literals("x", "int a = 0xffB5F36C;").size());
    T.eq("scan finds #rrggbb", 0xB5F36C, literals("x", "<a v=\"#b5f36c\"/>").get(0).rgb);
    T.eq("scan finds #aarrggbb", 0x10120F, literals("x", "<a v=\"#ff10120f\"/>").get(0).rgb);
    T.eq("scan ignores byte masks", 0, literals("x", "(b[0] & 0xff) == 0x89").size());
    T.check("scan flags Color.WHITE", ANDROID_LIGHT.matcher("v.setBackgroundColor(Color.WHITE);").find());
    T.check("scan flags light theme parent", ANDROID_LIGHT.matcher("parent=\"android:style/Theme.Material.Light\"").find());
    T.check("scan allows dark theme parent", !ANDROID_LIGHT.matcher("parent=\"android:style/Theme.Material.NoActionBar\"").find());
    T.eq("background roles parsed", Arrays.asList("CARD"), backgroundRoles("b.setBackground(ui.bg(Ui.CARD, 18));"));
  }

  public static void main(String[] args) throws Exception {
    selfTest();

    Path uiPath = MAIN.resolve("java/com/victorious/bots/Ui.java");
    Path colorsPath = MAIN.resolve("res/values/colors.xml");
    Path stylesPath = MAIN.resolve("res/values/styles.xml");
    Map<String, Integer> ui = uiConstants(read(uiPath));
    Map<String, Integer> xml = xmlColors(read(colorsPath));

    for (String k : new String[] {"BG", "CARD", "SURFACE_HI", "USER_BUBBLE", "FG", "MUTED", "ACCENT", "ACCENT2", "WARN", "BAD"}) {
      T.check("Ui." + k + " defined", ui.containsKey(k));
      T.check("colors.xml vb_" + k.toLowerCase(Locale.ROOT) + " defined", xml.containsKey(k));
      T.eq("Ui." + k + " matches colors.xml", xml.get(k), ui.get(k));
    }

    T.eq("palette: background is #0B0F1A", NAVY, ui.get("BG"));
    T.eq("palette: primary accent is cyan #00E5FF", CYAN, ui.get("ACCENT"));
    T.eq("palette: secondary accent is magenta #FF2BD6", MAGENTA, ui.get("ACCENT2"));
    T.eq("palette: colors.xml background", NAVY, xml.get("BG"));
    T.eq("palette: colors.xml cyan", CYAN, xml.get("ACCENT"));
    T.eq("palette: colors.xml magenta", MAGENTA, xml.get("ACCENT2"));

    List<Hit> all = new ArrayList<>();
    for (Path p : sources()) {
      String rel = MAIN.relativize(p).toString();
      String text = read(p);
      List<Hit> hits = literals(rel, text);
      all.addAll(hits);

      for (Hit h : hits) if (BANNED.contains(h.rgb)) T.check("no old lime/olive colour at " + h, false);
      Matcher bad = ANDROID_LIGHT.matcher(text);
      if (bad.find()) T.check("no framework/light colour constants or light themes in " + rel + " (" + bad.group() + ")", false);

      boolean allowedHome = p.equals(uiPath) || p.equals(colorsPath);
      if (!allowedHome) {
        for (Hit h : hits) T.check("colour literals live only in Ui.java / colors.xml, found " + h, false);
      }
      if (rel.endsWith(".java")) {
        String[] lines = text.split("\n", -1);
        for (Hit h : hits) {
          if (BG_CALL.matcher(lines[h.line - 1]).find()) T.check("literal background at " + h + " is a dark surface or neon accent", acceptableBackground(h.rgb));
        }
        for (int i = 0; i < lines.length; i++) {
          for (String role : backgroundRoles(lines[i])) {
            Integer c = ui.get(role);
            if (c == null) continue;
            T.check(rel + ":" + (i + 1) + " background role " + role + " is a dark surface or neon accent (#" + String.format("%06X", c) + ")", acceptableBackground(c));
            T.check(rel + ":" + (i + 1) + " background role " + role + " is not a text-only role", !TEXT_ROLES.contains(role) || role.startsWith("ACCENT"));
          }
        }
      }
    }
    T.check("scanned colour literals (" + all.size() + ") include no banned colour", all.stream().noneMatch(h -> BANNED.contains(h.rgb)));

    for (Map.Entry<String, Integer> e : ui.entrySet()) {
      if (!TEXT_ROLES.contains(e.getKey())) {
        T.check("background colour " + e.getKey() + " #" + String.format("%06X", e.getValue()) + " is dark (luminance <= " + BG_MAX_LUMINANCE + ")", luminance(e.getValue()) <= BG_MAX_LUMINANCE);
      }
    }

    String styles = read(stylesPath);
    for (String attr : new String[] {"windowBackground", "statusBarColor", "navigationBarColor", "colorBackground", "colorBackgroundFloating"}) {
      Matcher m = Pattern.compile("name=\"android:" + attr + "\">@color/vb_([a-z0-9_]+)<").matcher(styles);
      boolean any = false;
      while (m.find()) {
        any = true;
        Integer c = xml.get(m.group(1).toUpperCase(Locale.ROOT));
        T.check("styles.xml " + attr + " -> vb_" + m.group(1) + " is a dark surface", c != null && luminance(c) <= BG_MAX_LUMINANCE);
      }
      T.check("styles.xml sets " + attr, any);
    }
    T.check("styles.xml status bar is the dark background", styles.contains("name=\"android:statusBarColor\">@color/vb_bg<"));
    T.check("styles.xml navigation bar is the dark background", styles.contains("name=\"android:navigationBarColor\">@color/vb_bg<"));
    T.check("styles.xml status bar icons not light-mode", styles.contains("name=\"android:windowLightStatusBar\">false<"));
    T.check("styles.xml accent is cyan", styles.contains("name=\"android:colorAccent\">@color/vb_accent<"));
    T.check("styles.xml has no light parent theme", !ANDROID_LIGHT.matcher(styles).find());

    int[] surfaces = {ui.get("BG"), ui.get("CARD"), ui.get("SURFACE_HI"), ui.get("USER_BUBBLE")};
    String[] surfaceNames = {"BG", "CARD", "SURFACE_HI", "USER_BUBBLE"};
    for (String t : TEXT_ROLES) {
      for (int i = 0; i < surfaces.length; i++) {
        double r = contrast(ui.get(t), surfaces[i]);
        T.check(String.format(Locale.ROOT, "contrast %s on %s is %.2f (WCAG AA body text >= %.1f)", t, surfaceNames[i], r, AA_BODY), r >= AA_BODY);
      }
    }
    T.check("primary button text (BG) on cyan fill has AA contrast", contrast(ui.get("BG"), ui.get("ACCENT")) >= AA_BODY);

    String uiSrc = read(uiPath);
    T.check("Ui.java primary action uses cyan", uiSrc.contains("bg(ACCENT, 16)"));
    T.check("Ui.java secondary action uses magenta border", uiSrc.contains("bg(SURFACE_HI, 16, ACCENT2)"));

    T.done();
  }
}
