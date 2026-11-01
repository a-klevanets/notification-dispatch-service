package com.example.notification.api;

import java.sql.Timestamp;

import com.example.notification.persistence.NotificationAttempt;

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
