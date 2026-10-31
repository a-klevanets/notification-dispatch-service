package com.example.notification.dispatch;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;

/**
 * A target is limited once its bucket runs dry: three tokens a minute, refilled smoothly, so the
 * fourth attempt passes twenty seconds after the third instead of a minute after the first.
 */
@Component
public class RateLimit {

  private static final int PER_MINUTE = 3;
  private static final Duration MINUTE = Duration.ofMinutes(1);

  /** One bucket per target for the life of the process; no entry is ever evicted. */
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  /** Takes the token it answers about, so a refusal is a decision and not a read. */
  public boolean exceeded(String target) {
    return !buckets.computeIfAbsent(target, _ -> newBucket()).tryConsume(1);
  }

  private static Bucket newBucket() {
    Bandwidth limit =
        Bandwidth.builder().capacity(PER_MINUTE).refillGreedy(PER_MINUTE, MINUTE).build();
    return Bucket.builder().addLimit(limit).build();
  }
}
