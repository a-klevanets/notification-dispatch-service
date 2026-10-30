package com.example.notification.api;

import java.net.URI;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.example.notification.NotificationDispatchException;
import com.example.notification.NotificationDispatchException.Reason;

/**
 * Every error the API answers carries the same problem body, so a caller reads one shape whichever
 * layer refused it. Spring Boot registers its own advice for the framework errors handled here, and
 * this one has to outrank it, or the framework answers first with a body that has no {@code type}.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class NotificationExceptionHandler {

  @ExceptionHandler(NotificationDispatchException.class)
  ResponseEntity<ProblemDetail> requestRefused(NotificationDispatchException ex) {
    return refuse(ex.reason(), ex.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> requestInvalid(MethodArgumentNotValidException ex) {
    FieldError first = ex.getBindingResult().getFieldErrors().getFirst();
    return refuse(Reason.INVALID_REQUEST, first.getDefaultMessage());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ProblemDetail> bodyUnreadable() {
    return refuse(Reason.INVALID_REQUEST, "The request body could not be read.");
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ProblemDetail> noSuchPath(NoResourceFoundException ex) {
    return refuse(Reason.NOT_FOUND, "No resource at /" + ex.getResourcePath() + ".");
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ProblemDetail> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
    return refuse(
        HttpStatus.METHOD_NOT_ALLOWED, "method-not-allowed", "Method not allowed",
        ex.getMethod() + " is not supported.");
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ProblemDetail> queryParameterUnreadable(MethodArgumentTypeMismatchException ex) {
    return refuse(Reason.INVALID_REQUEST, ex.getName() + " must be a number.");
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ProblemDetail> mediaTypeUnsupported() {
    return refuse(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-media-type", "Unsupported media type",
        "Only application/json is accepted.");
  }

  private ResponseEntity<ProblemDetail> refuse(Reason reason, String detail) {
    return refuse(reason.status(), reason.slug(), reason.title(), detail);
  }

  private ResponseEntity<ProblemDetail> refuse(
      HttpStatus status, String slug, String title, String detail) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
    body.setType(URI.create("https://errors.example.com/" + slug));
    body.setTitle(title);
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
  }
}
