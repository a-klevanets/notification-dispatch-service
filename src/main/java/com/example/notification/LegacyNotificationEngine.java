package com.example.notification;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.example.notification.NotificationDispatchException.Reason;
import com.example.notification.persistence.NotificationAttempt;
import com.example.notification.persistence.NotificationAttempt.Status;
import com.example.notification.persistence.NotificationRepository;

@Service
public class LegacyNotificationEngine {

  private final NotificationRepository repository;
  private final SenderRegistry senders;

  public LegacyNotificationEngine(NotificationRepository repository, SenderRegistry senders) {
    this.repository = repository;
    this.senders = senders;
  }

  public synchronized LegacyNotificationResponse processNotification(String type, String target, String payload) {

    String safeType = type != null ? type.trim().toUpperCase() : "";
    String safeTarget = target != null ? target.trim() : null;
    String safePayload = payload != null ? payload : "";

    if (safeTarget == null || safeTarget.isEmpty()) {
      throw new NotificationDispatchException(Reason.INVALID_REQUEST, "Target is mandatory.");
    }

    assertNotBlacklisted(safeTarget);
    assertNotRateLimited(safeTarget);

    long id = repository.open(safeType, safeTarget, safePayload);

    if (repository.latestFailureCount() >= 3) {
      repository.setState(id, Status.REJECTED, "Circuit breaker OPEN via database state.");
      throw new NotificationDispatchException(Reason.CIRCUIT_BREAKER_OPEN, "Circuit breaker OPEN via database state.");
    }

    Optional<NotificationType> channel = NotificationType.parse(safeType);
    if (channel.isEmpty()) {
      System.out.println("LOGGING TO DB: Unknown type attempted -> " + type);
      repository.setState(id, Status.UNKNOWN, "Unsupported type: " + safeType);
      throw new NotificationDispatchException(Reason.UNSUPPORTED_TYPE, "Unsupported type: " + type);
    }

    try {
      senders.senderFor(channel.get()).send(safeTarget, safePayload);
      repository.setState(id, Status.SENT, "SENT");
      return toResponse(repository.findById(id).orElseThrow());
    } catch (Exception e) {
      repository.setState(id, Status.ERROR, e.getMessage());
      System.out.println(
          "[DB AUDIT] Inserted failure record for " + safeTarget + ": " + e.getMessage());
      throw new NotificationDispatchException(Reason.DELIVERY_FAILED, e.getMessage(), e);
    }
  }

  public List<LegacyNotificationResponse> getHistory() {
    return repository.findHistory().stream().map(LegacyNotificationEngine::toResponse).collect(Collectors.toList());
  }

  public LegacyNotificationResponse getLatestState() {
    return repository.findLatest().map(LegacyNotificationEngine::toResponse).orElse(null);
  }

  private static boolean isBlacklisted(String target) {
    return target.toLowerCase().contains("blocked");
  }

  private void assertNotBlacklisted(String target) {
    if (isBlacklisted(target)) {
      throw new NotificationDispatchException(Reason.BLACKLISTED, "Recipient is blacklisted.");
    }
  }

  private void assertNotRateLimited(String target) {
    if (repository.countRecentByTarget(target) >= 3) {
      throw new NotificationDispatchException(Reason.RATE_LIMITED, "Recipient exceeded the rate limit.");
    }
  }

  private static LegacyNotificationResponse toResponse(NotificationAttempt attempt) {
    LegacyNotificationResponse response = new LegacyNotificationResponse();
    response.setId(attempt.id());
    response.setType(attempt.type());
    response.setTarget(attempt.target());
    response.setPayload(attempt.payload());
    response.setStatus(letterOf(attempt.status()));
    response.setFailureCount(attempt.failureCount());
    response.setDispatchCount(Math.toIntExact(attempt.id()));
    response.setBlocked(isBlacklisted(attempt.target()) ? "Y" : "N");
    response.setMessage(attempt.message());
    response.setCreatedAt(attempt.createdAt());
    response.setUpdatedAt(attempt.updatedAt());
    return response;
  }

  /** The wire letters the legacy API speaks; the log stores the words. */
  private static String letterOf(Status status) {
    return switch (status) {
      case NEW -> "N";
      case SENT -> "S";
      case ERROR -> "E";
      case UNKNOWN -> "U";
      case REJECTED -> "R";
    };
  }
}
