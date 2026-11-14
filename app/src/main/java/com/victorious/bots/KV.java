package com.victorious.bots;

/** Tiny key/value settings surface (SharedPreferences on Android, a map in tests). */
public interface KV {
  String get(String key, String fallback);

  void put(String key, String value);

  default boolean bool(String key, boolean fallback) {
    return "1".equals(get(key, fallback ? "1" : "0"));
  }

  default long num(String key, long fallback) {
    try {
      return Long.parseLong(get(key, "" + fallback));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}
