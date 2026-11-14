package com.victorious.bots;

/**
 * Provider credentials. A secret is cryptographically bound to one profile and one endpoint origin:
 * reading it for any other origin must fail, so editing an endpoint can never leak the old key to the
 * new host.
 */
public interface Secrets {
  /** @return the key, or "" when none is stored. @throws SecurityException if bound to another origin. */
  String read(long profileId, String origin) throws Exception;

  void save(long profileId, String origin, String key) throws Exception;

  void remove(long profileId);

  boolean has(long profileId);
}
