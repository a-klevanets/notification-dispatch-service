package com.example.notification.dispatch;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
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

  private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

  private final NotificationRepository repository;
  private final SenderRegistry senders;
  private final Blacklist blacklist;
  private final RateLimit rateLimit;
  private final CircuitBreaker breaker;

  /** The rate limit counts rows other dispatches insert, so count and insert stay atomic. */
  private static final int STRIPES = 1024;

  private final Object[] admission =
      IntStream.range(0, STRIPES).mapToObj(_ -> new Object()).toArray();

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

  public NotificationAttempt dispatch(String type, String target, String payload) {

    String safeType = type.trim().toUpperCase(Locale.ROOT);
    String safeTarget = target.trim();

    if (blacklist.blocks(safeTarget)) {
      throw new NotificationDispatchException(Reason.BLACKLISTED, "Recipient is blacklisted.");
    }

    long id;
    synchronized (admission[stripeOf(safeTarget)]) {
      if (rateLimit.exceeded(safeTarget)) {
        throw new NotificationDispatchException(
            Reason.RATE_LIMITED, "Recipient exceeded the rate limit.");
      }
      id = repository.open(safeType, safeTarget, payload);
    }

    if (breaker.isOpen()) {
      repository.setState(id, Status.REJECTED, "Circuit breaker OPEN via database state.");
      throw new NotificationDispatchException(Reason.CIRCUIT_BREAKER_OPEN, "Circuit breaker OPEN via database state.");
    }

    Optional<NotificationType> channel = NotificationType.parse(safeType);
    if (channel.isEmpty()) {
      repository.setState(id, Status.UNKNOWN, "Unsupported type: " + safeType);
      throw new NotificationDispatchException(Reason.UNSUPPORTED_TYPE, "Unsupported type: " + safeType);
    }

    try {
      senders.senderFor(channel.get()).send(safeTarget, payload);
    } catch (Exception e) {
      String detail = e.getMessage() != null ? e.getMessage() : e.toString();
      repository.setState(id, Status.ERROR, detail);
      log.warn("Delivery failed for {}: {}", safeTarget, detail);
      throw new NotificationDispatchException(Reason.DELIVERY_FAILED, detail, e);
    }

    repository.setState(id, Status.SENT, "SENT");
    return repository.findById(id).orElseThrow();
  }

  public List<NotificationAttempt> getHistory(int limit) {
    return repository.findByOrderByIdDesc(Limit.of(limit));
  }

  /**
   * The attempt is a record of what happened, so an update may only attach a note that took no part
   * in the delivery; the fields the log observed stay as they were written.
   */
  public NotificationAttempt note(long id, String note) {
    if (repository.writeNote(id, note) == 0) {
      throw new NotificationDispatchException(Reason.NOT_FOUND, "No attempt " + id + ".");
    }
    return repository.findById(id).orElseThrow();
  }

  /** A modulus would read the low bits alone; the high word of the product uses the whole hash. */
  private static int stripeOf(String target) {
    return (int) ((Integer.toUnsignedLong(target.hashCode()) * STRIPES) >>> 32);
  }
}
