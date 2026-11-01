package com.example.notification.dispatch;

import java.time.Duration;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

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

  /** A bucket untouched for a minute has refilled itself, so it is dropped rather than kept. */
  private final LoadingCache<String, Bucket> buckets =
      Caffeine.newBuilder().expireAfterAccess(MINUTE).build(_ -> newBucket());

  /** Takes the token it answers about, so a refusal is a decision and not a read. */
  public boolean exceeded(String target) {
    return !buckets.get(target).tryConsume(1);
  }

  private static Bucket newBucket() {
    Bandwidth limit =
        Bandwidth.builder().capacity(PER_MINUTE).refillGreedy(PER_MINUTE, MINUTE).build();
    return Bucket.builder().addLimit(limit).build();
  }
}
