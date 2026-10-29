package com.example.notification.dispatch;

import org.springframework.stereotype.Component;

import com.example.notification.persistence.NotificationRepository;

/** Open at three failures in a row; only a successful send closes it, and nothing times out. */
@Component
public class CircuitBreaker {

  private final NotificationRepository repository;

  public CircuitBreaker(NotificationRepository repository) {
    this.repository = repository;
  }

  public boolean isOpen() {
    return repository.latestFailureCount() >= 3;
  }
}
