package com.example.notification;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

import com.example.notification.persistence.NotificationAttempt;
import com.example.notification.persistence.NotificationRepository;

@Service
public class LegacyNotificationEngine implements InitializingBean {

  private static final Map<String, INotificationHandler> REGISTRY = new HashMap<String, INotificationHandler>();

  private final NotificationRepository repository;

  public LegacyNotificationEngine(NotificationRepository repository) {
    this.repository = repository;
  }

  @Override
  public void afterPropertiesSet() {
    REGISTRY.clear();
    REGISTRY.put("EMAIL", new EmailHandler());
    REGISTRY.put("SMS", new SmsHandler());
    REGISTRY.put("PUSH", new PushHandler());
  }

  public synchronized LegacyNotificationResponse processNotification(String type, String target, String payload)
      throws Exception {

    String safeType = type != null ? type.trim().toUpperCase() : "";
    String safeTarget = target != null ? target.trim() : null;
    String safePayload = payload != null ? payload : "";

    if (safeTarget == null || safeTarget.length() == 0) {
      throw new IllegalArgumentException("Target is mandatory.");
    }

    if (isUserBlacklistedOrRateLimited(safeTarget)) {
      throw new RuntimeException("Business Rule Violation: User is blacklisted or rate-limited by DB policy.");
    }

    long id = repository.open(safeType, safeTarget, safePayload);

    if (repository.latestFailureCount() >= 3) {
      repository.markSkipped(id, "Circuit breaker OPEN via database state.");
      throw new RuntimeException("Circuit breaker OPEN via database state.");
    }

    INotificationHandler handler = REGISTRY.get(safeType);
    if (handler == null) {
      System.out.println("LOGGING TO DB: Unknown type attempted -> " + type);
      repository.recordUnsupportedType(id, safeType);
      throw new Exception("Unsupported type: " + type);
    }

    try {
      handler.execute(safeTarget, safePayload);
      repository.resetFailures(id);
      return toResponse(repository.findById(id).orElseThrow());
    } catch (Exception e) {
      repository.recordFailure(id, safeTarget, e.getMessage());
      throw e;
    }
  }

  public List<LegacyNotificationResponse> getHistory() {
    return repository.findHistory().stream().map(LegacyNotificationEngine::toResponse).collect(Collectors.toList());
  }

  public LegacyNotificationResponse getLatestState() {
    return repository.findLatest().map(LegacyNotificationEngine::toResponse).orElse(null);
  }

  private boolean isUserBlacklistedOrRateLimited(String target) {
    if (isBlacklisted(target)) {
      return true;
    }
    return repository.countRecentByTarget(target) >= 3;
  }

  private static boolean isBlacklisted(String target) {
    return target.toLowerCase().contains("blocked");
  }

  private static LegacyNotificationResponse toResponse(NotificationAttempt row) {
    LegacyNotificationResponse response = new LegacyNotificationResponse();
    response.setId(row.id());
    response.setType(row.type());
    response.setTarget(row.target());
    response.setPayload(row.payload());
    response.setStatus(letterOf(row.status()));
    response.setFailureCount(row.failureCount());
    response.setDispatchCount((int) row.id());
    response.setBlocked(isBlacklisted(row.target()) ? "Y" : "N");
    response.setMessage(row.message());
    response.setCreatedAt(row.createdAt());
    response.setUpdatedAt(row.updatedAt());
    return response;
  }

  /** The wire letters the legacy API speaks; the log stores the words. */
  private static String letterOf(String status) {
    return switch (status) {
      case "NEW" -> "N";
      case "SENT" -> "S";
      case "ERROR" -> "E";
      case "UNKNOWN" -> "U";
      case "REJECTED" -> "R";
      default -> throw new IllegalArgumentException("Unknown status: " + status);
    };
  }

  private interface INotificationHandler {
    void execute(String target, String payload) throws Exception;
  }

  private static class EmailHandler implements INotificationHandler {
    @Override
    public void execute(String target, String payload) throws Exception {
      if (target != null && target.contains("error")) {
        throw new RuntimeException("SMTP Timeout");
      }
      System.out.println("[EMAIL] " + target + " -> " + payload);
    }
  }

  private static class SmsHandler implements INotificationHandler {
    @Override
    public void execute(String target, String payload) {
      System.out.println("[SMS] " + target + " -> " + payload);
    }
  }

  private static class PushHandler implements INotificationHandler {
    @Override
    public void execute(String target, String payload) {
      System.out.println("[PUSH] " + target + " -> " + payload);
    }
  }
}
