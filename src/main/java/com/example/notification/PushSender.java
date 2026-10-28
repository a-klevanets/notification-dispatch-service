package com.example.notification;

import org.springframework.stereotype.Component;

@Component
class PushSender implements NotificationSender {

  @Override
  public NotificationType type() {
    return NotificationType.PUSH;
  }

  @Override
  public void send(String target, String payload) {
    System.out.println("[PUSH] " + target + " -> " + payload);
  }
}
