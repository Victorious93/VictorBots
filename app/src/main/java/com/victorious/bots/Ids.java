package com.victorious.bots;

import java.util.UUID;

public final class Ids {
  /** Stable 32-hex id used for import/export de-duplication and job correlation. */
  public static String uid() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  public static String uuid() {
    return UUID.randomUUID().toString();
  }

  private Ids() {}
}
