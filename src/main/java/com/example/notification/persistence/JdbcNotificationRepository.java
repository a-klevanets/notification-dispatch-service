package com.example.notification.persistence;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The SQL the service has always used, kept statement for statement so that moving it changes nothing
 * observable. Two facts about it are deliberate: {@code fc} and {@code dc} are read from the newest row
 * and carried into the next one, and the newest row is treated as the current state of the log.
 */
@Repository
public class JdbcNotificationRepository implements NotificationRepository {

  private static final int HISTORY_LIMIT = 20;
  private static final int MESSAGE_LENGTH = 255;

  private static final RowMapper<NotificationTransaction> ROW_MAPPER =
      (ResultSet rs, int rowNum) ->
          new NotificationTransaction(
              rs.getLong("id"),
              rs.getString("tp"),
              rs.getString("tgt"),
              rs.getString("pld"),
              rs.getString("st"),
              rs.getInt("fc"),
              rs.getInt("dc"),
              rs.getString("bl"),
              rs.getString("lm"),
              rs.getTimestamp("crt"),
              rs.getTimestamp("upd"));

  private final JdbcTemplate jdbcTemplate;

  public JdbcNotificationRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public int countRecentByTarget(String target) {
    Integer recentCount =
        jdbcTemplate.queryForObject(
            "select count(*) "
                + "from ntf_tx "
                + "where tgt = ? "
                + "  and crt >= dateadd('minute', -1, current_timestamp)",
            Integer.class,
            target);
    return recentCount == null ? 0 : recentCount.intValue();
  }

  @Override
  public long open(String type, String target, String payload) {
    long id = nextId();
    int nextDispatchCount = maxDispatchCount() + 1;
    int currentFailureCount = latestFailureCount();
    String blocked = isBlockedTarget(target) ? "Y" : "N";
    Timestamp now = new Timestamp(System.currentTimeMillis());

    jdbcTemplate.update(
        "insert into ntf_tx(id, tp, tgt, pld, st, fc, dc, bl, lm, crt, upd) "
            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        id,
        type,
        target,
        payload,
        "N",
        Integer.valueOf(currentFailureCount),
        Integer.valueOf(nextDispatchCount),
        blocked,
        "START",
        now,
        now);

    return id;
  }

  @Override
  public int latestFailureCount() {
    List<Integer> results =
        jdbcTemplate.query(
            "select fc from ntf_tx order by id desc limit 1",
            (ResultSet resultSet, int rowNum) -> Integer.valueOf(resultSet.getInt(1)));
    return results.isEmpty() ? 0 : results.get(0).intValue();
  }

  @Override
  public void recordFailure(long id, String target, String error) {
    int nextFailureCount = latestFailureCount() + 1;
    jdbcTemplate.update(
        "update ntf_tx set st = ?, fc = ?, lm = ?, upd = ? where id = ?",
        "E",
        Integer.valueOf(nextFailureCount),
        shorten(error),
        new Timestamp(System.currentTimeMillis()),
        Long.valueOf(id));
    System.out.println("[DB AUDIT] Inserted failure record for " + target + ": " + error);
  }

  @Override
  public void resetFailures(long id) {
    jdbcTemplate.update(
        "update ntf_tx set st = ?, fc = ?, lm = ?, upd = ? where id = ?",
        "S",
        Integer.valueOf(0),
        "SENT",
        new Timestamp(System.currentTimeMillis()),
        Long.valueOf(id));
  }

  @Override
  public void recordUnsupportedType(long id, String type) {
    jdbcTemplate.update(
        "update ntf_tx set st = ?, lm = ?, upd = ? where id = ?",
        "U",
        shorten("Unsupported type: " + type),
        new Timestamp(System.currentTimeMillis()),
        Long.valueOf(id));
  }

  @Override
  public void markSkipped(long id, String message) {
    jdbcTemplate.update(
        "update ntf_tx set st = ?, lm = ?, upd = ? where id = ?",
        "R",
        shorten(message),
        new Timestamp(System.currentTimeMillis()),
        Long.valueOf(id));
  }

  @Override
  public Optional<NotificationTransaction> findById(long id) {
    List<NotificationTransaction> rows =
        jdbcTemplate.query("select * from ntf_tx where id = ?", ROW_MAPPER, Long.valueOf(id));
    return rows.stream().findFirst();
  }

  @Override
  public Optional<NotificationTransaction> findLatest() {
    List<NotificationTransaction> rows =
        jdbcTemplate.query("select * from ntf_tx order by id desc limit 1", ROW_MAPPER);
    return rows.stream().findFirst();
  }

  @Override
  public List<NotificationTransaction> findHistory() {
    return jdbcTemplate.query(
        "select * from ntf_tx order by id desc limit " + HISTORY_LIMIT, ROW_MAPPER);
  }

  private long nextId() {
    Long maxId = jdbcTemplate.queryForObject("select coalesce(max(id), 0) from ntf_tx", Long.class);
    return (maxId == null ? 0L : maxId.longValue()) + 1L;
  }

  private int maxDispatchCount() {
    Integer latestDispatchCount =
        jdbcTemplate.queryForObject("select coalesce(max(dc), 0) from ntf_tx", Integer.class);
    return latestDispatchCount == null ? 0 : latestDispatchCount.intValue();
  }

  private static boolean isBlockedTarget(String target) {
    return target != null && target.toLowerCase().contains("blocked");
  }

  private static String shorten(String value) {
    if (value == null || value.length() <= MESSAGE_LENGTH) {
      return value;
    }
    return value.substring(0, MESSAGE_LENGTH);
  }
}
