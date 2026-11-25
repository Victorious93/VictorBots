package com.victorious.bots;

import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Pure logic for per-bot folders (roadmap item 7, PR order step 8). The owner picks ONE folder once through the
 * Storage Access Framework; each bot then gets its own subfolder {@code <bot-name>-<short-uid>} inside it, and
 * bot-produced files and chat exports are written only there. Nothing here touches Android: the SAF calls live in
 * {@code BotFolderAccess}.
 *
 * <p>Rules this class enforces:
 *
 * <ul>
 *   <li>With no folder chosen nothing is written ({@link State#NONE}).
 *   <li>A chosen folder whose grant Android no longer lists is reported as {@link State#REVOKED}, never silently
 *       re-requested or replaced.
 *   <li>A bot name or file name can never escape its folder: separators, traversal, control characters, leading
 *       dots and reserved names are neutralised and the result always passes {@link #safeSegment}.
 *   <li>The chosen location is a local device grant. It lives in app preferences, not the database, so backup
 *       exports never include it.
 * </ul>
 */
public final class BotFolders {
  /** Preferences key holding the chosen tree URI (in the "settings" preferences, never in the database). */
  public static final String PREF_TREE = "bot_folder_tree";
  public static final int MAX_NAME = 40, MAX_FILE = 120, UID_CHARS = 8;
  /** Largest single text file this feature writes (a chat export or a saved message). */
  public static final int MAX_WRITE_BYTES = 4 * 1024 * 1024;

  private static final String[] RESERVED = {"con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"};

  private BotFolders() {}

  public enum State {
    NONE,
    OK,
    REVOKED
  }

  /** Grant state from the stored tree URI and the URIs Android currently lists as persisted read+write grants. */
  public static State state(String storedTree, Collection<String> persistedWritable) {
    if (storedTree == null || storedTree.trim().isEmpty()) return State.NONE;
    return persistedWritable != null && persistedWritable.contains(storedTree) ? State.OK : State.REVOKED;
  }

  public static String describe(State s) {
    switch (s) {
      case OK:
        return "Bot folders are on. Each bot writes only inside its own subfolder of the folder you chose.";
      case REVOKED:
        return "VictorBots no longer has access to the folder you chose (the permission was removed in Android settings or the folder is gone). Nothing will be written until you choose a folder again in More → Bot folders.";
      default:
        return "No folder chosen. Nothing is written to shared storage until you choose one in More → Bot folders.";
    }
  }

  /** True for a single, harmless path segment: no separators, no traversal, no control characters, bounded. */
  public static boolean safeSegment(String n) {
    if (n == null || n.isEmpty() || n.length() > MAX_FILE) return false;
    if (n.startsWith(".") || n.startsWith(" ") || n.endsWith(" ") || n.endsWith(".")) return false;
    if (n.contains("/") || n.contains("\\") || n.contains("..")) return false;
    for (int i = 0; i < n.length(); i++) {
      char c = n.charAt(i);
      if (c < 0x20 || c == 0x7f || ":*?\"<>|".indexOf(c) >= 0) return false;
    }
    String stem = n.toLowerCase(Locale.ROOT);
    int dot = stem.indexOf('.');
    if (dot > 0) stem = stem.substring(0, dot);
    for (String r : RESERVED) if (stem.equals(r)) return false;
    return n.matches("[\\p{L}\\p{N}._ +()-]+");
  }

  /** Keeps letters, digits, space, '.', '_', '-', '+', '(' and ')'; everything else becomes '-'. Bounded. */
  static String clean(String s, int max) {
    StringBuilder b = new StringBuilder();
    String in = s == null ? "" : java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC);
    for (int i = 0; i < in.length(); ) {
      int cp = in.codePointAt(i);
      i += Character.charCount(cp);
      boolean ok = Character.isLetterOrDigit(cp) || cp == ' ' || cp == '.' || cp == '_' || cp == '-' || cp == '+' || cp == '(' || cp == ')';
      b.append(ok ? new String(Character.toChars(cp)) : "-");
    }
    String r = b.toString().replaceAll("\\.{2,}", ".").replaceAll("-{2,}", "-").replaceAll(" {2,}", " ");
    r = r.replaceAll("^[ .\\-]+", "").replaceAll("[ .\\-]+$", "");
    if (r.length() > max) r = r.substring(0, max).replaceAll("[ .\\-]+$", "");
    return r;
  }

  /** Short, folder-safe form of a bot uid: lowercase letters and digits only. */
  static String shortUid(String uid) {
    String u = uid == null ? "" : uid.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    return u.length() > UID_CHARS ? u.substring(0, UID_CHARS) : u;
  }

  /** The subfolder for one bot: {@code <bot-name>-<short-uid>}. Always a {@link #safeSegment}. */
  public static String folderName(String botName, String uid) {
    String name = clean(botName, MAX_NAME);
    if (name.isEmpty() || !safeSegment(name)) name = "bot";
    String u = shortUid(uid);
    String out = u.isEmpty() ? name : name + "-" + u;
    if (!safeSegment(out)) out = "bot" + (u.isEmpty() ? "" : "-" + u);
    return out;
  }

  /** A file name to create inside a bot folder. Keeps a short extension; falls back to {@code fallback}. */
  public static String fileName(String requested, String fallback) {
    String r = requested == null ? "" : requested;
    int slash = Math.max(r.lastIndexOf('/'), r.lastIndexOf('\\'));
    if (slash >= 0) r = r.substring(slash + 1);
    String c = clean(r, MAX_FILE);
    if (!c.isEmpty() && safeSegment(c)) return c;
    String f = clean(fallback, MAX_FILE);
    return !f.isEmpty() && safeSegment(f) ? f : "victorbots-file.txt";
  }

  /** {@code chat-<title>-<yyyyMMdd-HHmm>.md} in the given zone. */
  public static String chatExportName(String chatTitle, long when, TimeZone zone) {
    SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT);
    f.setTimeZone(zone);
    String t = clean(chatTitle, 40);
    return fileName("chat-" + (t.isEmpty() ? "conversation" : t) + "-" + f.format(new Date(when)) + ".md", "chat.md");
  }

  /** {@code message-<yyyyMMdd-HHmmss>.md} for one saved message. */
  public static String messageName(long when, TimeZone zone) {
    SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT);
    f.setTimeZone(zone);
    return "message-" + f.format(new Date(when)) + ".md";
  }

  /**
   * Plain Markdown export of the visible conversation ({@link ChatHistory#visible}): replaced, pending and failed
   * turns are left out. Text is copied as-is; nothing is sent anywhere.
   */
  public static String chatExport(String botName, String chatTitle, List<ChatHistory.Msg> all, long when, TimeZone zone) {
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm zzz", Locale.ENGLISH);
    f.setTimeZone(zone);
    StringBuilder b = new StringBuilder();
    b.append("# ").append(oneLine(chatTitle, "Conversation")).append("\n\n");
    b.append("Bot: ").append(oneLine(botName, "Bot")).append("  \nExported: ").append(f.format(new Date(when))).append(" by VictorBots\n");
    for (ChatHistory.Msg m : ChatHistory.visible(all)) {
      if (!"done".equals(m.status)) continue;
      String who = "user".equals(m.role) ? "You" : "assistant".equals(m.role) ? oneLine(botName, "Bot") : m.role;
      b.append("\n## ").append(who).append("\n\n").append(m.body.trim()).append("\n");
      if (b.length() > MAX_WRITE_BYTES / 4) {
        b.append("\n…[export cut at the size limit]\n");
        break;
      }
    }
    return b.toString();
  }

  private static String oneLine(String s, String fallback) {
    String t = s == null ? "" : s.replaceAll("[\\r\\n]+", " ").trim();
    return t.isEmpty() ? fallback : t;
  }
}
