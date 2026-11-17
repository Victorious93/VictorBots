import com.victorious.bots.*;
import java.util.*;

/** Markdown subset: structure, escaping, unclosed constructs, hostile links and bounded work. */
public final class MarkdownTest {
  /** Compact form: PARA{style:text}{style:text@url}; code blocks as CODE[lang]<code>. */
  static String fmt(List<Markdown.Block> blocks) {
    StringBuilder b = new StringBuilder();
    for (Markdown.Block k : blocks) {
      if (b.length() > 0) b.append(" ; ");
      switch (k.type) {
        case CODE_BLOCK: b.append("CODE[").append(k.lang).append(k.open ? ",open" : "").append("]<").append(k.code).append(">"); break;
        case PARAGRAPH: b.append("P"); spans(b, k); break;
        case HEADING: b.append("H"); spans(b, k); break;
        case BULLET: b.append("B").append(k.depth); spans(b, k); break;
        case NUMBERED: b.append("N").append(k.number).append("."); spans(b, k); break;
        default: throw new IllegalStateException("type " + k.type);
      }
    }
    return b.toString();
  }

  static void spans(StringBuilder b, Markdown.Block k) {
    for (Markdown.Span s : k.spans) {
      b.append("{").append(s.style).append(":").append(s.text);
      if (s.url != null) b.append("@").append(s.url);
      if (s.mismatch) b.append("!");
      b.append("}");
    }
  }

  static String f(String md) {
    return fmt(Markdown.parse(md));
  }

  static boolean anyLink(String md) {
    for (Markdown.Block k : Markdown.parse(md)) for (Markdown.Span s : k.spans) if (s.isLink()) return true;
    return false;
  }

  static String repeat(String s, int n) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < n; i++) b.append(s);
    return b.toString();
  }

  public static void main(String[] args) {
    // ---- inline styles
    T.eq("plain text", "P{0:hello world}", f("hello world"));
    T.eq("bold", "P{0:a }{1:bold}{0: b}", f("a **bold** b"));
    T.eq("bold with underscores", "P{1:bold}", f("__bold__"));
    T.eq("italic", "P{0:a }{2:it}{0: b}", f("a *it* b"));
    T.eq("italic with underscores", "P{2:it}", f("_it_"));
    T.eq("bold italic", "P{3:both}", f("***both***"));
    T.eq("nested italic in bold", "P{1:bold }{3:and it}{1: more}", f("**bold *and it* more**"));
    T.eq("inline code", "P{0:run }{4:ls -la}{0: now}", f("run `ls -la` now"));
    T.eq("code content is not styled", "P{4:**not bold**}", f("`**not bold**`"));
    T.eq("code span with double backticks may hold a backtick", "P{4:a`b}", f("``a`b``"));
    T.eq("snake_case words are not italic", "P{0:use my_var_name here}", f("use my_var_name here"));
    T.eq("math stars are literal", "P{0:2 * 3 * 4}", f("2 * 3 * 4"));
    T.eq("single newline stays a line break", "P{0:one\ntwo}", f("one\ntwo"));
    T.eq("blank line splits paragraphs", "P{0:one} ; P{0:two}", f("one\n\ntwo"));

    // ---- escaping
    T.eq("escaped stars are literal", "P{0:*not italic*}", f("\\*not italic\\*"));
    T.eq("escaped backtick is literal", "P{0:a`b}", f("a\\`b"));
    T.eq("escaped bracket does not start a markdown link (the visible url is still a bare link)", "P{0:[x](}{0:https://a.example@https://a.example}{0:)}", f("\\[x\\](https://a.example)"));
    T.eq("backslash before a letter is kept", "P{0:C:\\temp}", f("C:\\temp"));
    T.eq("angle brackets and HTML are plain text", "P{0:<b>hi</b> <script>x</script>}", f("<b>hi</b> <script>x</script>"));
    T.check("HTML is kept as literal text, not interpreted", f("<a href=\"https://example.com\">x</a>").startsWith("P{0:<a href=\""));

    // ---- unclosed constructs (streamed replies stop mid-way)
    T.eq("unclosed bold is literal", "P{0:**oops}", f("**oops"));
    T.eq("unclosed italic is literal", "P{0:*oops}", f("*oops"));
    T.eq("unclosed backtick is literal", "P{0:`oops}", f("`oops"));
    T.eq("unclosed markdown link stays literal (the bare url is still a visible link)", "P{0:[oops](}{0:https://a.example@https://a.example}", f("[oops](https://a.example"));
    T.eq("unclosed fence runs to the end and is marked open", "CODE[java,open]<int a;\nint b;>", f("```java\nint a;\nint b;"));
    T.eq("empty input", "", f(""));
    T.eq("null input", "", fmt(Markdown.parse(null)));

    // ---- blocks
    T.eq("code block", "P{0:before} ; CODE[python]<x = 1\n\ny = 2> ; P{0:after}", f("before\n```python\nx = 1\n\ny = 2\n```\nafter"));
    T.eq("code block keeps markup literally", "CODE[]<**a** [b](https://x.example)>", f("```\n**a** [b](https://x.example)\n```"));
    T.eq("tilde fence", "CODE[]<a>", f("~~~\na\n~~~"));
    T.eq("a longer fence is closed by a matching fence only", "CODE[]<```\ninner\n```>", f("````\n```\ninner\n```\n````"));
    T.eq("empty code block", "CODE[]<>", f("```\n```"));
    T.eq("heading is bold text", "H{1:Title}", f("# Title"));
    T.eq("hash without space is text", "P{0:#nope}", f("#nope"));
    T.eq("bullet list", "B0{0:one} ; B0{0:two}", f("- one\n- two"));
    T.eq("star and plus bullets", "B0{0:a} ; B0{0:b}", f("* a\n+ b"));
    T.eq("nested bullet depth", "B0{0:a} ; B1{0:b}", f("- a\n  - b"));
    T.eq("numbered list keeps numbers", "N1.{0:first} ; N2.{0:second}", f("1. first\n2) second"));
    T.eq("list item with inline markup", "B0{1:b}{0: and }{2:i}", f("- **b** and *i*"));
    T.eq("a bold line is not a bullet", "P{1:bold}{0: text}", f("**bold** text"));

    // ---- links: allowed
    T.eq("https link", "P{0:see }{0:docs@https://example.com/a?b=1#c}", f("see [docs](https://example.com/a?b=1#c)"));
    T.eq("http link", "P{0:site@http://example.com}", f("[site](http://example.com)"));
    T.eq("scheme case is normalised", "P{0:x@https://Example.com}", f("[x](HTTPS://Example.com)"));
    T.eq("link text keeps styles", "P{1:bold link@https://example.com}", f("[**bold link**](https://example.com)"));
    T.eq("bare url becomes a link", "P{0:go }{0:https://example.com/x@https://example.com/x}{0: now}", f("go https://example.com/x now"));
    T.eq("bare url drops trailing punctuation", "P{0:https://example.com/x@https://example.com/x}{0:.}", f("https://example.com/x."));
    T.eq("url with balanced parens", "P{0:https://en.example.org/wiki/A_(b)@https://en.example.org/wiki/A_(b)}", f("https://en.example.org/wiki/A_(b)"));
    T.eq("port and ipv6 are allowed", "P{0:p@http://localhost:8080/x}", f("[p](http://localhost:8080/x)"));
    T.check("ipv6 host allowed", Markdown.safeUrl("http://[::1]:8080/") != null);

    // ---- links: malicious
    String[] bad = {
      "javascript:alert(1)", "JaVaScRiPt:alert(1)", "data:text/html;base64,PHNjcmlwdD4=", "file:///etc/passwd", "content://com.victorious.bots/x",
      "intent://scan/#Intent;scheme=zxing;end", "market://details?id=x", "tel:+10000000", "sms:+1000", "mailto:a@b.example", "ftp://example.com/x",
      "vbscript:x", "about:blank", "//evil.example/x", "/relative/path", "example.com", "https:/example.com", "https:example.com",
      "https://user:pass@example.com/", "https://bank.example.com@evil.example/", "https://@evil.example/", "https://", "https:///x", "https://.example.com/",
      "https://exa mple.com/", "https://example.com/\u0000x", "https://example.com/\tx", "https://example.com/\nx", "https://example.com\\@evil.example/",
      "https://example.com/\u202Eevil", "https://exa\u200Bmple.com/", "https://example.com:99999/", "https://example.com:abc/", "https://example.com:/",
      "https://example.com/<script>", "https://example.com/\"onmouseover=", " javascript:x", "java\nscript:alert(1)", "https://example..com/"
    };
    for (String u : bad) T.check("rejected link target: " + u.replace("\n", "\\n").replace("\t", "\\t").replace("\u0000", "\\0").replace("\u202E", "\\u202E").replace("\u200B", "\\u200B"), Markdown.safeUrl(u) == null);
    T.check("javascript link is not linked", !anyLink("[click](javascript:alert(1))"));
    T.check("javascript link text stays visible as literal text", f("[click](javascript:alert(1))").contains("click"));
    T.check("data link is not linked", !anyLink("[x](data:text/html,<b>)"));
    T.check("credential trick is not linked (markdown form)", !anyLink("[bank](https://bank.example.com@evil.example/)"));
    T.check("credential trick is not linked (bare form)", !anyLink("https://bank.example.com@evil.example/"));
    T.check("bare javascript is not linked", !anyLink("javascript:alert(1)"));
    T.check("markdown link with a space in the target is not formed", !f("[x](https://example.com/a b)").contains("{0:x@"));
    T.check("over-long url is not linked", !anyLink("[x](https://example.com/" + repeat("a", Markdown.MAX_URL) + ")"));
    T.check("url at the limit is fine", Markdown.safeUrl("https://example.com/" + repeat("a", Markdown.MAX_URL - 21)) != null);
    T.check("link inside code is not linked", !anyLink("`[x](https://example.com)`"));
    T.check("link inside a code block is not linked", !anyLink("```\n[x](https://example.com)\n```"));
    T.check("nested link syntax in a label does not nest", f("[[a](https://a.example)](https://b.example)").contains("https://b.example"));

    // ---- mismatching link text
    T.check("label showing another address is flagged", f("[https://bank.example](https://evil.example/)").contains("!"));
    T.check("label with another domain is flagged", f("[bank.example](https://evil.example/)").contains("!"));
    T.check("matching label is not flagged", !f("[https://example.com/x](https://example.com/x)").contains("!"));
    T.check("www difference is not flagged", !f("[www.example.com](https://example.com/x)").contains("!"));
    T.check("ordinary words are not flagged", !f("[the docs](https://example.com/x)").contains("!"));
    T.eq("host helper", "example.com", Markdown.hostOf("https://Example.com:8443/x"));

    // ---- bounded work: hostile input must finish fast and never throw
    long t0 = System.currentTimeMillis();
    String[] hostile = {
      repeat("*", 100000), repeat("**a", 40000), repeat("[", 50000), repeat("[a](", 20000), repeat("`", 100000), repeat("_a ", 40000), repeat("- ", 20000),
      repeat("# ", 20000), repeat("***", 30000), repeat("(", 100000), repeat("a*b ", 30000), repeat("http://", 20000), repeat("\n", 100000), repeat("```\n", 20000),
      repeat("**[`", 30000), "x" + repeat("*a ", 60000) + "y"
    };
    int blocks = 0;
    for (String h : hostile) blocks += Markdown.parse(h).size();
    long ms = System.currentTimeMillis() - t0;
    T.check("hostile inputs parse without error (" + blocks + " blocks)", blocks >= 0);
    T.check("hostile inputs finish quickly (" + ms + " ms)", ms < 5000);
    StringBuilder deep = new StringBuilder();
    for (int i = 0; i < 2000; i++) deep.append("*a ");
    for (int i = 0; i < 2000; i++) deep.append("*");
    T.check("deeply nested emphasis does not overflow the stack", Markdown.parse(deep.toString()).size() >= 1);
    T.check("huge input is capped, not rejected", Markdown.parse(repeat("word ", 300000)).size() >= 1);
    T.check("plain text of a block drops markup", Markdown.parse("**a** `b`").get(0).plain().equals("a b"));

    T.done();
  }
}
