package com.victorious.bots;

import java.util.regex.Pattern;

/** Keeps credentials out of anything shown, logged, stored in job logs or exported. */
public final class Redact {
  private static final Pattern BEARER = Pattern.compile("(?i)((?:authorization:?\\s*(?:(?:bearer|basic)\\s+)?)|(?:bearer\\s+))[A-Za-z0-9._~+/=-]{6,}");
  private static final Pattern KEYLIKE = Pattern.compile("\\b(sk|pk|rk|key|tok|ghp|gho|ghs|xox[bap])[-_][A-Za-z0-9_-]{8,}");
  private static final Pattern KV =
      Pattern.compile("(?i)(api[_-]?key|token|secret|password|passwd)\\s*[=:]\\s*[^\\s\"',;]{4,}");

  public static String text(String s, String... known) {
    if (s == null) return "";
    String out = s;
    for (String k : known) if (k != null && k.length() >= 4) out = out.replace(k, "[redacted]");
    out = BEARER.matcher(out).replaceAll("$1 [redacted]");
    out = KV.matcher(out).replaceAll("$1=[redacted]");
    out = KEYLIKE.matcher(out).replaceAll("[redacted]");
    return out;
  }

  public static String snippet(String s, int max, String... known) {
    String r = text(s, known).replaceAll("\\s+", " ").trim();
    return r.length() > max ? r.substring(0, max) + "…" : r;
  }

  private Redact() {}
}
