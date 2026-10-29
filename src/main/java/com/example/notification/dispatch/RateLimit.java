package com.example.notification.dispatch;

import org.springframework.stereotype.Component;

import com.example.notification.persistence.NotificationRepository;

/** A target is limited once three of its attempts fall in the last minute, whatever the outcome. */
@Component
public class RateLimit {

  private final NotificationRepository repository;

  public RateLimit(NotificationRepository repository) {
    this.repository = repository;
  }

  public boolean exceeded(String target) {
    return repository.countRecentByTarget(target) >= 3;
  }
}
