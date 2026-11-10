package com.victorious.bots;

import android.content.*;
import android.content.pm.*;
import java.security.MessageDigest;
import java.util.List;
import org.json.*;

public final class DcaBridge {
  public static final String PACKAGE = "ai.droidcommand.app",
      ACTION = "ai.droidcommand.action.COMPANION_ASSIST",
      REQUEST = "ai.droidcommand.extra.REQUEST_JSON",
      RESULT = "ai.droidcommand.extra.RESULT_JSON";
  public static final String DEBUG_PIN =
      "880051A6D95D38DD8D81E1931E1705480387D593C30943539968E6EF11A0C84D";

  public static String fingerprint(Context c) throws Exception {
    PackageInfo info =
        c.getPackageManager().getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
    if (info.signingInfo == null || info.signingInfo.getApkContentsSigners().length != 1)
      throw new SecurityException("DroidCommand signer is unavailable or ambiguous.");
    byte[] digest =
        MessageDigest.getInstance("SHA-256")
            .digest(info.signingInfo.getApkContentsSigners()[0].toByteArray());
    StringBuilder b = new StringBuilder();
    for (byte v : digest) b.append(String.format(java.util.Locale.ROOT, "%02X", v));
    return b.toString();
  }

  public static void verify(Context c, String pin) throws Exception {
    if (!fingerprint(c).equals(Rules.pin(pin)))
      throw new SecurityException(
          "DroidCommand certificate does not match the configured pin. Verify its signing"
              + " certificate before changing trust.");
  }

  public static Intent request(List<Rules.Turn> turns) throws JSONException {
    StringBuilder transcript = new StringBuilder();
    for (Rules.Turn t : turns) {
      transcript
          .append(t.role.toUpperCase(java.util.Locale.ROOT))
          .append(":\n")
          .append(t.content)
          .append("\n\n");
    }
    if (transcript.length() > 60000)
      throw new IllegalArgumentException("DroidCommand prompt exceeds 60,000 characters.");
    String json =
        new JSONObject()
            .put("v", 1)
            .put("task", "assist")
            .put("capability", "companion.assist")
            .put("prompt", transcript.toString())
            .put("destructive", false)
            .toString();
    return new Intent(ACTION)
        .setComponent(
            new ComponentName(
                PACKAGE, "ai.droidcommand.app.companionassist.CompanionAssistActivity"))
        .putExtra(REQUEST, json);
  }

  public static String result(Intent data) throws Exception {
    String raw = data == null ? null : data.getStringExtra(RESULT);
    if (raw == null || raw.length() > 600000)
      throw new IllegalArgumentException("Missing or oversized DroidCommand reply.");
    JSONObject o = new JSONObject(raw);
    if (o.optInt("v", -1) != 1 || !(o.opt("ok") instanceof Boolean))
      throw new IllegalArgumentException("Unsupported DroidCommand reply.");
    if (!o.getBoolean("ok"))
      throw new IllegalArgumentException(
          "DroidCommand could not complete the request. Check its provider configuration.");
    Object t = o.opt("text");
    if (!(t instanceof String) || ((String) t).isEmpty())
      throw new IllegalArgumentException("DroidCommand returned no text.");
    return (String) t;
  }

  private DcaBridge() {}
}
