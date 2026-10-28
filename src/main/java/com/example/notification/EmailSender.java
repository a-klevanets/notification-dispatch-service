package com.example.notification;

import org.springframework.stereotype.Component;

@Component
class EmailSender implements NotificationSender {

  @Override
  public String type() {
    return "EMAIL";
  }

  @Override
  public void send(String target, String payload) {
    if (target != null && target.contains("error")) {
      throw new RuntimeException("SMTP Timeout");
    }
    System.out.println("[EMAIL] " + target + " -> " + payload);
  }
}
