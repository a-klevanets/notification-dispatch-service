package com.example.notification.dispatch;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.example.notification.NotificationDispatchException;
import com.example.notification.NotificationDispatchException.Reason;
import com.example.notification.channel.NotificationType;
import com.example.notification.channel.SenderRegistry;
import com.example.notification.persistence.NotificationAttempt;
import com.example.notification.persistence.NotificationAttempt.Status;
import com.example.notification.persistence.NotificationRepository;

@Service
public class NotificationDispatcher {

  private final NotificationRepository repository;
  private final SenderRegistry senders;
  private final Blacklist blacklist;
  private final RateLimit rateLimit;
  private final CircuitBreaker breaker;

  public NotificationDispatcher(
      NotificationRepository repository,
      SenderRegistry senders,
      Blacklist blacklist,
      RateLimit rateLimit,
      CircuitBreaker breaker) {
    this.repository = repository;
    this.senders = senders;
    this.blacklist = blacklist;
    this.rateLimit = rateLimit;
    this.breaker = breaker;
  }

  public synchronized NotificationAttempt dispatch(String type, String target, String payload) {

    String safeType = type != null ? type.trim().toUpperCase(Locale.ROOT) : "";
    String safeTarget = target != null ? target.trim() : null;
    String safePayload = payload != null ? payload : "";

    if (safeTarget == null || safeTarget.isEmpty()) {
      throw new NotificationDispatchException(Reason.INVALID_REQUEST, "Target is mandatory.");
    }

    if (blacklist.blocks(safeTarget)) {
      throw new NotificationDispatchException(Reason.BLACKLISTED, "Recipient is blacklisted.");
    }
    if (rateLimit.exceeded(safeTarget)) {
      throw new NotificationDispatchException(Reason.RATE_LIMITED, "Recipient exceeded the rate limit.");
    }

    long id = repository.open(safeType, safeTarget, safePayload);

    if (breaker.isOpen()) {
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
      return repository.findById(id).orElseThrow();
    } catch (Exception e) {
      String detail = e.getMessage() != null ? e.getMessage() : e.toString();
      repository.setState(id, Status.ERROR, detail);
      System.out.println("[DB AUDIT] Inserted failure record for " + safeTarget + ": " + detail);
      throw new NotificationDispatchException(Reason.DELIVERY_FAILED, detail, e);
    }
  }

  public List<NotificationAttempt> getHistory() {
    return repository.findHistory();
  }

  public NotificationAttempt getLatestState() {
    return repository.findLatest().orElse(null);
  }
}
