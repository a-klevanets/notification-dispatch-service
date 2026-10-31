package com.example.notification.dispatch;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;

/**
 * Once open it stays open for the life of the process: the library recovers on a timer, so the probe
 * is dated ten years out. The window is the last three calls this process made.
 */
@Component
public class CircuitBreaker {

  private static final int FAILURES = 3;
  private static final Duration NOT_ANYTIME_SOON = Duration.ofDays(3650);

  private final io.github.resilience4j.circuitbreaker.CircuitBreaker breaker =
      io.github.resilience4j.circuitbreaker.CircuitBreaker.of(
          "dispatch",
          CircuitBreakerConfig.custom()
              .slidingWindowType(SlidingWindowType.COUNT_BASED)
              .slidingWindowSize(FAILURES)
              .minimumNumberOfCalls(FAILURES)
              .failureRateThreshold(100)
              .waitDurationInOpenState(NOT_ANYTIME_SOON)
              .build());

  public boolean isOpen() {
    return breaker.getState() == State.OPEN;
  }

  /** The library keeps no log, so every outcome is handed in; no duration is configured to matter. */
  public void recordSuccess() {
    breaker.onSuccess(0, TimeUnit.NANOSECONDS);
  }

  public void recordFailure(Throwable cause) {
    breaker.onError(0, TimeUnit.NANOSECONDS, cause);
  }
}
