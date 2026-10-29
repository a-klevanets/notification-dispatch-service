package com.example.notification;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.example.notification.persistence.NotificationAttempt;

@Controller
@RequestMapping("/api/legacy/notifications")
public class LegacyNotificationController {

  private final LegacyNotificationEngine engine;

  public LegacyNotificationController(LegacyNotificationEngine engine) {
    this.engine = engine;
  }

  @RequestMapping(value = "/send", method = RequestMethod.POST)
  @ResponseBody
  public LegacyNotificationResponse send(@RequestBody LegacyNotificationRequest request) {
    return toResponse(
        engine.processNotification(request.getType(), request.getTarget(), request.getPayload()));
  }

  @RequestMapping(value = "/history", method = RequestMethod.GET)
  @ResponseBody
  public List<LegacyNotificationResponse> history() {
    return engine.getHistory().stream().map(this::toResponse).toList();
  }

  @RequestMapping(value = "/latest", method = RequestMethod.GET)
  @ResponseBody
  public LegacyNotificationResponse latest() {
    NotificationAttempt latest = engine.getLatestState();
    return latest == null ? null : toResponse(latest);
  }

  private LegacyNotificationResponse toResponse(NotificationAttempt attempt) {
    return LegacyNotificationResponse.from(
        attempt, LegacyNotificationEngine.isBlacklisted(attempt.target()));
  }
}