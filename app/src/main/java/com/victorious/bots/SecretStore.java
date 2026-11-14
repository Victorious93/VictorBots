package com.victorious.bots;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Provider keys, encrypted with a non-exportable Android Keystore AES-GCM key. Each ciphertext is
 * authenticated against "profileId|origin" (GCM associated data) and the origin is stored beside it:
 * decrypting for any other profile or endpoint fails, so a key cannot be replayed to a new endpoint
 * even if the profile row is edited.
 */
public final class SecretStore implements Secrets {
  private static final String ALIAS = "victorbots-provider";
  private final SharedPreferences prefs, legacy;

  public SecretStore(Context c) {
    Context app = c.getApplicationContext();
    prefs = app.getSharedPreferences("vault2", 0);
    legacy = app.getSharedPreferences("vault", 0);
  }

  private SecretKey key() throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    if (!ks.containsAlias(ALIAS)) {
      KeyGenerator kg = KeyGenerator.getInstance("AES", "AndroidKeyStore");
      kg.init(
          new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      kg.generateKey();
    }
    return (SecretKey) ks.getKey(ALIAS, null);
  }

  private static byte[] aad(long id, String origin) {
    return (id + "|" + origin).getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public void save(long profileId, String origin, String value) throws Exception {
    if (value.isEmpty()) {
      remove(profileId);
      return;
    }
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    cipher.updateAAD(aad(profileId, origin));
    String stored =
        origin
            + "\n"
            + Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
            + ":"
            + Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    if (!prefs.edit().putString("p" + profileId, stored).commit()) throw new IllegalStateException("Could not save key");
  }

  @Override
  public String read(long profileId, String origin) throws Exception {
    String value = prefs.getString("p" + profileId, "");
    if (value.isEmpty()) return "";
    int nl = value.indexOf('\n');
    String bound = value.substring(0, nl);
    if (!bound.equals(origin)) throw new SecurityException("Stored key is bound to " + bound);
    String[] parts = value.substring(nl + 1).split(":", 2);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
    cipher.updateAAD(aad(profileId, origin));
    return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
  }

  @Override
  public void remove(long profileId) {
    prefs.edit().remove("p" + profileId).commit();
  }

  @Override
  public boolean has(long profileId) {
    return prefs.contains("p" + profileId);
  }

  /** The single shared key from VictorBots 0.1.0 (no endpoint binding), kept only for migration. */
  public String readLegacy() throws Exception {
    String value = legacy.getString("key", "");
    if (value.isEmpty()) return "";
    String[] parts = value.split(":", 2);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
    return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
  }

  public boolean hasLegacy() {
    return !legacy.getString("key", "").isEmpty();
  }

  public void clearLegacy() {
    legacy.edit().clear().commit();
  }

  /** Test hook: seeds a 0.1.0-format key exactly as the old app wrote it. */
  public void writeLegacyForTest(String value) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    String stored = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    legacy.edit().putString("key", stored).commit();
  }
}
