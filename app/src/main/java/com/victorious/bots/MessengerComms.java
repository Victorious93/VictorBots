package com.victorious.bots;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Victorious Framework: the Android transport for COMPANION_COMMS v1. It binds droidcommand-AI's Messenger service, sends one
 * request and waits for one reply. VictorBots exposes nothing: this is an outgoing call only, and no component was added to
 * the manifest. NEVER RUN on a device in the session that wrote it; the logic around it ({@link CommsClient}) is tested.
 *
 * <p>Trust, in order: (1) the installed droidcommand-AI must carry the single signing certificate the owner pinned
 * ({@link DcaBridge#verify}), checked BEFORE binding, so nothing is sent to a look-alike; (2) the bind is restricted to that
 * package; (3) a reply is accepted only if Android stamped it as sent by that package's uid ({@code Message.sendingUid}); the
 * payload is never trusted to say who it is from. droidcommand-AI, not this app, decides who may do what.
 */
public final class MessengerComms implements CommsClient.Transport {
  private static final int MSG_REQUEST = 1, MSG_RESULT = 2;
  private static final String KEY_REQUEST = "request_json", KEY_RESULT = "result_json";
  private static final long BIND_TIMEOUT_MS = 10_000;

  private final Context context;
  private final String pin;

  public MessengerComms(Context context, String pin) {
    this.context = context.getApplicationContext();
    this.pin = pin;
  }

  @Override
  public String exchange(String requestJson, long timeoutMs) throws Exception {
    if (Looper.myLooper() == Looper.getMainLooper()) throw new CommsClient.NotSentException("Messaging calls must not run on the main thread.");
    int dcaUid;
    try {
      DcaBridge.verify(context, pin);
      dcaUid = context.getPackageManager().getApplicationInfo(DcaBridge.PACKAGE, 0).uid;
    } catch (PackageManager.NameNotFoundException e) {
      throw new CommsClient.NotSentException("droidcommand-AI is not installed on this device.");
    } catch (SecurityException e) {
      throw new CommsClient.NotSentException(e.getMessage());
    } catch (Exception e) {
      // Anything unexpected here happened before a request existed anywhere but in this process.
      throw new CommsClient.NotSentException("droidcommand-AI could not be verified (" + e.getClass().getSimpleName() + ").");
    }

    final AtomicReference<Messenger> service = new AtomicReference<>();
    final AtomicReference<String> answer = new AtomicReference<>();
    final CountDownLatch bound = new CountDownLatch(1), answered = new CountDownLatch(1);
    HandlerThread thread = new HandlerThread("vb-comms");
    thread.start();
    Messenger replies =
        new Messenger(
            new Handler(thread.getLooper()) {
              @Override
              public void handleMessage(Message m) {
                // Only the verified package's uid may answer, and only with the result message type.
                if (m.sendingUid != dcaUid || m.what != MSG_RESULT) return;
                Bundle d = m.getData();
                String s = d == null ? null : d.getString(KEY_RESULT);
                if (s != null && answer.compareAndSet(null, s)) answered.countDown();
              }
            });
    ServiceConnection connection =
        new ServiceConnection() {
          @Override
          public void onServiceConnected(ComponentName name, IBinder binder) {
            service.set(new Messenger(binder));
            bound.countDown();
          }

          @Override
          public void onServiceDisconnected(ComponentName name) {}

          @Override
          public void onNullBinding(ComponentName name) {
            bound.countDown();
          }

          @Override
          public void onBindingDied(ComponentName name) {
            bound.countDown();
          }
        };
    boolean bindRequested = false;
    try {
      // Everything up to the hand-over is "not sent": a failure here means the request never left this app.
      try {
        Intent intent = new Intent(CommsCodec.ACTION).setPackage(DcaBridge.PACKAGE);
        bindRequested = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        if (!bindRequested) throw new CommsClient.NotSentException("droidcommand-AI has no messaging service. Update droidcommand-AI.");
        if (!bound.await(Math.min(timeoutMs, BIND_TIMEOUT_MS), TimeUnit.MILLISECONDS) || service.get() == null)
          throw new CommsClient.NotSentException("droidcommand-AI did not accept the messaging connection.");
      } catch (CommsClient.NotSentException e) {
        throw e;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CommsClient.NotSentException("The messaging connection was interrupted before anything was sent.", e);
      } catch (RuntimeException e) {
        throw new CommsClient.NotSentException("droidcommand-AI refused the messaging connection (" + e.getClass().getSimpleName() + ").", e);
      }
      Message request = Message.obtain(null, MSG_REQUEST);
      Bundle data = new Bundle();
      data.putString(KEY_REQUEST, requestJson);
      request.setData(data);
      request.replyTo = replies;
      try {
        service.get().send(request);
      } catch (RemoteException e) {
        throw new CommsClient.NotSentException("The request could not be handed to droidcommand-AI.", e);
      }
      // From here on the request may have run.
      boolean got;
      try {
        got = answered.await(timeoutMs, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CommsClient.NoReplyException("Interrupted while waiting. The request was handed over and may have run; it was not repeated.");
      }
      if (!got) throw new CommsClient.NoReplyException("No reply within " + (timeoutMs / 1000) + " s. The request was handed over and may have run; it was not repeated.");
      return answer.get();
    } finally {
      if (bindRequested) {
        try {
          context.unbindService(connection);
        } catch (RuntimeException ignored) {
          // already unbound
        }
      }
      thread.quitSafely();
    }
  }
}
