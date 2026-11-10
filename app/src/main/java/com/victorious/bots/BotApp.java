package com.victorious.bots;

import android.app.Application;
import android.os.*;
import java.util.List;
import java.util.concurrent.*;

public final class BotApp extends Application {
  public Store store;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  public Runnable changed;

  @Override
  public void onCreate() {
    super.onCreate();
    store = new Store(this);
    store
        .getWritableDatabase()
        .execSQL(
            "UPDATE messages SET status='error',role='system',body='Interrupted when the app"
                + " process stopped. No automatic retry was sent.' WHERE status='pending' AND"
                + " body!='Waiting for DroidCommand consent…'");
  }

  public void ask(long reply, String endpoint, String key, String model, List<Rules.Turn> turns) {
    worker.execute(
        () -> {
          try {
            Provider.Reply result = Provider.ask(endpoint, key, model, turns);
            store.finish(reply, result.text, true);
            getSharedPreferences("settings", 0).edit().putString("usage", result.usage).apply();
          } catch (Exception e) {
            String message =
                e instanceof java.net.SocketTimeoutException
                    ? "Provider timed out. It may still have processed the request; no automatic"
                          + " retry was sent."
                    : e instanceof java.io.IOException
                        ? e.getMessage()
                        : "Could not parse the provider reply. Check endpoint compatibility.";
            store.finish(reply, message == null ? "Provider connection failed." : message, false);
          }
          new Handler(Looper.getMainLooper())
              .post(
                  () -> {
                    if (changed != null) changed.run();
                  });
        });
  }
}
