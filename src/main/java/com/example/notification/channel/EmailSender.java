package com.example.notification.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class EmailSender implements NotificationSender {

  private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

  @Override
  public NotificationType type() {
    return NotificationType.EMAIL;
  }

  @Override
  public void send(String target, String payload) {
    if (target != null && target.contains("error")) {
      throw new RuntimeException("SMTP Timeout");
    }
    log.info("{} -> {}", target, payload);
  }
}
