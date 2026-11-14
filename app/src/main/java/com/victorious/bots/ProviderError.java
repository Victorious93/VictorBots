package com.victorious.bots;

import java.io.IOException;

/** A failed provider call, categorised so the UI can say what actually went wrong without secrets. */
public final class ProviderError extends IOException {
  public enum Category {
    TIMEOUT("Timed out"),
    UNREACHABLE("Host unreachable"),
    TLS("TLS / certificate problem"),
    AUTH("Authentication rejected"),
    MODEL_UNAVAILABLE("Model unavailable"),
    UNSUPPORTED("Unsupported by this backend"),
    RATE_LIMIT("Rate limited"),
    SERVER("Server error"),
    MALFORMED("Malformed response"),
    REDIRECT("Redirect refused"),
    TOO_LARGE("Response too large"),
    CANCELLED("Cancelled"),
    NOT_FOUND("Endpoint not found");

    public final String label;

    Category(String l) {
      label = l;
    }
  }

  public final Category category;
  public final int httpStatus;
  /** The request may have reached the provider and been processed (so it must not be auto-retried). */
  public final boolean mayHaveBeenProcessed;

  public ProviderError(Category c, int status, String message, boolean processed) {
    super(message);
    category = c;
    httpStatus = status;
    mayHaveBeenProcessed = processed;
  }
}
