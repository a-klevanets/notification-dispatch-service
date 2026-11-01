package com.example.notification.dispatch;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;

/**
 * The window is the last three calls this process made; three failures hold the path for five seconds,
 * then one call is let through to test the transport: it closes the breaker if it delivers and reopens
 * it for another five if it does not.
 */
@Component
public class CircuitBreaker {

  private static final int CALLS = 3;
  private static final int PROBE_CALLS = 1;
  private static final Duration OPEN_FOR = Duration.ofSeconds(5);

  private final io.github.resilience4j.circuitbreaker.CircuitBreaker breaker =
      io.github.resilience4j.circuitbreaker.CircuitBreaker.of(
          "dispatch",
          CircuitBreakerConfig.custom()
              .slidingWindowType(SlidingWindowType.COUNT_BASED)
              .slidingWindowSize(CALLS)
              .minimumNumberOfCalls(CALLS)
              .failureRateThreshold(100)
              .waitDurationInOpenState(OPEN_FOR)
              .permittedNumberOfCallsInHalfOpenState(PROBE_CALLS)
              .build());

  /** Asking is how the breaker is read: it half-opens on the ask, never on a state read, and the
   * answer spends the probe's one place. */
  public boolean admitsCall() {
    return breaker.tryAcquirePermission();
  }

  /** The library keeps no log, so every outcome is handed in; no duration is configured to matter. */
  public void recordSuccess() {
    breaker.onSuccess(0, TimeUnit.NANOSECONDS);
  }

  public void recordFailure(Throwable cause) {
    breaker.onError(0, TimeUnit.NANOSECONDS, cause);
  }
}
