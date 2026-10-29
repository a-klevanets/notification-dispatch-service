package com.example.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import com.example.notification.persistence.NotificationAttempt;
import com.example.notification.persistence.NotificationAttempt.Status;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public class LegacyNotificationEngineIntegrationTest {

  @Autowired
  private LegacyNotificationEngine engine;

  @Test
  public void emailSuccessIsStoredAsSent() throws Exception {
    NotificationAttempt attempt = engine.processNotification("EMAIL", "buyer@example.com", "hello");

    assertThat(attempt.status()).isEqualTo(Status.SENT);
    assertThat(attempt.id()).isEqualTo(1L);
    assertThat(attempt.failureCount()).isZero();
    assertThat(engine.getHistory()).hasSize(1);
  }

  @Test
  public void blockedTargetFailsBeforeDispatchIsInserted() {
    try {
      engine.processNotification("SMS", "blocked-user@example.com", "hello");
      fail("Expected blocked target to fail");
    } catch (RuntimeException ex) {
      assertThat(ex.getMessage()).isEqualTo("Recipient is blacklisted.");
    } catch (Exception ex) {
      fail("Expected RuntimeException but got: " + ex.getClass().getName());
    }

    assertThat(engine.getHistory()).isEmpty();
  }

  @Test
  public void unsupportedTypeIsStoredAsUnknown() {
    try {
      engine.processNotification("FAX", "ops@example.com", "hello");
      fail("Expected unsupported type to fail");
    } catch (Exception ex) {
      assertThat(ex.getMessage()).isEqualTo("Unsupported type: FAX");
    }

    NotificationAttempt latest = engine.getLatestState();
    assertThat(latest.status()).isEqualTo(Status.UNKNOWN);
    assertThat(latest.message()).isEqualTo("Unsupported type: FAX");
  }

  @Test
  public void threeFailuresOpenCircuitBreakerForNextAttempt() {
    for (int i = 0; i < 3; i++) {
      try {
        engine.processNotification("EMAIL", "error-user@example.com", "boom");
        fail("Expected email failure");
      } catch (RuntimeException ex) {
        assertThat(ex.getMessage()).isEqualTo("SMTP Timeout");
      } catch (Exception ex) {
        fail("Expected RuntimeException but got: " + ex.getClass().getName());
      }
    }

    NotificationAttempt failed = engine.getLatestState();
    assertThat(failed.status()).isEqualTo(Status.ERROR);
    assertThat(failed.message()).isEqualTo("SMTP Timeout");
    assertThat(failed.failureCount()).isEqualTo(3);

    try {
      engine.processNotification("SMS", "ok-user@example.com", "hello");
      fail("Expected circuit breaker to reject the request");
    } catch (RuntimeException ex) {
      assertThat(ex.getMessage()).isEqualTo("Circuit breaker OPEN via database state.");
    } catch (Exception ex) {
      fail("Expected RuntimeException but got: " + ex.getClass().getName());
    }

    NotificationAttempt latest = engine.getLatestState();
    assertThat(latest.status()).isEqualTo(Status.REJECTED);
    assertThat(latest.failureCount()).isEqualTo(3);
    assertThat(latest.id()).isEqualTo(4L);
  }

  @Test
  public void rateLimitedTargetIsRefusedAfterThreeAttempts() throws Exception {
    for (int i = 0; i < 3; i++) {
      engine.processNotification("EMAIL", "busy@example.com", "hello");
    }

    try {
      engine.processNotification("EMAIL", "busy@example.com", "hello");
      fail("Expected the rate limit to refuse the fourth attempt");
    } catch (RuntimeException ex) {
      assertThat(ex.getMessage()).isEqualTo("Recipient exceeded the rate limit.");
    }

    assertThat(engine.getHistory()).hasSize(3);
  }

  @Test
  public void smsAndPushAreDeliveredAndRecorded() throws Exception {
    NotificationAttempt sms = engine.processNotification("SMS", "+70000000000", "hello");
    NotificationAttempt push = engine.processNotification("PUSH", "device-42", "hello");

    assertThat(sms.type()).isEqualTo("SMS");
    assertThat(sms.status()).isEqualTo(Status.SENT);
    assertThat(push.type()).isEqualTo("PUSH");
    assertThat(push.status()).isEqualTo(Status.SENT);
    assertThat(engine.getHistory()).hasSize(2);
  }

  @Test
  public void missingTargetIsRejected() throws Exception {
    try {
      engine.processNotification("EMAIL", "  ", "hello");
      fail("Expected a blank target to be rejected");
    } catch (NotificationDispatchException ex) {
      assertThat(ex.getMessage()).isEqualTo("Target is mandatory.");
    }

    try {
      engine.processNotification("EMAIL", null, "hello");
      fail("Expected a missing target to be rejected");
    } catch (NotificationDispatchException ex) {
      assertThat(ex.getMessage()).isEqualTo("Target is mandatory.");
    }

    assertThat(engine.getHistory()).isEmpty();
  }

  @Test
  public void targetWithBlockedInsideItsNameIsAlsoRefused() {
    try {
      engine.processNotification("EMAIL", "unblocked@example.com", "hello");
      fail("Expected the substring check to refuse the target");
    } catch (RuntimeException ex) {
      assertThat(ex.getMessage()).isEqualTo("Recipient is blacklisted.");
    } catch (Exception ex) {
      fail("Expected RuntimeException but got: " + ex.getClass().getName());
    }

    assertThat(engine.getHistory()).isEmpty();
  }

  @Test
  public void missingTypeIsStoredAsUnknown() throws Exception {
    try {
      engine.processNotification(null, "ops@example.com", "hello");
      fail("Expected a missing type to be refused");
    } catch (Exception ex) {
      assertThat(ex.getMessage()).isEqualTo("Unsupported type: null");
    }

    NotificationAttempt latest = engine.getLatestState();
    assertThat(latest.status()).isEqualTo(Status.UNKNOWN);
    assertThat(latest.message()).isEqualTo("Unsupported type: ");
  }

  @Test
  public void missingPayloadIsStoredAsEmptyText() throws Exception {
    NotificationAttempt attempt = engine.processNotification("PUSH", "device-42", null);

    assertThat(attempt.status()).isEqualTo(Status.SENT);
    assertThat(attempt.payload()).isEmpty();
  }

  @Test
  public void latestStateIsNullBeforeAnythingIsSent() {
    assertThat(engine.getLatestState()).isNull();
  }

  @Test
  public void historyIsCappedAtTheTwentyNewestAttempts() throws Exception {
    for (int i = 0; i < 21; i++) {
      engine.processNotification("SMS", "+700000000" + i, "hello");
    }

    List<NotificationAttempt> history = engine.getHistory();
    assertThat(history).hasSize(20);
    assertThat(history.getFirst().target()).isEqualTo("+70000000020");
  }
}
