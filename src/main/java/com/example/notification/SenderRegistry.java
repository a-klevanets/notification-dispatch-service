package com.example.notification;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * The channels this service can deliver, keyed by type. Built once from every {@link NotificationSender}
 * component, so the set of channels is whatever is on the classpath rather than a list maintained by
 * hand; two senders claiming the same type fail startup.
 */
@Component
public class SenderRegistry {

  private final Map<String, NotificationSender> senders;

  public SenderRegistry(List<NotificationSender> senders) {
    this.senders = senders.stream()
        .collect(Collectors.toUnmodifiableMap(NotificationSender::type, Function.identity()));
  }

  /** The sender for a normalized type, or {@code null} when no channel answers to it. */
  public NotificationSender find(String type) {
    return senders.get(type);
  }
}
