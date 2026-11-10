package com.victorious.bots.test;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import com.victorious.bots.*;
import java.util.*;
import org.json.*;

/** Runs against an isolated emulator installation; never run on a user's populated app. */
public final class SmokeInstrumentation extends Instrumentation {
  private int passed;

  private void check(boolean value, String label) {
    if (!value) throw new AssertionError(label);
    passed++;
  }

  private interface Throwing {
    void run() throws Exception;
  }

  private void refuse(Throwing r, String label) throws Exception {
    try {
      r.run();
      throw new AssertionError(label);
    } catch (IllegalArgumentException expected) {
      passed++;
    }
  }

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  @Override
  public void onStart() {
    Bundle report = new Bundle();
    long bot = 0;
    Store db = null;
    try {
      Context c = getTargetContext();
      db = new Store(c);
      check(db.rows("SELECT id FROM bots").size() >= 4, "starter bots");
      bot = db.saveBot(0, "Instrumentation bot", "Test description", "Test instructions", "T");
      long chat = db.newChat(bot);
      db.message(chat, "user", "A persisted message", "done");
      long pending = db.message(chat, "system", "Waiting", "pending");
      check(db.pending(chat), "pending state");
      db.finish(pending, "Answer", true);
      check(!db.pending(chat), "completion");
      check(db.history(chat).size() == 2, "history preserved");
      db.finish(pending, "late duplicate", false);
      check(db.history(chat).get(1).content.equals("Answer"), "duplicate completion ignored");
      long cancelled = db.message(chat, "system", "waiting", "pending");
      db.finish(cancelled, "cancelled", false);
      db.finish(cancelled, "late reply", true);
      check(db.history(chat).size() == 2, "cancelled reply ignored");
      db.close();
      db = new Store(c);
      check(db.bot(bot).optString("name").equals("Instrumentation bot"), "bot survives reopen");
      check(db.history(chat).size() == 2, "history survives reopen");
      SecretStore secrets = new SecretStore(c);
      secrets.save("instrumentation-test-key");
      check(secrets.read().equals("instrumentation-test-key"), "keystore roundtrip");
      check(
          !c.getSharedPreferences("vault", 0)
              .getString("key", "")
              .contains("instrumentation-test-key"),
          "key not stored as plaintext");
      check(
          !db.exportData().toString().contains("instrumentation-test-key"), "export excludes key");
      secrets.save("");
      check(secrets.read().isEmpty(), "key deletion");
      Intent request =
          DcaBridge.request(
              Arrays.asList(new Rules.Turn("system", "Role"), new Rules.Turn("user", "Hello")));
      check(
          request.getComponent().getPackageName().equals("ai.droidcommand.app"),
          "explicit DCA target");
      JSONObject payload = new JSONObject(request.getStringExtra(DcaBridge.REQUEST));
      check(
          payload.getInt("v") == 1 && payload.getString("task").equals("assist"), "wire contract");
      check(!payload.getBoolean("destructive"), "text-only request");
      check(payload.getString("prompt").contains("Hello"), "prompt serialized");
      check(
          DcaBridge.result(
                  new Intent()
                      .putExtra(DcaBridge.RESULT, "{\"v\":1,\"ok\":true,\"text\":\"Hello\"}"))
              .equals("Hello"),
          "reply parser");
      refuse(() -> DcaBridge.result(null), "missing reply refused");
      refuse(
          () ->
              DcaBridge.result(
                  new Intent().putExtra(DcaBridge.RESULT, "{\"v\":2,\"ok\":true,\"text\":\"x\"}")),
          "wrong version refused");
      refuse(
          () -> DcaBridge.result(new Intent().putExtra(DcaBridge.RESULT, "{\"v\":1,\"ok\":false}")),
          "error reply refused");
      try {
        DcaBridge.verify(c, DcaBridge.DEBUG_PIN);
        throw new AssertionError("No DCA should be installed in this isolated test emulator");
      } catch (android.content.pm.PackageManager.NameNotFoundException expected) {
        passed++;
      }
      db.getWritableDatabase().delete("bots", "id=?", new String[] {"" + bot});
      bot = 0;
      check(
          db.rows("SELECT id FROM chats WHERE id=?", "" + chat).isEmpty(),
          "chat deletion cascades");
      check(
          db.rows("SELECT id FROM messages WHERE chat=?", "" + chat).isEmpty(),
          "message deletion cascades");
      report.putString("stream", passed + " Android integration checks passed\n");
      finish(Activity.RESULT_OK, report);
    } catch (Throwable e) {
      report.putString(
          "stream",
          "FAILED after " + passed + " checks: " + android.util.Log.getStackTraceString(e));
      finish(Activity.RESULT_CANCELED, report);
    } finally {
      if (db != null) {
        if (bot != 0) db.getWritableDatabase().delete("bots", "id=?", new String[] {"" + bot});
        db.close();
      }
    }
  }
}
