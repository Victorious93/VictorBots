package com.victorious.bots;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Pure-Java validation shared by the UI and transports. */
public final class Rules {
  public static final int MAX_INPUT = 12000, MAX_INSTRUCTIONS = 8000, MAX_CONTEXT = 40000;

  public static String endpoint(String raw) {
    try {
      URI uri = new URI(raw.trim());
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getFragment() != null
          || uri.getQuery() != null)
        throw new IllegalArgumentException(
            "Use an HTTPS URL without credentials, query, or fragment.");
      if (uri.getPort() == 0 || uri.getPort() > 65535)
        throw new IllegalArgumentException("Invalid port.");
      String path = uri.getPath();
      if (path == null || !path.endsWith("/chat/completions"))
        throw new IllegalArgumentException("Enter the full endpoint ending in /chat/completions.");
      return uri.toASCIIString();
    } catch (java.net.URISyntaxException e) {
      throw new IllegalArgumentException("Invalid provider URL.");
    }
  }

  public static String pin(String raw) {
    String p = raw.replace(":", "").trim().toUpperCase(java.util.Locale.ROOT);
    if (!p.matches("[0-9A-F]{64}"))
      throw new IllegalArgumentException("Certificate SHA-256 must contain 64 hexadecimal digits.");
    return p;
  }

  public static void length(String text, int max, String label) {
    if (text == null || text.trim().isEmpty())
      throw new IllegalArgumentException(label + " is required.");
    if (text.length() > max)
      throw new IllegalArgumentException(label + " exceeds " + max + " characters.");
  }

  public static final class Turn {
    public final String role, content;

    public Turn(String r, String c) {
      role = r;
      content = c;
    }
  }

  /** Keeps the latest input and whole recent turns. Never silently clips the current request. */
  public static List<Turn> context(List<Turn> history, String instruction, String input) {
    length(instruction, MAX_INSTRUCTIONS, "Bot instructions");
    length(input, MAX_INPUT, "Message");
    int remaining = MAX_CONTEXT - instruction.length() - input.length();
    List<Turn> kept = new ArrayList<>();
    for (int i = history.size() - 1; i >= 0; i--) {
      Turn t = history.get(i);
      if (!t.role.equals("user") && !t.role.equals("assistant")) continue;
      if (t.content.length() > remaining) break;
      kept.add(0, t);
      remaining -= t.content.length();
    }
    while (!kept.isEmpty() && kept.get(0).role.equals("assistant")) kept.remove(0);
    kept.add(0, new Turn("system", instruction));
    kept.add(new Turn("user", input));
    return kept;
  }

  private Rules() {}
}
