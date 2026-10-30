package com.example.notification.persistence;

import java.sql.Timestamp;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One row of {@code notification_attempt}. {@code schema.sql} leaves the identifiers unquoted, so
 * H2 folds them to upper case; Spring Data quotes this value, so it has to be the folded name.
 */
@Table("NOTIFICATION_ATTEMPT")
public record NotificationAttempt(
    @Id Long id,
    String type,
    String target,
    String payload,
    Status status,
    String message,
    @ReadOnlyProperty Timestamp createdAt,
    @ReadOnlyProperty Timestamp updatedAt) {

  /** The {@code status} column's words; {@code schema.sql} checks a row against the same five. */
  public enum Status {
    NEW, SENT, ERROR, UNKNOWN, REJECTED
  }

  /** A new attempt: its key and its timestamps are the read's to fill, so they stay null. */
  public static NotificationAttempt opened(String type, String target, String payload) {
    return new NotificationAttempt(null, type, target, payload, Status.NEW, "START", null, null);
  }
}
