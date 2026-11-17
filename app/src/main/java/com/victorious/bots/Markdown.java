package com.victorious.bots;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A small, dependency-free Markdown subset for model replies: paragraphs, headings (shown bold), bold, italic,
 * inline code, fenced code blocks, bullet and numbered lists, and http/https links. Pure Java so it is unit-tested
 * on the JVM. It never produces HTML and never decides to open anything: a link is only data ({@link Span#url})
 * and the UI opens it on tap after showing the exact address.
 *
 * <p>Parsing is total: any input, including unclosed markers and fences (a streamed reply stops mid-construct),
 * yields blocks. Unrecognised syntax stays literal text. Work is bounded so hostile input cannot stall the UI.
 */
public final class Markdown {
  public static final int BOLD = 1, ITALIC = 2, CODE = 4;
  public static final int MAX_URL = 2000;
  private static final int MAX_DEPTH = 6;
  private static final int MAX_TEXT = 400_000;
  private static final int SCAN_BUDGET_PER_BLOCK = 200_000;

  /** A run of text with one style and, for links, a validated absolute http/https address. */
  public static final class Span {
    public final String text;
    public final int style;
    public final String url;
    /** True when the visible link text looks like a different web address than the one it opens. */
    public final boolean mismatch;

    Span(String text, int style, String url, boolean mismatch) {
      this.text = text;
      this.style = style;
      this.url = url;
      this.mismatch = mismatch;
    }

    public boolean isLink() {
      return url != null;
    }
  }

  public enum Type {
    PARAGRAPH,
    HEADING,
    BULLET,
    NUMBERED,
    CODE_BLOCK
  }

  public static final class Block {
    public final Type type;
    /** Inline content (empty for code blocks). */
    public final List<Span> spans;
    /** Raw code for CODE_BLOCK; otherwise empty. */
    public final String code;
    /** Info string after the opening fence (language hint, display only). */
    public final String lang;
    /** Nesting depth for list items, 0-based. */
    public final int depth;
    /** The number shown for NUMBERED items. */
    public final int number;
    /** True when a fence was still open at the end of the text (for example a reply cut off mid-stream). */
    public final boolean open;

    Block(Type type, List<Span> spans, String code, String lang, int depth, int number, boolean open) {
      this.type = type;
      this.spans = spans;
      this.code = code;
      this.lang = lang;
      this.depth = depth;
      this.number = number;
      this.open = open;
    }

    /** Plain text of the block, without markup, for copying a single block or measuring. */
    public String plain() {
      if (type == Type.CODE_BLOCK) return code;
      StringBuilder b = new StringBuilder();
      for (Span s : spans) b.append(s.text);
      return b.toString();
    }
  }

  // ------------------------------------------------------------------ blocks

  public static List<Block> parse(String input) {
    List<Block> out = new ArrayList<>();
    if (input == null || input.isEmpty()) return out;
    String text = input.length() > MAX_TEXT ? input.substring(0, MAX_TEXT) : input;
    String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    List<String> para = new ArrayList<>();
    int i = 0;
    while (i < lines.length) {
      String line = lines[i];
      String fence = fenceOf(line);
      if (fence != null) {
        flush(para, out);
        String lang = line.trim().substring(fence.length()).trim();
        if (lang.length() > 32) lang = lang.substring(0, 32);
        StringBuilder code = new StringBuilder();
        boolean closed = false, first = true;
        i++;
        while (i < lines.length) {
          if (closesFence(lines[i], fence)) {
            closed = true;
            i++;
            break;
          }
          if (!first) code.append('\n');
          first = false;
          code.append(lines[i]);
          i++;
        }
        out.add(new Block(Type.CODE_BLOCK, new ArrayList<Span>(), code.toString(), lang, 0, 0, !closed));
        continue;
      }
      if (line.trim().isEmpty()) {
        flush(para, out);
        i++;
        continue;
      }
      int hashes = headingLevel(line);
      if (hashes > 0) {
        flush(para, out);
        String body = line.trim().substring(hashes).trim();
        out.add(new Block(Type.HEADING, inline(body, BOLD), "", "", 0, 0, false));
        i++;
        continue;
      }
      ListItem li = listItem(line);
      if (li != null) {
        flush(para, out);
        out.add(new Block(li.numbered ? Type.NUMBERED : Type.BULLET, inline(li.body, 0), "", "", li.depth, li.number, false));
        i++;
        continue;
      }
      para.add(line);
      i++;
    }
    flush(para, out);
    return out;
  }

  private static void flush(List<String> para, List<Block> out) {
    if (para.isEmpty()) return;
    StringBuilder b = new StringBuilder();
    for (int k = 0; k < para.size(); k++) {
      if (k > 0) b.append('\n');
      b.append(para.get(k));
    }
    para.clear();
    out.add(new Block(Type.PARAGRAPH, inline(b.toString(), 0), "", "", 0, 0, false));
  }

  private static String fenceOf(String line) {
    int lead = 0;
    while (lead < line.length() && lead < 4 && line.charAt(lead) == ' ') lead++;
    if (lead > 3) return null;
    String t = line.substring(lead);
    char c = t.isEmpty() ? 0 : t.charAt(0);
    if (c != '`' && c != '~') return null;
    int n = 0;
    while (n < t.length() && t.charAt(n) == c) n++;
    if (n < 3) return null;
    if (c == '`' && t.indexOf('`', n) >= 0) return null;
    return t.substring(0, n);
  }

  private static boolean closesFence(String line, String fence) {
    String t = line.trim();
    if (t.length() < fence.length()) return false;
    char c = fence.charAt(0);
    for (int k = 0; k < t.length(); k++) if (t.charAt(k) != c) return false;
    return true;
  }

  private static int headingLevel(String line) {
    String t = line.trim();
    int n = 0;
    while (n < t.length() && t.charAt(n) == '#') n++;
    if (n == 0 || n > 6 || n >= t.length() || t.charAt(n) != ' ') return 0;
    return n;
  }

  private static final class ListItem {
    boolean numbered;
    int depth, number;
    String body;
  }

  private static ListItem listItem(String line) {
    int lead = 0;
    while (lead < line.length() && (line.charAt(lead) == ' ' || line.charAt(lead) == '\t')) lead += line.charAt(lead) == '\t' ? 2 : 1;
    String t = line.trim();
    if (t.length() < 2) return null;
    ListItem li = new ListItem();
    li.depth = Math.min(lead / 2, 4);
    char c = t.charAt(0);
    if ((c == '-' || c == '*' || c == '+') && t.charAt(1) == ' ') {
      li.body = t.substring(2).trim();
      return li;
    }
    int d = 0;
    while (d < t.length() && d < 9 && Character.isDigit(t.charAt(d))) d++;
    if (d > 0 && d + 1 < t.length() && (t.charAt(d) == '.' || t.charAt(d) == ')') && t.charAt(d + 1) == ' ') {
      li.numbered = true;
      li.number = Integer.parseInt(t.substring(0, d));
      li.body = t.substring(d + 2).trim();
      return li;
    }
    return null;
  }

  // ------------------------------------------------------------------ inline

  /** Mutable budget so a pathological block cannot make parsing quadratic. */
  private static final class Budget {
    int left = SCAN_BUDGET_PER_BLOCK;
  }

  static List<Span> inline(String s, int baseStyle) {
    List<Span> out = new ArrayList<>();
    if (s.isEmpty()) return out;
    Budget budget = new Budget();
    parseInline(s, baseStyle, 0, out, budget, true);
    if (budget.left < 0) {
      out.clear();
      out.add(new Span(s, baseStyle, null, false));
    }
    return merge(out);
  }

  private static void parseInline(String s, int style, int depth, List<Span> out, Budget budget, boolean allowLinks) {
    StringBuilder lit = new StringBuilder();
    int n = s.length();
    int i = 0;
    while (i < n) {
      if (budget.left < 0) return;
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < n && isEscapable(s.charAt(i + 1))) {
        lit.append(s.charAt(i + 1));
        i += 2;
        continue;
      }
      if (c == '`') {
        int run = runLength(s, i, '`');
        int close = findRun(s, i + run, '`', run, budget);
        if (close >= 0) {
          emit(out, lit, style);
          String code = s.substring(i + run, close);
          if (code.length() > 1 && code.charAt(0) == ' ' && code.charAt(code.length() - 1) == ' ' && !code.trim().isEmpty()) code = code.substring(1, code.length() - 1);
          out.add(new Span(code, style | CODE, null, false));
          i = close + run;
          continue;
        }
        lit.append(s, i, i + run);
        i += run;
        continue;
      }
      if (allowLinks && c == '[') {
        int[] m = matchLink(s, i, budget);
        if (m != null) {
          String label = s.substring(i + 1, m[0]);
          String raw = s.substring(m[0] + 2, m[1]);
          String url = safeUrl(raw);
          if (url != null && !label.trim().isEmpty()) {
            emit(out, lit, style);
            List<Span> inner = new ArrayList<>();
            if (depth < MAX_DEPTH) parseInline(label, style, depth + 1, inner, budget, false);
            else inner.add(new Span(label, style, null, false));
            boolean mismatch = looksLikeDifferentUrl(label, url);
            for (Span sp : merge(inner)) out.add(new Span(sp.text, sp.style, url, mismatch));
            i = m[1] + 1;
            continue;
          }
        }
      }
      if (allowLinks && (c == 'h' || c == 'H') && (i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)))) {
        int end = bareUrlEnd(s, i);
        if (end > i) {
          String url = safeUrl(s.substring(i, end));
          if (url != null) {
            emit(out, lit, style);
            out.add(new Span(s.substring(i, end), style, url, false));
            i = end;
            continue;
          }
        }
      }
      if ((c == '*' || c == '_') && depth < MAX_DEPTH) {
        int run = runLength(s, i, c);
        if (run <= 3 && canOpen(s, i, run, c)) {
          int close = findEmphasisClose(s, i + run, c, run, budget);
          if (close >= 0) {
            emit(out, lit, style);
            int add = run == 1 ? ITALIC : run == 2 ? BOLD : BOLD | ITALIC;
            parseInline(s.substring(i + run, close), style | add, depth + 1, out, budget, allowLinks);
            i = close + run;
            continue;
          }
        }
        lit.append(s, i, i + run);
        i += run;
        continue;
      }
      lit.append(c);
      i++;
    }
    emit(out, lit, style);
  }

  private static void emit(List<Span> out, StringBuilder lit, int style) {
    if (lit.length() == 0) return;
    out.add(new Span(lit.toString(), style, null, false));
    lit.setLength(0);
  }

  private static List<Span> merge(List<Span> in) {
    List<Span> out = new ArrayList<>();
    for (Span s : in) {
      if (s.text.isEmpty()) continue;
      if (!out.isEmpty()) {
        Span p = out.get(out.size() - 1);
        if (p.style == s.style && p.url == null && s.url == null) {
          out.set(out.size() - 1, new Span(p.text + s.text, p.style, null, false));
          continue;
        }
      }
      out.add(s);
    }
    return out;
  }

  private static boolean isEscapable(char c) {
    return "\\`*_[]()#+-.!<>~|{}".indexOf(c) >= 0;
  }

  private static int runLength(String s, int i, char c) {
    int k = i;
    while (k < s.length() && s.charAt(k) == c) k++;
    return k - i;
  }

  /** Index of a run of exactly {@code len} copies of {@code c} at or after {@code from}, or -1. */
  private static int findRun(String s, int from, char c, int len, Budget budget) {
    int k = from;
    while (k < s.length()) {
      budget.left--;
      if (budget.left < 0) return -1;
      if (s.charAt(k) == c) {
        int r = runLength(s, k, c);
        if (r == len) return k;
        k += r;
      } else k++;
    }
    return -1;
  }

  private static boolean canOpen(String s, int i, int run, char c) {
    int after = i + run;
    if (after >= s.length() || Character.isWhitespace(s.charAt(after))) return false;
    if (c == '_' && i > 0 && Character.isLetterOrDigit(s.charAt(i - 1))) return false;
    return true;
  }

  private static int findEmphasisClose(String s, int from, char c, int len, Budget budget) {
    int k = from;
    while (k < s.length()) {
      budget.left--;
      if (budget.left < 0) return -1;
      char ch = s.charAt(k);
      if (ch == '\\') {
        k += 2;
        continue;
      }
      if (ch == '`') {
        int r = runLength(s, k, '`');
        int close = findRun(s, k + r, '`', r, budget);
        k = close >= 0 ? close + r : k + r;
        continue;
      }
      if (ch == c) {
        int r = runLength(s, k, c);
        if (r == len && k > from && !Character.isWhitespace(s.charAt(k - 1))) {
          if (c != '_' || k + r >= s.length() || !Character.isLetterOrDigit(s.charAt(k + r))) return k;
        }
        k += r;
        continue;
      }
      k++;
    }
    return -1;
  }

  /** For "[label](url)" starting at {@code i}: returns {indexOfClosingBracket, indexOfClosingParen} or null. */
  private static int[] matchLink(String s, int i, Budget budget) {
    int depth = 0;
    int k = i;
    int close = -1;
    while (k < s.length()) {
      budget.left--;
      if (budget.left < 0) return null;
      char c = s.charAt(k);
      if (c == '\\') {
        k += 2;
        continue;
      }
      if (c == '[') depth++;
      else if (c == ']') {
        depth--;
        if (depth == 0) {
          close = k;
          break;
        }
      }
      k++;
    }
    if (close < 0 || close + 1 >= s.length() || s.charAt(close + 1) != '(') return null;
    int p = close + 2;
    int parens = 1;
    while (p < s.length()) {
      budget.left--;
      if (budget.left < 0) return null;
      char c = s.charAt(p);
      if (c == '\n' || c == ' ') return null;
      if (c == '(') parens++;
      else if (c == ')') {
        parens--;
        if (parens == 0) return new int[] {close, p};
      }
      p++;
    }
    return null;
  }

  private static int bareUrlEnd(String s, int i) {
    String rest = s.substring(i, Math.min(s.length(), i + 8)).toLowerCase(Locale.ROOT);
    if (!rest.startsWith("http://") && !rest.startsWith("https://")) return -1;
    int k = i;
    while (k < s.length() && k - i < MAX_URL + 1) {
      char c = s.charAt(k);
      if (Character.isWhitespace(c) || c == '<' || c == '>' || c == '"' || c == '`' || c == '[' || c == ']') break;
      k++;
    }
    while (k > i && ".,;:!?'*_)".indexOf(s.charAt(k - 1)) >= 0) {
      if (s.charAt(k - 1) == ')' && balanced(s.substring(i, k))) break;
      k--;
    }
    return k;
  }

  private static boolean balanced(String t) {
    int o = 0, c = 0;
    for (int k = 0; k < t.length(); k++) {
      if (t.charAt(k) == '(') o++;
      else if (t.charAt(k) == ')') c++;
    }
    return o >= c;
  }

  // ------------------------------------------------------------------ links

  /**
   * Returns a normalised absolute http/https address, or null when it must not become a link. Rejects every other
   * scheme (javascript:, data:, file:, content:, intent:, market:, tel:, ...), relative and scheme-relative
   * addresses, embedded credentials ("https://bank.com@evil.com"), empty hosts, whitespace, control and invisible
   * or bidirectional-override characters, backslashes, and over-long addresses.
   */
  public static String safeUrl(String raw) {
    if (raw == null) return null;
    String u = raw.trim();
    if (u.isEmpty() || u.length() > MAX_URL) return null;
    for (int k = 0; k < u.length(); k++) {
      char c = u.charAt(k);
      if (c <= 0x20 || c == 0x7f || c == '\\' || c == '<' || c == '>' || c == '"' || c == '`') return null;
      if (c >= 0x80 && !Character.isLetterOrDigit(c) && "-._~".indexOf(c) < 0) {
        int t = Character.getType(c);
        if (t == Character.FORMAT || t == Character.CONTROL || t == Character.SPACE_SEPARATOR || t == Character.LINE_SEPARATOR || t == Character.PARAGRAPH_SEPARATOR || t == Character.PRIVATE_USE || t == Character.UNASSIGNED || t == Character.SURROGATE)
          return null;
      }
    }
    int colon = u.indexOf(':');
    if (colon <= 0) return null;
    String scheme = u.substring(0, colon).toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https")) return null;
    String rest = u.substring(colon + 1);
    if (!rest.startsWith("//")) return null;
    rest = rest.substring(2);
    int end = rest.length();
    for (int k = 0; k < rest.length(); k++) {
      char c = rest.charAt(k);
      if (c == '/' || c == '?' || c == '#') {
        end = k;
        break;
      }
    }
    String authority = rest.substring(0, end);
    if (authority.isEmpty() || authority.indexOf('@') >= 0) return null;
    String host = authority;
    int portAt = authority.lastIndexOf(':');
    if (authority.startsWith("[")) {
      int rb = authority.indexOf(']');
      if (rb < 0) return null;
      host = authority.substring(0, rb + 1);
      if (rb + 1 < authority.length()) {
        if (authority.charAt(rb + 1) != ':') return null;
        if (!validPort(authority.substring(rb + 2))) return null;
      }
    } else if (portAt >= 0) {
      host = authority.substring(0, portAt);
      if (!validPort(authority.substring(portAt + 1))) return null;
    }
    if (host.isEmpty() || host.startsWith(".") || host.endsWith(".") || host.contains("..")) return null;
    return scheme + "://" + rest;
  }

  private static boolean validPort(String p) {
    if (p.isEmpty() || p.length() > 5) return false;
    for (int k = 0; k < p.length(); k++) if (!Character.isDigit(p.charAt(k))) return false;
    int v = Integer.parseInt(p);
    return v >= 1 && v <= 65535;
  }

  /** The host of an address {@link #safeUrl} accepted (lower-case, no port), or "" when there is none. */
  public static String hostOf(String url) {
    if (url == null) return "";
    int s = url.indexOf("//");
    if (s < 0) return "";
    String r = url.substring(s + 2);
    int e = r.length();
    for (int k = 0; k < r.length(); k++) {
      char c = r.charAt(k);
      if (c == '/' || c == '?' || c == '#') {
        e = k;
        break;
      }
    }
    String a = r.substring(0, e);
    if (a.startsWith("[")) {
      int rb = a.indexOf(']');
      return rb < 0 ? "" : a.substring(0, rb + 1).toLowerCase(Locale.ROOT);
    }
    int p = a.lastIndexOf(':');
    return (p >= 0 ? a.substring(0, p) : a).toLowerCase(Locale.ROOT);
  }

  /** Visible text that reads like a web address or domain but names a different host than the link target. */
  static boolean looksLikeDifferentUrl(String label, String url) {
    String l = label.trim().toLowerCase(Locale.ROOT);
    if (l.isEmpty() || l.indexOf(' ') >= 0) return false;
    String shown = null;
    if (l.startsWith("http://") || l.startsWith("https://")) shown = hostOf(l);
    else if (l.matches("(www\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)+(/.*)?")) shown = hostOf("//" + l);
    if (shown == null || shown.isEmpty()) return false;
    String target = hostOf(url);
    if (shown.startsWith("www.")) shown = shown.substring(4);
    if (target.startsWith("www.")) target = target.substring(4);
    return !shown.equals(target);
  }

  private Markdown() {}
}
