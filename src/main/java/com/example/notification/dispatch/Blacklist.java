package com.example.notification.dispatch;

import java.util.Locale;

import org.springframework.stereotype.Component;

/** A target is blocked when its text contains {@code blocked}, whatever else the address says. */
@Component
public class Blacklist {

  public boolean blocks(String target) {
    return target.toLowerCase(Locale.ROOT).contains("blocked");
  }
}
