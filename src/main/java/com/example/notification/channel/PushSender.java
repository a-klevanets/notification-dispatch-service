package com.example.notification.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class PushSender implements NotificationSender {

  private static final Logger log = LoggerFactory.getLogger(PushSender.class);

  @Override
  public NotificationType type() {
    return NotificationType.PUSH;
  }

  @Override
  public void send(String target, String payload) {
    log.info("{} -> {}", target, payload);
  }
}
