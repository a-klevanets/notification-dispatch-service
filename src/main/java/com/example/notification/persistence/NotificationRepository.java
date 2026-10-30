package com.example.notification.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

import com.example.notification.persistence.NotificationAttempt.Status;

/**
 * The attempt log. {@code Repository} rather than {@code CrudRepository}: the interface declares
 * the reads and writes the service uses, and nothing else. {@code save} has no body; Spring Data
 * supplies it from {@code SimpleJdbcRepository} and matches it to the declared methods.
 */
public interface NotificationRepository extends Repository<NotificationAttempt, Long> {

  /** The column widths from {@code schema.sql}; a longer value fails the write. */
  int TYPE_LENGTH = 32;
  int TARGET_LENGTH = 255;
  int MESSAGE_LENGTH = 255;

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
  int failuresSinceLastSuccess();

  Optional<NotificationAttempt> findById(Long id);

  List<NotificationAttempt> findByOrderByIdDesc(Limit limit);

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
