package com.example.notification.api;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.notification.NotificationDispatchException;
import com.example.notification.NotificationDispatchException.Reason;
import com.example.notification.dispatch.NotificationDispatcher;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

  /** The largest page the log answers with; asking for more is refused, not clamped. */
  private static final int MAX_LIMIT = 100;

  private final NotificationDispatcher dispatcher;

  public NotificationController(NotificationDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  @PostMapping
  public NotificationResponse send(@Valid @RequestBody NotificationRequest request) {
    return NotificationResponse.from(
        dispatcher.dispatch(request.type(), request.recipient(), request.payload()));
  }

  @GetMapping
  public List<NotificationResponse> history(@RequestParam(defaultValue = "20") int limit) {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new NotificationDispatchException(
          Reason.INVALID_REQUEST, "limit must be between 1 and " + MAX_LIMIT + ".");
    }
    return dispatcher.getHistory(limit).stream().map(NotificationResponse::from).toList();
  }
}
