package com.example.notification.api;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.notification.NotificationDispatchException;

/**
 * Maps dispatch refusals to an RFC 9457 body with a status per reason, so an unsupported type or a
 * blacklisted recipient no longer surfaces as an opaque 500.
 */
@RestControllerAdvice
public class NotificationExceptionHandler {

  @ExceptionHandler(NotificationDispatchException.class)
  ResponseEntity<ProblemDetail> dispatchRefused(NotificationDispatchException ex) {
    NotificationDispatchException.Reason reason = ex.reason();
    HttpStatus status = reason.status();
    ProblemDetail body = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
    body.setType(URI.create("https://errors.example.com/" + reason.slug()));
    body.setTitle(reason.title());
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
  }
}
