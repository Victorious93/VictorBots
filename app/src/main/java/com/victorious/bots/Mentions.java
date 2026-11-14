package com.victorious.bots;

import java.util.*;
import java.util.regex.Pattern;

/** @Name / @everyone parsing. Names are matched longest-first with a word boundary after them. */
public final class Mentions {
  public static final class Member {
    public final long id;
    public final String name;
    public final boolean muted;

    public Member(long id, String name, boolean muted) {
      this.id = id;
      this.name = name;
      this.muted = muted;
    }
  }

  private static final Pattern EVERYONE = Pattern.compile("(^|[^\\p{L}\\p{N}_])@everyone(?![\\p{L}\\p{N}_])", Pattern.CASE_INSENSITIVE);

  public static boolean everyone(String text) {
    return EVERYONE.matcher(text).find();
  }

  /** Members named in {@code text}, in order of first appearance. @everyone returns all unmuted members. */
  public static List<Member> find(String text, List<Member> members, long exclude) {
    List<Member> out = new ArrayList<>();
    if (everyone(text)) {
      for (Member m : members) if (m.id != exclude && !m.muted) out.add(m);
      return out;
    }
    String lower = text.toLowerCase(Locale.ROOT);
    List<Member> byLen = new ArrayList<>(members);
    byLen.sort((a, b) -> b.name.length() - a.name.length());
    final Map<Long, Integer> at = new HashMap<>();
    for (Member m : byLen) {
      if (m.id == exclude) continue;
      String needle = "@" + m.name.toLowerCase(Locale.ROOT);
      int from = 0;
      while (true) {
        int i = lower.indexOf(needle, from);
        if (i < 0) break;
        int end = i + needle.length();
        boolean boundaryAfter = end >= lower.length() || !Character.isLetterOrDigit(lower.charAt(end)) && lower.charAt(end) != '_';
        boolean boundaryBefore = i == 0 || !Character.isLetterOrDigit(lower.charAt(i - 1)) && lower.charAt(i - 1) != '_';
        if (boundaryAfter && boundaryBefore && !at.containsKey(m.id)) {
          // A longer name already claimed this span (e.g. "@Android Engineer" vs "@Android").
          boolean claimed = false;
          for (Map.Entry<Long, Integer> en : at.entrySet()) {
            Member o = byId(members, en.getKey());
            if (o != null && en.getValue() <= i && i < en.getValue() + o.name.length() + 1) claimed = true;
          }
          if (!claimed) at.put(m.id, i);
        }
        from = end;
      }
    }
    for (Member m : members) if (at.containsKey(m.id)) out.add(m);
    out.sort((a, b) -> Integer.compare(at.get(a.id), at.get(b.id)));
    return out;
  }

  private static Member byId(List<Member> l, long id) {
    for (Member m : l) if (m.id == id) return m;
    return null;
  }

  private Mentions() {}
}
