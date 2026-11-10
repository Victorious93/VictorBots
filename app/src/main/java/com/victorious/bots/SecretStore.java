package com.victorious.bots;

import android.content.Context;
import android.security.keystore.*;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

public final class SecretStore {
  private final Context context;

  public SecretStore(Context c) {
    context = c.getApplicationContext();
  }

  private SecretKey key() throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    if (!ks.containsAlias("victorbots-provider")) {
      KeyGenerator kg = KeyGenerator.getInstance("AES", "AndroidKeyStore");
      kg.init(
          new KeyGenParameterSpec.Builder(
                  "victorbots-provider",
                  KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      kg.generateKey();
    }
    return (SecretKey) ks.getKey("victorbots-provider", null);
  }

  public void save(String value) throws Exception {
    if (value.isEmpty()) {
      context.getSharedPreferences("vault", 0).edit().clear().commit();
      return;
    }
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    String stored =
        Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
            + ":"
            + Base64.encodeToString(
                cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                Base64.NO_WRAP);
    if (!context.getSharedPreferences("vault", 0).edit().putString("key", stored).commit())
      throw new IllegalStateException("Could not save key");
  }

  public String read() throws Exception {
    String value = context.getSharedPreferences("vault", 0).getString("key", "");
    if (value.isEmpty()) return "";
    String[] parts = value.split(":", 2);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        key(),
        new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
    return new String(
        cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)),
        java.nio.charset.StandardCharsets.UTF_8);
  }
}
