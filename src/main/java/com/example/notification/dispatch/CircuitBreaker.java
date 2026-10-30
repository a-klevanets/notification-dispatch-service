package com.example.notification.dispatch;

import org.springframework.stereotype.Component;

import com.example.notification.persistence.NotificationRepository;

/**
 * Once open it stays open: no timer, no half-open probe, and no success can reach the counter that
 * would reset it.
 */
@Component
public class CircuitBreaker {

  private final NotificationRepository repository;

  public CircuitBreaker(NotificationRepository repository) {
    this.repository = repository;
  }

  public boolean isOpen() {
    return repository.failuresSinceLastSuccess() >= 3;
  }
}
