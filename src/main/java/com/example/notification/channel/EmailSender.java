package com.example.notification.channel;

import org.springframework.stereotype.Component;

@Component
class EmailSender implements NotificationSender {

  @Override
  public NotificationType type() {
    return NotificationType.EMAIL;
  }

  @Override
  public void send(String target, String payload) {
    if (target != null && target.contains("error")) {
      throw new RuntimeException("SMTP Timeout");
    }
    System.out.println("[EMAIL] " + target + " -> " + payload);
  }
}
