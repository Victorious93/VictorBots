package com.victorious.bots;

/**
 * Inference policy. This is the single place that decides whether text may be sent to a destination.
 * It is evaluated from owner-controlled settings only: model output, bot messages, imported data and
 * skill text can never change it.
 */
public final class Policy {
  /** Ordered from least to most restrictive. */
  public enum Mode {
    ALLOW_CLOUD("allow_cloud", "Cloud allowed", "Any configured provider may be used.", true),
    PC_PREFERRED(
        "pc_preferred",
        "PC first, cloud only if I confirm",
        "Your PC is used. A cloud provider is used only after you confirm that single request;"
            + " unattended work never falls back to cloud.",
        true),
    PC_ONLY(
        "pc_only",
        "PC only",
        "Only your PC-hosted provider is used. Cloud and DroidCommand are refused.",
        true),
    LOCAL_ONLY(
        "local_only",
        "This device only",
        "Not available: VictorBots has no on-device model, and it cannot verify where DroidCommand"
            + " sends text. Every inference request is refused under this setting.",
        false);

    public final String id, label, explanation;
    public final boolean usable;

    Mode(String i, String l, String e, boolean u) {
      id = i;
      label = l;
      explanation = e;
      usable = u;
    }

    public static Mode parse(String s, Mode fallback) {
      for (Mode m : values()) if (m.id.equals(s)) return m;
      return fallback;
    }
  }

  public enum Kind {
    CLOUD,
    PC,
    DROIDCOMMAND
  }

  /** Every reason VictorBots sends text to a model. Listed so a test can prove each is gated. */
  public enum Purpose {
    CHAT(true),
    ROUTING(false),
    MEMORY(false),
    SUMMARY(false),
    SKILL(true),
    TITLE(false),
    ROUTINE(false),
    TEAM(false),
    DIAGNOSTIC(true),
    FALLBACK(true);

    /** True when the owner is looking at the screen and tapped for this specific request. */
    public final boolean ownerInitiated;

    Purpose(boolean o) {
      ownerInitiated = o;
    }
  }

  public static final class Decision {
    public final boolean allowed;
    public final String reason;

    Decision(boolean a, String r) {
      allowed = a;
      reason = r;
    }
  }

  public static Mode stricter(Mode a, Mode b) {
    return a.ordinal() >= b.ordinal() ? a : b;
  }

  /** A bot may only tighten the owner's global policy, never loosen it. */
  public static Mode effective(Mode global, String botOverride) {
    if (botOverride == null || botOverride.isEmpty()) return global;
    return stricter(global, Mode.parse(botOverride, global));
  }

  /**
   * @param ownerConfirmedThisRequest true only when a dialog naming the destination was accepted for
   *     this one request. Background purposes can never set it.
   */
  public static Decision decide(
      Mode mode, Kind kind, Purpose purpose, boolean ownerConfirmedThisRequest) {
    if (mode == Mode.LOCAL_ONLY)
      return new Decision(
          false,
          "Policy is “This device only”, but VictorBots cannot run a model on this device. Nothing"
              + " was sent.");
    boolean confirmedCloud = ownerConfirmedThisRequest && purpose.ownerInitiated;
    switch (mode) {
      case ALLOW_CLOUD:
        return new Decision(true, "");
      case PC_PREFERRED:
        if (kind == Kind.PC) return new Decision(true, "");
        if (confirmedCloud) return new Decision(true, "");
        return new Decision(
            false,
            purpose.ownerInitiated
                ? "Policy keeps this on your PC. Confirm this one request to use a "
                    + (kind == Kind.CLOUD ? "cloud provider" : "DroidCommand")
                    + " instead. Nothing was sent."
                : "Policy keeps unattended work on your PC; it never falls back to cloud. Nothing"
                    + " was sent.");
      case PC_ONLY:
        if (kind == Kind.PC) return new Decision(true, "");
        return new Decision(
            false,
            "Policy is “PC only”: "
                + (kind == Kind.CLOUD ? "a cloud provider" : "DroidCommand")
                + " is not allowed. Nothing was sent.");
      default:
        return new Decision(false, "Unknown policy; refused.");
    }
  }

  private Policy() {}
}
