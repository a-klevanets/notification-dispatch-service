package com.example.notification.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.notification.dispatch.NotificationDispatcher;
import com.example.notification.persistence.NotificationAttempt;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

  private final NotificationDispatcher dispatcher;

  public NotificationController(NotificationDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  @PostMapping
  public NotificationResponse send(@RequestBody NotificationRequest request) {
    return NotificationResponse.from(
        dispatcher.dispatch(request.type(), request.recipient(), request.payload()));
  }

  @GetMapping
  public List<NotificationResponse> history() {
    return dispatcher.getHistory().stream().map(NotificationResponse::from).toList();
  }

  /** An empty log has no newest attempt; the answer is 200 with an empty body, not 404. */
  @GetMapping("/latest")
  public NotificationResponse latest() {
    NotificationAttempt latest = dispatcher.getLatestState();
    return latest == null ? null : NotificationResponse.from(latest);
  }
}
