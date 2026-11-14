package com.victorious.bots;

import android.content.SharedPreferences;

public final class PrefsKV implements KV {
  private final SharedPreferences p;

  public PrefsKV(SharedPreferences p) {
    this.p = p;
  }

  @Override
  public String get(String key, String fallback) {
    return p.getString("kv." + key, fallback);
  }

  @Override
  public void put(String key, String value) {
    p.edit().putString("kv." + key, value).commit();
  }
}
