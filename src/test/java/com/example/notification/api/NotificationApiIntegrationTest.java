package com.example.notification.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The contract, asserted where a caller meets it: the rules the service enforces, the status and the
 * RFC 9457 body each refusal answers with, the shape a stored attempt is reported in, and the two read
 * endpoints. Nothing is mocked - a real context over a real H2. The four tests the template shipped
 * with come first, the rest follow the order of the contract table in the README. MockMvc is wired
 * from the context here rather than by {@code @AutoConfigureMockMvc}, which Spring Boot 4 moved into a
 * {@code spring-boot-webmvc-test} module this build does not carry.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public class NotificationApiIntegrationTest {

  private static final String SEND = "/api/legacy/notifications/send";
  private static final String HISTORY = "/api/legacy/notifications/history";
  private static final String LATEST = "/api/legacy/notifications/latest";

  @Autowired
  private WebApplicationContext context;

  private MockMvc mockMvc;

  @BeforeEach
  public void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
  }

  @Test
  public void emailSuccessIsStoredAsSent() throws Exception {
    send("EMAIL", "buyer@example.com")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(jsonPath("$.type").value("EMAIL"))
        .andExpect(jsonPath("$.target").value("buyer@example.com"))
        .andExpect(jsonPath("$.payload").value("hello"))
        .andExpect(jsonPath("$.status").value("S"))
        .andExpect(jsonPath("$.failureCount").value(0))
        .andExpect(jsonPath("$.dispatchCount").value(1))
        .andExpect(jsonPath("$.blocked").value("N"))
        .andExpect(jsonPath("$.message").value("SENT"))
        .andExpect(jsonPath("$.createdAt").exists())
        .andExpect(jsonPath("$.updatedAt").exists());

    // Read back - the write path proves the mapper, not that the read endpoints use the same query.
    latest()
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.target").value("buyer@example.com"))
        .andExpect(jsonPath("$.payload").value("hello"))
        .andExpect(jsonPath("$.blocked").value("N"))
        .andExpect(jsonPath("$.createdAt").exists())
        .andExpect(jsonPath("$.updatedAt").exists());

    history().andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  public void blockedTargetFailsBeforeDispatchIsInserted() throws Exception {
    expectProblem(
        send("SMS", "blocked-user@example.com"),
        403,
        "blacklisted",
        "Recipient blacklisted",
        "Recipient is blacklisted.");

    expectNothingIsStored();
  }

  @Test
  public void unsupportedTypeIsStoredAsUnknown() throws Exception {
    expectProblem(
        send("FAX", "ops@example.com"),
        400,
        "unsupported-type",
        "Unsupported notification type",
        "Unsupported type: FAX");

    latest()
        .andExpect(jsonPath("$.type").value("FAX"))
        .andExpect(jsonPath("$.status").value("U"))
        .andExpect(jsonPath("$.message").value("Unsupported type: FAX"));
  }

  @Test
  public void threeFailuresOpenCircuitBreakerForNextAttempt() throws Exception {
    send("EMAIL", "error-one@example.com").andExpect(status().isBadGateway());
    send("EMAIL", "error-two@example.com").andExpect(status().isBadGateway());
    send("EMAIL", "error-three@example.com").andExpect(status().isBadGateway());

    latest()
        .andExpect(jsonPath("$.status").value("E"))
        .andExpect(jsonPath("$.message").value("SMTP Timeout"))
        .andExpect(jsonPath("$.failureCount").value(3));

    expectProblem(
        send("SMS", "ok-user@example.com"),
        503,
        "circuit-breaker-open",
        "Dispatch unavailable",
        "Circuit breaker OPEN via database state.");

    latest()
        .andExpect(jsonPath("$.id").value(4))
        .andExpect(jsonPath("$.dispatchCount").value(4))
        .andExpect(jsonPath("$.status").value("R"))
        .andExpect(jsonPath("$.failureCount").value(3));
  }

  @Test
  public void allThreeChannelsAreDeliveredAndRecorded() throws Exception {
    send("EMAIL", "buyer@example.com")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("S"));
    send("SMS", "+70000000000")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("SMS"))
        .andExpect(jsonPath("$.status").value("S"));
    send("PUSH", "device-42")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("PUSH"))
        .andExpect(jsonPath("$.status").value("S"));

    history().andExpect(jsonPath("$.length()").value(3));
  }

  /** The guard matches a substring of the target, not a list, so this address is refused. */
  @Test
  public void targetWithBlockedInsideItsNameIsAlsoRefused() throws Exception {
    expectProblem(
        send("EMAIL", "unblocked@example.com"),
        403,
        "blacklisted",
        "Recipient blacklisted",
        "Recipient is blacklisted.");

    expectNothingIsStored();
  }

  @Test
  public void rateLimitedTargetIsRefusedAfterThreeAttempts() throws Exception {
    for (int i = 0; i < 3; i++) {
      send("EMAIL", "busy@example.com").andExpect(status().isOk());
    }

    expectProblem(
        send("EMAIL", "busy@example.com"),
        429,
        "rate-limited",
        "Recipient rate-limited",
        "Recipient exceeded the rate limit.");

    history().andExpect(jsonPath("$.length()").value(3));
  }

  @Test
  public void missingTargetIsRejected() throws Exception {
    expectProblem(
        send("EMAIL", "  "), 400, "invalid-request", "Invalid request", "Target is mandatory.");
    expectProblem(
        send("""
            {"type": "EMAIL", "payload": "hello"}"""),
        400,
        "invalid-request",
        "Invalid request",
        "Target is mandatory.");

    expectNothingIsStored();
  }

  @Test
  public void missingPayloadIsStoredAsEmptyText() throws Exception {
    send("""
        {"type": "PUSH", "target": "device-42"}""")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("S"))
        .andExpect(jsonPath("$.payload").value(""));
  }

  /** The refusal quotes what arrived, the log stores the normalized value, so the two differ. */
  @Test
  public void missingTypeIsStoredAsUnknown() throws Exception {
    expectProblem(
        send("""
            {"target": "ops@example.com", "payload": "hello"}"""),
        400,
        "unsupported-type",
        "Unsupported notification type",
        "Unsupported type: null");

    latest()
        .andExpect(jsonPath("$.status").value("U"))
        .andExpect(jsonPath("$.message").value("Unsupported type: "));
  }

  @Test
  public void channelFailureIsStoredAsAnError() throws Exception {
    expectProblem(
        send("EMAIL", "error-user@example.com"),
        502,
        "delivery-failed",
        "Delivery failed",
        "SMTP Timeout");

    latest()
        .andExpect(jsonPath("$.status").value("E"))
        .andExpect(jsonPath("$.message").value("SMTP Timeout"))
        .andExpect(jsonPath("$.failureCount").value(1));
  }

  @Test
  public void historyIsCappedAtTheTwentyNewestAttempts() throws Exception {
    for (int i = 0; i < 21; i++) {
      send("SMS", "+700000000" + i).andExpect(status().isOk());
    }

    history()
        .andExpect(jsonPath("$.length()").value(20))
        .andExpect(jsonPath("$[0].target").value("+70000000020"))
        .andExpect(jsonPath("$[19].target").value("+7000000001"));
  }

  @Test
  public void latestIsEmptyBeforeAnythingIsSent() throws Exception {
    latest().andExpect(content().string(""));
  }

  /** The problem body every refusal shares: a status, a type slug, a title and the detail. */
  private void expectProblem(
      ResultActions result, int status, String slug, String title, String detail) throws Exception {
    result
        .andExpect(status().is(status))
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.example.com/" + slug))
        .andExpect(jsonPath("$.title").value(title))
        .andExpect(jsonPath("$.detail").value(detail))
        .andExpect(jsonPath("$.status").value(status));
  }

  private void expectNothingIsStored() throws Exception {
    history().andExpect(jsonPath("$.length()").value(0));
  }

  private ResultActions history() throws Exception {
    return mockMvc
        .perform(get(HISTORY))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON));
  }

  private ResultActions latest() throws Exception {
    return mockMvc.perform(get(LATEST)).andExpect(status().isOk());
  }

  private ResultActions send(String type, String target) throws Exception {
    return send("""
        {"type": "%s", "target": "%s", "payload": "hello"}""".formatted(type, target));
  }

  private ResultActions send(String body) throws Exception {
    return mockMvc.perform(post(SEND).contentType(MediaType.APPLICATION_JSON).content(body));
  }
}
