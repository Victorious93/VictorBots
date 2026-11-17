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
    /** data: URIs of owner-selected images; empty for text-only turns. */
    public final List<String> images;
    /** Assistant turn that asked for tools (OpenAI "tool_calls" array), else null. */
    public org.json.JSONArray toolCalls;
    /** For role "tool": the id of the call this answers. */
    public String toolCallId = "";

    public Turn(String r, String c) {
      this(r, c, new ArrayList<String>());
    }

    public Turn(String r, String c, List<String> imgs) {
      role = r;
      content = c;
      images = imgs;
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

  /** Result of model-aware context budgeting. */
  public static final class Plan {
    public List<Turn> turns = new ArrayList<>();
    public int omitted, budgetTokens, reserveTokens;
    public long estimatedInputTokens;
    public String basis = "";

    public String summary() {
      return "~"
          + estimatedInputTokens
          + " input tokens (estimate, "
          + basis
          + ") of "
          + (budgetTokens - reserveTokens)
          + " available; "
          + reserveTokens
          + " reserved for the reply"
          + (omitted > 0 ? "; " + omitted + " older message" + (omitted == 1 ? "" : "s") + " omitted" : "");
    }
  }

  public static long tokens(String text, double charsPerToken) {
    if (text == null || text.isEmpty()) return 0;
    return (long) Math.ceil(text.length() / charsPerToken) + 4;
  }

  /**
   * Budgets in tokens. The system block (instructions + memory) and the current input are essential:
   * if they alone do not fit, this throws instead of clipping them. Older whole turns are dropped
   * oldest-first and counted so the owner can see what was left out.
   */
  public static Plan plan(
      List<Turn> history,
      String system,
      String input,
      int budgetTokens,
      int reserveTokens,
      double charsPerToken,
      String basis) {
    length(system, 200000, "System instructions");
    if (input == null || input.trim().isEmpty()) throw new IllegalArgumentException("Message is required.");
    int available = budgetTokens - reserveTokens;
    long essential = tokens(system, charsPerToken) + tokens(input, charsPerToken);
    if (essential > available)
      throw new IllegalArgumentException(
          "The instructions, memory and your message need ~"
              + essential
              + " tokens but only "
              + available
              + " are available ("
              + budgetTokens
              + " budget, "
              + reserveTokens
              + " reserved for the reply). Shorten the message or memory, or raise this profile's"
              + " context limit if the model supports more. Nothing was clipped or sent.");
    Plan p = new Plan();
    p.budgetTokens = budgetTokens;
    p.reserveTokens = reserveTokens;
    p.basis = basis;
    long remaining = available - essential;
    List<Turn> kept = new ArrayList<>();
    int considered = 0;
    for (int i = history.size() - 1; i >= 0; i--) {
      Turn t = history.get(i);
      if (!t.role.equals("user") && !t.role.equals("assistant")) continue;
      considered++;
      long cost = tokens(t.content, charsPerToken);
      if (cost > remaining) break;
      kept.add(0, t);
      remaining -= cost;
    }
    while (!kept.isEmpty() && kept.get(0).role.equals("assistant")) kept.remove(0);
    int total = 0;
    for (Turn t : history) if (t.role.equals("user") || t.role.equals("assistant")) total++;
    p.omitted = total - kept.size();
    kept.add(0, new Turn("system", system));
    kept.add(new Turn("user", input));
    p.turns = kept;
    p.estimatedInputTokens = available - remaining;
    return p;
  }

  private Rules() {}
}
