package com.example.notification;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * The channels this service can deliver, keyed by {@link NotificationType}. Built once from every
 * {@link NotificationSender} component, so the set of channels is whatever is on the classpath rather
 * than a list maintained by hand. Startup fails when two senders claim one type, or when a type has no
 * sender at all.
 */
@Component
public class SenderRegistry {

  private final Map<NotificationType, NotificationSender> senders;

  public SenderRegistry(List<NotificationSender> senders) {
    this.senders = senders.stream()
        .collect(Collectors.toUnmodifiableMap(NotificationSender::type, Function.identity()));
    for (NotificationType type : NotificationType.values()) {
      if (!this.senders.containsKey(type)) {
        throw new IllegalStateException("No NotificationSender registered for " + type);
      }
    }
  }

  /** Never {@code null}: the registry is complete over every type. */
  public NotificationSender senderFor(NotificationType type) {
    return senders.get(type);
  }
}
