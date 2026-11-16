package com.victorious.bots;

/**
 * Provider credentials. A secret is cryptographically bound to one profile and one "binding" string
 * (see {@link Profile#binding}): the endpoint origin, plus the pinned certificate fingerprint for PC
 * profiles. Reading it for any other binding must fail, so editing an endpoint or changing a pin can
 * never send the old key to a new host or certificate.
 */
public interface Secrets {
  /** @return the key, or "" when none is stored. @throws SecurityException if bound to another binding. */
  String read(long profileId, String binding) throws Exception;

  void save(long profileId, String binding, String key) throws Exception;

  void remove(long profileId);

  boolean has(long profileId);

  /**
   * True only when a key is stored AND it can be read for {@code binding}. False for keys stored
   * under an older or different binding (for example before the pin was part of the binding), which
   * the owner must re-enter. Never throws.
   */
  default boolean usable(long profileId, String binding) {
    try {
      return !read(profileId, binding).isEmpty();
    } catch (Exception e) {
      return false;
    }
  }
}
