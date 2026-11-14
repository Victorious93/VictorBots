package com.victorious.bots;

import android.content.SharedPreferences;
import org.json.JSONObject;

/**
 * One-time move of the 0.1.0 single shared provider configuration into the first profile. The
 * request shape is preserved (streaming off) and the stored key is re-encrypted bound to that
 * endpoint's origin. If anything fails the old key is left untouched and the owner is told.
 */
public final class LegacyMigration {
  public static void run(BotApp app, Store store, Profiles profiles, SecretStore secrets, KV kv, SharedPreferences prefs) {
    if (prefs.getBoolean("migrated_v2", false)) return;
    Profile dca = profiles.byKind(Policy.Kind.DROIDCOMMAND);
    long defaultId = dca == null ? 0 : dca.id;
    String note = "";
    String endpoint = prefs.getString("endpoint", "");
    if (!endpoint.isEmpty()) {
      try {
        String base = Hosts.base(endpoint);
        String name = "Direct provider (migrated)";
        long id = profiles.save(0, name, Policy.Kind.CLOUD, base, prefs.getString("model", ""), "", new JSONObject().put("streaming", false));
        if (secrets.hasLegacy()) {
          try {
            String key = secrets.readLegacy();
            if (!key.isEmpty()) secrets.save(id, Hosts.origin(base), key);
            secrets.clearLegacy();
          } catch (Exception e) {
            note = "Your old API key could not be moved. Enter it again in Providers.";
          }
        }
        if (prefs.getBoolean("direct", false)) defaultId = id;
      } catch (Exception e) {
        note = "Your old provider settings could not be migrated: " + Redact.snippet(String.valueOf(e.getMessage()), 120);
      }
    }
    if (defaultId != 0) kv.put(Profiles.DEFAULT_KEY, "" + defaultId);
    kv.put("migration_note", note);
    prefs.edit().putBoolean("migrated_v2", true).commit();
  }

  private LegacyMigration() {}
}
