package com.victorious.bots;

import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;
import javax.net.ssl.*;

/**
 * Per-connection certificate pinning for a PC that uses a private or self-signed certificate. The
 * pin is the SHA-256 of the exact server certificate; any other certificate is rejected. This is not
 * trust-all, and it is never installed application-wide. Without a pin the platform trust store and
 * hostname verification apply unchanged.
 */
public final class Tls {
  public static String sha256Hex(byte[] der) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(der);
      StringBuilder b = new StringBuilder();
      for (byte v : d) b.append(String.format(Locale.ROOT, "%02X", v));
      return b.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static SSLSocketFactory pinnedFactory(String pinHex) {
    final String pin = Rules.pin(pinHex);
    X509TrustManager tm =
        new X509TrustManager() {
          @Override
          public void checkClientTrusted(X509Certificate[] chain, String authType)
              throws CertificateException {
            throw new CertificateException("Client certificates are not accepted.");
          }

          @Override
          public void checkServerTrusted(X509Certificate[] chain, String authType)
              throws CertificateException {
            if (chain == null || chain.length == 0)
              throw new CertificateException("No server certificate.");
            chain[0].checkValidity();
            if (!sha256Hex(chain[0].getEncoded()).equals(pin))
              throw new CertificateException(
                  "Server certificate does not match the pinned fingerprint.");
          }

          @Override
          public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
          }
        };
    try {
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(null, new TrustManager[] {tm}, null);
      return ctx.getSocketFactory();
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Only used together with {@link #pinnedFactory}: the pinned certificate identifies the server. */
  public static final HostnameVerifier PIN_ONLY = (host, session) -> true;

  private Tls() {}
}
