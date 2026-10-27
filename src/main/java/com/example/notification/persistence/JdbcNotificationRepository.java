package com.example.notification.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcNotificationRepository implements NotificationRepository {

  private static final int HISTORY_LIMIT = 20;
  private static final int MESSAGE_LENGTH = 255;

  private static final String ATTEMPTS_WITH_FAILURE_COUNT =
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

  private static final RowMapper<NotificationAttempt> ROW_MAPPER =
      (ResultSet rs, int _) ->
          new NotificationAttempt(
              rs.getLong("id"),
              rs.getString("type"),
              rs.getString("target"),
              rs.getString("payload"),
              rs.getString("status"),
              rs.getString("message"),
              rs.getTimestamp("created_at"),
              rs.getTimestamp("updated_at"),
              rs.getInt("failure_count"));

  private final JdbcTemplate jdbcTemplate;

  public JdbcNotificationRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public int countRecentByTarget(String target) {
    Integer recentCount =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM notification_attempt
            WHERE target = ?
              AND created_at >= CURRENT_TIMESTAMP - INTERVAL '1' MINUTE
            """,
            Integer.class,
            target);
    return recentCount == null ? 0 : recentCount;
  }

  @Override
  public long open(String type, String target, String payload) {
    KeyHolder keys = new GeneratedKeyHolder();
    jdbcTemplate.update(
        connection -> {
          PreparedStatement statement =
              connection.prepareStatement(
                  """
                  INSERT INTO notification_attempt
                    (type, target, payload, status, message, created_at, updated_at)
                  VALUES (?, ?, ?, 'NEW', 'START', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                  """,
                  Statement.RETURN_GENERATED_KEYS);
          statement.setString(1, type);
          statement.setString(2, target);
          statement.setString(3, payload);
          return statement;
        },
        keys);
    return keys.getKey().longValue();
  }

  @Override
  public int latestFailureCount() {
    Integer count =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM notification_attempt
            WHERE status = 'ERROR'
              AND id > COALESCE(
                (SELECT MAX(id) FROM notification_attempt WHERE status = 'SENT'),
                0
              )
            """,
            Integer.class);
    return count == null ? 0 : count;
  }

  @Override
  public void recordFailure(long id, String target, String error) {
    jdbcTemplate.update(
        """
        UPDATE notification_attempt
        SET status = 'ERROR', message = ?, updated_at = CURRENT_TIMESTAMP
        WHERE id = ?
        """,
        shorten(error),
        id);
    System.out.println("[DB AUDIT] Inserted failure record for " + target + ": " + error);
  }

  @Override
  public void resetFailures(long id) {
    jdbcTemplate.update(
        """
        UPDATE notification_attempt
        SET status = 'SENT', message = 'SENT', updated_at = CURRENT_TIMESTAMP
        WHERE id = ?
        """,
        id);
  }

  @Override
  public void recordUnsupportedType(long id, String type) {
    jdbcTemplate.update(
        """
        UPDATE notification_attempt
        SET status = 'UNKNOWN', message = ?, updated_at = CURRENT_TIMESTAMP
        WHERE id = ?
        """,
        shorten("Unsupported type: " + type),
        id);
  }

  @Override
  public void markSkipped(long id, String message) {
    jdbcTemplate.update(
        """
        UPDATE notification_attempt
        SET status = 'REJECTED', message = ?, updated_at = CURRENT_TIMESTAMP
        WHERE id = ?
        """,
        shorten(message),
        id);
  }

  @Override
  public Optional<NotificationAttempt> findById(long id) {
    List<NotificationAttempt> rows =
        jdbcTemplate.query(ATTEMPTS_WITH_FAILURE_COUNT + " WHERE id = ?", ROW_MAPPER, id);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<NotificationAttempt> findLatest() {
    List<NotificationAttempt> rows =
        jdbcTemplate.query(ATTEMPTS_WITH_FAILURE_COUNT + " ORDER BY id DESC LIMIT 1", ROW_MAPPER);
    return rows.stream().findFirst();
  }

  @Override
  public List<NotificationAttempt> findHistory() {
    return jdbcTemplate.query(
        ATTEMPTS_WITH_FAILURE_COUNT + " ORDER BY id DESC LIMIT " + HISTORY_LIMIT, ROW_MAPPER);
  }

  private static String shorten(String value) {
    if (value == null || value.length() <= MESSAGE_LENGTH) {
      return value;
    }
    return value.substring(0, MESSAGE_LENGTH);
  }
}
