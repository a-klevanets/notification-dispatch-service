package com.example.notification.api;

import java.sql.Timestamp;

import com.example.notification.persistence.NotificationAttempt;

/**
 * The wire shape of one attempt: the log's own words, with nothing encoded at the boundary.
 * {@code detail} is the last thing said about the attempt - {@code START} while it runs, the
 * delivery error, or {@code SENT}.
 */
public record NotificationResponse(
    long id,
    String type,
    String recipient,
    String payload,
    NotificationAttempt.Status status,
    String detail,
    String note,
    Timestamp createdAt,
    Timestamp updatedAt) {

  static NotificationResponse from(NotificationAttempt attempt) {
    return new NotificationResponse(
        attempt.id(),
        attempt.type(),
        attempt.target(),
        attempt.payload(),
        attempt.status(),
        attempt.message(),
        attempt.note(),
        attempt.createdAt(),
        attempt.updatedAt());
  }
}
