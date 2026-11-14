package com.victorious.bots;

import android.app.Activity;
import android.content.Intent;

/** What screens may ask of the single Activity. */
public interface Host {
  Activity act();

  BotApp app();

  Ui ui();

  /** Re-render the current page. */
  void refresh();

  void error(Exception e);

  void startForResult(Intent intent, int request);

  /** Navigate: tab (Bots/Teams/Jobs/More) and optional sub-page with an id. */
  void go(String tab, String sub, long id);
}
