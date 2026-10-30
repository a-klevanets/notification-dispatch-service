package com.example.notification.api;

import java.net.URI;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.notification.NotificationDispatchException;
import com.example.notification.NotificationDispatchException.Reason;

/** Outranks the advice Spring Boot registers for framework errors, so both answers share a shape. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class NotificationExceptionHandler {

  @ExceptionHandler(NotificationDispatchException.class)
  ResponseEntity<ProblemDetail> dispatchRefused(NotificationDispatchException ex) {
    return refuse(ex.reason(), ex.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> requestInvalid(MethodArgumentNotValidException ex) {
    FieldError first = ex.getBindingResult().getFieldErrors().getFirst();
    return refuse(Reason.INVALID_REQUEST, first.getDefaultMessage());
  }

  private ResponseEntity<ProblemDetail> refuse(Reason reason, String detail) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(reason.status(), detail);
    body.setType(URI.create("https://errors.example.com/" + reason.slug()));
    body.setTitle(reason.title());
    return ResponseEntity.status(reason.status())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }
}
