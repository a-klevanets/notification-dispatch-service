package com.example.notification.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

import com.example.notification.persistence.NotificationAttempt.Status;

/**
 * The attempt log. {@code Repository} rather than {@code CrudRepository}: a generated read selects every
 * mapped column, and {@code failureCount} has none. {@code save} is still implemented — Spring Data gives
 * every repository a {@code SimpleJdbcRepository} base class and matches it to the declared methods.
 */
public interface NotificationRepository extends Repository<NotificationAttempt, Long> {

  /** The type column's width; a longer value fails the write. */
  int TYPE_LENGTH = 32;

  /** The target column's width; a longer value fails the write. */
  int TARGET_LENGTH = 255;

  /** The message column's width; a longer text fails the write. */
  int MESSAGE_LENGTH = 255;

  /** Every attempt with the failures its run has accumulated; a caller appends its own filter or order. */
  String ATTEMPTS_WITH_FAILURE_COUNT =
      """
      WITH runs AS (
        SELECT id, type, target, payload, status, message, created_at, updated_at,
               MAX(CASE WHEN status = 'SENT' THEN id ELSE 0 END)
                 OVER (ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS run
          FROM notification_attempt
      )
      SELECT id, type, target, payload, status, message, created_at, updated_at,
             SUM(CASE WHEN status = 'ERROR' THEN 1 ELSE 0 END)
               OVER (PARTITION BY run ORDER BY id) AS failure_count
        FROM runs
      """;

  @Query(
      """
      SELECT COUNT(*)
      FROM notification_attempt
      WHERE target = :target
        AND created_at >= CURRENT_TIMESTAMP - INTERVAL '1' MINUTE
      """)
  int countRecentByTarget(String target);

  @Query(
      """
      SELECT COUNT(*)
      FROM notification_attempt
      WHERE status = 'ERROR'
        AND id > COALESCE((SELECT MAX(id) FROM notification_attempt WHERE status = 'SENT'), 0)
      """)
  int latestFailureCount();

  @Query(ATTEMPTS_WITH_FAILURE_COUNT + " WHERE id = :id")
  Optional<NotificationAttempt> findById(Long id);

  @Query(ATTEMPTS_WITH_FAILURE_COUNT + " ORDER BY id DESC LIMIT 1")
  Optional<NotificationAttempt> findLatest();

  @Query(ATTEMPTS_WITH_FAILURE_COUNT + " ORDER BY id DESC LIMIT 20")
  List<NotificationAttempt> findHistory();

  /** The raw write; {@link #setState} is the way in, it cuts the message to the column's width. */
  @Modifying
  @Query(
      """
      UPDATE notification_attempt
      SET status = :status, message = :message, updated_at = CURRENT_TIMESTAMP
      WHERE id = :id
      """)
  void writeState(long id, Status status, String message);

  default void setState(long id, Status status, String message) {
    writeState(id, status, shorten(message));
  }

  NotificationAttempt save(NotificationAttempt attempt);

  default long open(String type, String target, String payload) {
    return save(NotificationAttempt.opened(type, target, payload)).id();
  }

  private static String shorten(String value) {
    if (value == null || value.length() <= MESSAGE_LENGTH) {
      return value;
    }
    return value.substring(0, MESSAGE_LENGTH);
  }
}
