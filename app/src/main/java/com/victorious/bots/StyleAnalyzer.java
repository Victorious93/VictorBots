package com.victorious.bots;

import java.util.*;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Victorious Framework: statistical analysis of the OWNER'S OWN outgoing messages. Deterministic, no model, no network.
 * The caller must pass only outgoing text the owner wrote (VictorSMS filters on direction and author); incoming text
 * never belongs here, and examples that contain links, emails or number sequences are never kept.
 */
public final class StyleAnalyzer {
  public static final class Msg {
    public final String text;
    public final long ts;

    public Msg(String text, long ts) {
      this.text = text == null ? "" : text;
      this.ts = ts;
    }
  }

  public static final class Result {
    public JSONObject stats = new JSONObject();
    public List<String> examples = new ArrayList<>();
    public String confidence = "low";
    public int count;
  }

  static final Set<String> SLANG = new HashSet<>(Arrays.asList("lol", "lmao", "lmfao", "omw", "brb", "idk", "imo", "tbh", "thx", "ty", "u", "ur", "k", "kk", "nah", "yep", "yeah", "gonna",
      "wanna", "gotta", "lmk", "btw", "np", "smh", "rn", "ngl", "fr", "bc", "cuz", "tho", "haha", "hahaha", "hehe", "rofl", "ya", "yo", "sup", "dude", "bro", "pls", "plz", "omg", "wtf", "ily", "gn", "ttyl"));
  static final Set<String> LAUGH = new HashSet<>(Arrays.asList("lol", "lmao", "lmfao", "haha", "hahaha", "hehe", "rofl", "😂", "🤣"));
  static final Set<String> GREET = new HashSet<>(Arrays.asList("hey", "hi", "hello", "yo", "sup", "morning", "heya", "howdy", "hiya"));
  static final Set<String> SIGNOFF = new HashSet<>(Arrays.asList("thanks", "thx", "ty", "cheers", "later", "ttyl", "bye", "xoxo", "goodnight", "gn", "soon", "tomorrow", "x"));
  static final Set<String> STOP = new HashSet<>(Arrays.asList("the", "a", "an", "to", "and", "of", "i", "you", "it", "is", "in", "that", "for", "on", "be", "my", "me", "we", "so", "this", "with", "at", "are", "was", "have", "do", "not", "but", "if", "or", "just", "im", "its", "your"));
  static final Set<String> NO_APOS = new HashSet<>(Arrays.asList("dont", "cant", "wont", "im", "ive", "youre", "thats", "didnt", "isnt", "wasnt", "couldnt", "shouldnt", "wouldnt", "doesnt", "ill", "id", "theyre", "whats"));
  static final Set<String> WITH_APOS = new HashSet<>(Arrays.asList("don't", "can't", "won't", "i'm", "i've", "you're", "that's", "didn't", "isn't", "wasn't", "couldn't", "shouldn't", "wouldn't", "doesn't", "i'll", "i'd", "they're", "what's"));
  private static final Pattern SPLIT = Pattern.compile("[^\\p{L}\\p{N}'’]+");
  private static final Pattern URL = Pattern.compile("(?i)(https?://|www\\.)\\S+");
  private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
  private static final Pattern DIGITS = Pattern.compile("\\d[\\d\\s().-]{5,}\\d");
  private static final Pattern DOUBLE_PUNCT = Pattern.compile("[!?]{2,}");
  private static final int BURST_GAP_MS = 90_000;

  public static Result analyze(List<Msg> in) {
    Result r = new Result();
    List<Msg> msgs = new ArrayList<>();
    for (Msg m : in) if (!m.text.trim().isEmpty()) msgs.add(m);
    int n = msgs.size();
    r.count = n;
    r.confidence = n < 30 ? "low" : n < 200 ? "medium" : "high";
    JSONObject s = r.stats;
    try {
      s.put("count", n);
      if (n == 0) return r;
      int[] chars = new int[n];
      long words = 0, shortMsgs = 0, multiline = 0, startsLower = 0, startsLetter = 0, endPeriod = 0, endQ = 0, endExcl = 0, ellipsis = 0, dbl = 0, withEmoji = 0, emojiTotal = 0, allCaps = 0, wordTotal = 0;
      long iLower = 0, iUpper = 0, apoOmit = 0, apoWith = 0, laugh = 0, sarcasm = 0, slangTotal = 0;
      Map<String, Integer> emojis = new HashMap<>(), slang = new HashMap<>(), greet = new HashMap<>(), sign = new HashMap<>(), uni = new HashMap<>(), bi = new HashMap<>(), tri = new HashMap<>();
      for (int i = 0; i < n; i++) {
        String t = msgs.get(i).text.trim();
        chars[i] = t.codePointCount(0, t.length());
        if (chars[i] <= 25) shortMsgs++;
        if (t.indexOf('\n') >= 0) multiline++;
        int first = firstLetter(t);
        if (first >= 0) {
          startsLetter++;
          if (Character.isLowerCase(first)) startsLower++;
        }
        char last = t.charAt(t.length() - 1);
        if (last == '.') endPeriod++;
        if (last == '?') endQ++;
        if (last == '!') endExcl++;
        if (t.contains("...") || t.contains("…")) ellipsis++;
        if (DOUBLE_PUNCT.matcher(t).find()) dbl++;
        int e = 0;
        for (int cp, k = 0; k < t.length(); k += Character.charCount(cp)) {
          cp = t.codePointAt(k);
          if (isEmoji(cp)) {
            e++;
            emojis.merge(new String(Character.toChars(cp)), 1, Integer::sum);
            if (LAUGH.contains(new String(Character.toChars(cp)))) laugh++;
          }
        }
        if (e > 0) withEmoji++;
        emojiTotal += e;
        if (t.contains("/s") || t.toLowerCase(Locale.ROOT).contains("yeah right")) sarcasm++;
        String[] toks = SPLIT.split(t.toLowerCase(Locale.ROOT));
        List<String> w = new ArrayList<>();
        for (String tok : toks) if (!tok.isEmpty()) w.add(tok);
        words += w.size();
        for (String raw : SPLIT.split(t)) {
          if (raw.length() >= 2 && raw.equals(raw.toUpperCase(Locale.ROOT)) && raw.chars().anyMatch(Character::isLetter)) allCaps++;
          if (!raw.isEmpty()) wordTotal++;
        }
        for (String raw : SPLIT.split(t)) {
          if (raw.equals("i")) iLower++;
          if (raw.equals("I")) iUpper++;
        }
        for (int k = 0; k < w.size(); k++) {
          String tok = w.get(k);
          if (SLANG.contains(tok)) { slangTotal++; slang.merge(tok, 1, Integer::sum); }
          if (LAUGH.contains(tok)) laugh++;
          if (NO_APOS.contains(tok)) apoOmit++;
          if (WITH_APOS.contains(tok.replace('’', '\''))) apoWith++;
          if (!STOP.contains(tok) && tok.length() > 2) uni.merge(tok, 1, Integer::sum);
          if (k + 1 < w.size() && !(STOP.contains(tok) && STOP.contains(w.get(k + 1)))) bi.merge(tok + " " + w.get(k + 1), 1, Integer::sum);
          if (k + 2 < w.size() && !STOP.contains(tok)) tri.merge(tok + " " + w.get(k + 1) + " " + w.get(k + 2), 1, Integer::sum);
        }
        if (!w.isEmpty() && GREET.contains(w.get(0))) greet.merge(w.get(0), 1, Integer::sum);
        if (!w.isEmpty() && SIGNOFF.contains(w.get(w.size() - 1))) sign.merge(w.get(w.size() - 1), 1, Integer::sum);
      }
      int[] sorted = chars.clone();
      Arrays.sort(sorted);
      double med = n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
      s.put("avgChars", round(sum(chars) / (double) n));
      s.put("medianChars", med);
      s.put("p90Chars", sorted[Math.min(n - 1, (int) Math.floor(n * 0.9))]);
      s.put("avgWords", round(words / (double) n));
      s.put("shortRatio", ratio(shortMsgs, n));
      s.put("multilineRatio", ratio(multiline, n));
      double lowerStart = startsLetter == 0 ? 0 : startsLower / (double) startsLetter;
      s.put("startsLowercaseRatio", round(lowerStart));
      s.put("endsPeriodRatio", ratio(endPeriod, n));
      s.put("endsQuestionRatio", ratio(endQ, n));
      s.put("endsExclamationRatio", ratio(endExcl, n));
      s.put("ellipsisRatio", ratio(ellipsis, n));
      s.put("doublePunctuationRatio", ratio(dbl, n));
      s.put("allCapsWordRatio", wordTotal == 0 ? 0 : round(allCaps / (double) wordTotal));
      s.put("lowercaseIRatio", iLower + iUpper == 0 ? JSONObject.NULL : (Object) round(iLower / (double) (iLower + iUpper)));
      s.put("emojiPerMessage", round(emojiTotal / (double) n));
      s.put("emojiMessageRatio", ratio(withEmoji, n));
      s.put("topEmoji", top(emojis, 5, 1));
      double slangRate = slangTotal / (double) n;
      s.put("slangPerMessage", round(slangRate));
      s.put("topSlang", top(slang, 8, 2));
      s.put("apostropheOmitRatio", apoOmit + apoWith == 0 ? JSONObject.NULL : (Object) round(apoOmit / (double) (apoOmit + apoWith)));
      s.put("humorRate", round(laugh / (double) n));
      s.put("humor", Math.min(100, (int) Math.round(laugh / (double) n * 200)));
      s.put("sarcasmMarkers", sarcasm);
      s.put("greetings", top(greet, 4, 2));
      s.put("signOffs", top(sign, 4, 2));
      s.put("topWords", top(uni, 15, 3));
      Map<String, Integer> phrases = new HashMap<>();
      int minPhrase = Math.max(3, (int) Math.round(n * 0.02));
      for (Map.Entry<String, Integer> en : tri.entrySet()) if (en.getValue() >= minPhrase) phrases.put(en.getKey(), en.getValue());
      for (Map.Entry<String, Integer> en : bi.entrySet()) if (en.getValue() >= minPhrase) phrases.put(en.getKey(), en.getValue());
      s.put("phrases", top(phrases, 10, minPhrase));
      double formality = 50 + 25 * (1 - lowerStart) + 15 * (endPeriod / (double) n) - 25 * Math.min(1, slangRate * 2) - 10 * Math.min(1, emojiTotal / (double) n)
          - 10 * (apoOmit + apoWith == 0 ? 0 : apoOmit / (double) (apoOmit + apoWith));
      s.put("formality", (int) Math.max(0, Math.min(100, Math.round(formality))));
      // Bursts need timestamps: consecutive messages sent within 90 s read as one thought in several texts.
      List<Msg> timed = new ArrayList<>();
      for (Msg m : msgs) if (m.ts > 0) timed.add(m);
      if (timed.size() >= 2) {
        Collections.sort(timed, new Comparator<Msg>() { public int compare(Msg a, Msg b) { return Long.compare(a.ts, b.ts); } });
        int inBurst = 0, bursts = 0, run = 1;
        for (int i = 1; i <= timed.size(); i++) {
          if (i < timed.size() && timed.get(i).ts - timed.get(i - 1).ts <= BURST_GAP_MS) run++;
          else { if (run >= 2) { inBurst += run; bursts++; } run = 1; }
        }
        s.put("burstRatio", ratio(inBurst, timed.size()));
        s.put("avgBurstSize", bursts == 0 ? 1 : round(inBurst / (double) bursts));
      }
      r.examples = pickExamples(msgs, med, phrases.keySet(), slang.keySet());
    } catch (JSONException ex) {
      throw new IllegalStateException(ex);
    }
    return r;
  }

  /** Short, typical messages that carry the owner's habits. Anything containing a link, email or number run is excluded. */
  static List<String> pickExamples(List<Msg> msgs, double median, Set<String> phrases, Set<String> slang) {
    final Map<String, Double> score = new HashMap<>();
    for (Msg m : msgs) {
      String t = m.text.trim();
      if (t.length() > 200 || t.length() < 2 || URL.matcher(t).find() || EMAIL.matcher(t).find() || DIGITS.matcher(t).find()) continue;
      String low = t.toLowerCase(Locale.ROOT);
      double sc = -Math.abs(t.length() - median) / Math.max(1, median);
      for (String p : phrases) if (low.contains(p)) sc += 1;
      for (String sl : slang) if (Arrays.asList(SPLIT.split(low)).contains(sl)) sc += 0.5;
      if (t.codePoints().anyMatch(StyleAnalyzer::isEmoji)) sc += 0.3;
      Double prev = score.get(t);
      if (prev == null || sc > prev) score.put(t, sc);
    }
    List<String> keys = new ArrayList<>(score.keySet());
    Collections.sort(keys, new Comparator<String>() {
      public int compare(String a, String b) {
        int c = Double.compare(score.get(b), score.get(a));
        return c != 0 ? c : a.compareTo(b);
      }
    });
    return new ArrayList<>(keys.subList(0, Math.min(8, keys.size())));
  }

  static boolean isEmoji(int cp) {
    return (cp >= 0x1F300 && cp <= 0x1FAFF) || (cp >= 0x2600 && cp <= 0x27BF) || (cp >= 0x1F000 && cp <= 0x1F2FF) || cp == 0x2764 || cp == 0x2B50;
  }

  private static int firstLetter(String t) {
    for (int cp, i = 0; i < t.length(); i += Character.charCount(cp)) {
      cp = t.codePointAt(i);
      if (Character.isLetter(cp)) return cp;
      if (Character.isDigit(cp)) return -1;
    }
    return -1;
  }

  private static JSONArray top(Map<String, Integer> m, int limit, int min) {
    List<Map.Entry<String, Integer>> es = new ArrayList<>();
    for (Map.Entry<String, Integer> e : m.entrySet()) if (e.getValue() >= min) es.add(e);
    Collections.sort(es, new Comparator<Map.Entry<String, Integer>>() {
      public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
        int c = b.getValue().compareTo(a.getValue());
        return c != 0 ? c : a.getKey().compareTo(b.getKey());
      }
    });
    JSONArray out = new JSONArray();
    for (int i = 0; i < Math.min(limit, es.size()); i++) out.put(es.get(i).getKey());
    return out;
  }

  private static long sum(int[] a) {
    long t = 0;
    for (int x : a) t += x;
    return t;
  }

  private static double ratio(long a, long b) {
    return b == 0 ? 0 : round(a / (double) b);
  }

  private static double round(double d) {
    return Math.round(d * 1000.0) / 1000.0;
  }

  private StyleAnalyzer() {}
}
