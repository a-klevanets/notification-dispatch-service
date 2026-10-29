package com.example.notification.channel;

import java.util.Arrays;
import java.util.Optional;

/**
 * The channels this service can deliver. A wire {@code type} is parsed into one of these; a value with
 * no constant is not a channel, so it is recorded as an UNKNOWN attempt and its text stays in the log.
 */
public enum NotificationType {
  EMAIL, SMS, PUSH;

  /** The constant for an already-normalized type, or empty when no channel answers to it. */
  public static Optional<NotificationType> parse(String normalized) {
    return Arrays.stream(values())
        .filter(type -> type.name().equals(normalized))
        .findFirst();
  }
}
