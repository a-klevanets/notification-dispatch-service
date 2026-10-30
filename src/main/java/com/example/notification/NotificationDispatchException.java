package com.example.notification;

import java.util.Locale;

import org.springframework.http.HttpStatus;

/**
 * A refusal: what a request asked for cannot be done. The reason carries the HTTP status and title
 * the API answers with; the message keeps the specific detail.
 */
public class NotificationDispatchException extends RuntimeException {

  /** Why a request was refused and the HTTP status it maps to. */
  public enum Reason {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid request"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),
    UNSUPPORTED_TYPE(HttpStatus.BAD_REQUEST, "Unsupported notification type"),
    BLACKLISTED(HttpStatus.FORBIDDEN, "Recipient blacklisted"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Recipient rate-limited"),
    CIRCUIT_BREAKER_OPEN(HttpStatus.SERVICE_UNAVAILABLE, "Dispatch unavailable"),
    DELIVERY_FAILED(HttpStatus.BAD_GATEWAY, "Delivery failed");

    private final HttpStatus status;
    private final String title;

    Reason(HttpStatus status, String title) {
      this.status = status;
      this.title = title;
    }

    public HttpStatus status() {
      return status;
    }

    public String title() {
      return title;
    }

    /** A stable slug for the ProblemDetail {@code type} URI, e.g. {@code rate-limited}. */
    public String slug() {
      return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
  }

  private final Reason reason;

  public NotificationDispatchException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public NotificationDispatchException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
