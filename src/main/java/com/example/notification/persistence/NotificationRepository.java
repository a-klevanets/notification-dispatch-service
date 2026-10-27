package com.example.notification.persistence;

import java.util.List;
import java.util.Optional;

/** Storage of the notification attempt log, expressed in the terms the dispatch logic uses. */
public interface NotificationRepository {

  int countRecentByTarget(String target);

  long open(String type, String target, String payload);

  int latestFailureCount();

  void recordFailure(long id, String target, String error);

  void resetFailures(long id);

  void recordUnsupportedType(long id, String type);

  void markSkipped(long id, String message);

  Optional<NotificationAttempt> findById(long id);

  Optional<NotificationAttempt> findLatest();

  List<NotificationAttempt> findHistory();
}
