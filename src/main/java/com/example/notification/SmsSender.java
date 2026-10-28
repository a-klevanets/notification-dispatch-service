package com.example.notification;

import org.springframework.stereotype.Component;

@Component
class SmsSender implements NotificationSender {

  @Override
  public String type() {
    return "SMS";
  }

  @Override
  public void send(String target, String payload) {
    System.out.println("[SMS] " + target + " -> " + payload);
  }
}
