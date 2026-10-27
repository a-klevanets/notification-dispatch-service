package com.example.notification.persistence;

import java.sql.Timestamp;

/** One row of {@code notification_attempt} plus the failure count of its run, derived by the read. */
public record NotificationAttempt(
    long id,
    String type,
    String target,
    String payload,
    String status,
    String message,
    Timestamp createdAt,
    Timestamp updatedAt,
    int failureCount) {}
