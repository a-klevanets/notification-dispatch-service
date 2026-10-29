package com.example.notification.api;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.example.notification.dispatch.Blacklist;
import com.example.notification.dispatch.NotificationDispatcher;
import com.example.notification.persistence.NotificationAttempt;

@Controller
@RequestMapping("/api/legacy/notifications")
public class LegacyNotificationController {

  private final NotificationDispatcher dispatcher;
  private final Blacklist blacklist;

  public LegacyNotificationController(NotificationDispatcher dispatcher, Blacklist blacklist) {
    this.dispatcher = dispatcher;
    this.blacklist = blacklist;
  }

  @RequestMapping(value = "/send", method = RequestMethod.POST)
  @ResponseBody
  public LegacyNotificationResponse send(@RequestBody LegacyNotificationRequest request) {
    return toResponse(
        dispatcher.dispatch(request.getType(), request.getTarget(), request.getPayload()));
  }

  @RequestMapping(value = "/history", method = RequestMethod.GET)
  @ResponseBody
  public List<LegacyNotificationResponse> history() {
    return dispatcher.getHistory().stream().map(this::toResponse).toList();
  }

  @RequestMapping(value = "/latest", method = RequestMethod.GET)
  @ResponseBody
  public LegacyNotificationResponse latest() {
    NotificationAttempt latest = dispatcher.getLatestState();
    return latest == null ? null : toResponse(latest);
  }

  private LegacyNotificationResponse toResponse(NotificationAttempt attempt) {
    return LegacyNotificationResponse.from(attempt, blacklist.blocks(attempt.target()));
  }
}