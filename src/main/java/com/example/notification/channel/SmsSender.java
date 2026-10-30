package com.example.notification.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class SmsSender implements NotificationSender {

  private static final Logger log = LoggerFactory.getLogger(SmsSender.class);

  @Override
  public NotificationType type() {
    return NotificationType.SMS;
  }

  @Override
  public void send(String target, String payload) {
    log.info("{} -> {}", target, payload);
  }
}
