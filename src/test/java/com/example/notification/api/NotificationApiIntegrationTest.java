package com.example.notification.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * The contract, asserted where a caller meets it: the rules the service enforces, the status and
 * the RFC 9457 body each refusal answers with, the shape a stored attempt is reported in, and the
 * read endpoint. Nothing is mocked - a real context over a real H2. MockMvc is wired from the
 * context here rather than by {@code @AutoConfigureMockMvc}, which Spring Boot 4 moved into a
 * {@code spring-boot-webmvc-test} module this build does not carry.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public class NotificationApiIntegrationTest {

  private static final String NOTIFICATIONS = "/api/v1/notifications";
  private static final String BLOCKLIST = "/api/v1/blocklist";

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
        .andExpect(jsonPath("$.recipient").value("buyer@example.com"))
        .andExpect(jsonPath("$.payload").value("hello"))
        .andExpect(jsonPath("$.status").value("SENT"))
        .andExpect(jsonPath("$.detail").value("SENT"))
        .andExpect(jsonPath("$.createdAt").exists())
        .andExpect(jsonPath("$.updatedAt").exists())
        .andExpect(jsonPath("$.dispatchCount").doesNotExist())
        .andExpect(jsonPath("$.blocked").doesNotExist())
        .andExpect(jsonPath("$.failureCount").doesNotExist());

    // Read back - the write path proves the mapper, not that the read endpoint uses the same query.
    history(1)
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$[0].recipient").value("buyer@example.com"))
        .andExpect(jsonPath("$[0].payload").value("hello"))
        .andExpect(jsonPath("$[0].createdAt").exists())
        .andExpect(jsonPath("$[0].updatedAt").exists());

    history().andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  public void blockedTargetFailsBeforeDispatchIsInserted() throws Exception {
    block("blocked-user@example.com");

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

    history(1)
        .andExpect(jsonPath("$[0].type").value("FAX"))
        .andExpect(jsonPath("$[0].status").value("UNKNOWN"))
        .andExpect(jsonPath("$[0].detail").value("Unsupported type: FAX"));
  }

  @Test
  public void threeFailuresOpenCircuitBreakerForNextAttempt() throws Exception {
    send("EMAIL", "error-one@example.com").andExpect(status().isBadGateway());
    send("EMAIL", "error-two@example.com").andExpect(status().isBadGateway());
    send("EMAIL", "error-three@example.com").andExpect(status().isBadGateway());

    history(1)
        .andExpect(jsonPath("$[0].status").value("ERROR"))
        .andExpect(jsonPath("$[0].detail").value("SMTP Timeout"));

    expectProblem(
        send("SMS", "ok-user@example.com"),
        503,
        "circuit-breaker-open",
        "Dispatch unavailable",
        "Circuit breaker OPEN via database state.");

    history(1)
        .andExpect(jsonPath("$[0].id").value(4))
        .andExpect(jsonPath("$[0].status").value("REJECTED"));
  }

  /** The breaker counts a run, not the total: a success between the failures keeps it closed. */
  @Test
  public void aSuccessBetweenFailuresKeepsTheBreakerClosed() throws Exception {
    send("EMAIL", "error-one@example.com").andExpect(status().isBadGateway());
    send("EMAIL", "error-two@example.com").andExpect(status().isBadGateway());
    send("EMAIL", "buyer@example.com").andExpect(status().isOk());

    send("EMAIL", "error-three@example.com").andExpect(status().isBadGateway());
    send("EMAIL", "error-four@example.com").andExpect(status().isBadGateway());

    send("SMS", "+70000000000").andExpect(status().isOk());
  }

  @Test
  public void allThreeChannelsAreDeliveredAndRecorded() throws Exception {
    send("EMAIL", "buyer@example.com")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SENT"));
    send("SMS", "+70000000000")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("SMS"))
        .andExpect(jsonPath("$.status").value("SENT"));
    send("PUSH", "device-42")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("PUSH"))
        .andExpect(jsonPath("$.status").value("SENT"));

    history().andExpect(jsonPath("$.length()").value(3));
  }

  /** The list holds addresses, so a name that merely contains the word is delivered. */
  @Test
  public void nameContainingBlockedIsDelivered() throws Exception {
    send("EMAIL", "unblocked@example.com").andExpect(status().isOk());
  }

  /** The guard reads the list on every dispatch, so an entry taken off it stops refusing. */
  @Test
  public void removingAnEntryLetsTheRecipientThrough() throws Exception {
    block("buyer@example.com");
    unblock("buyer@example.com");

    send("EMAIL", "buyer@example.com").andExpect(status().isOk());
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
        send("EMAIL", "  "), 400, "invalid-request", "Invalid request", "Recipient is mandatory.");
    expectProblem(
        send("""
            {"type": "EMAIL", "payload": "hello"}"""),
        400,
        "invalid-request",
        "Invalid request",
        "Recipient is mandatory.");

    expectNothingIsStored();
  }

  /** One over each column's width; the insert would fail, so the dispatcher refuses first. */
  @Test
  public void overlongTypeAndTargetAreRejected() throws Exception {
    expectProblem(
        send("T".repeat(33), "ops@example.com"),
        400,
        "invalid-request",
        "Invalid request",
        "Type exceeds 32 characters.");
    expectProblem(
        send("EMAIL", "t".repeat(256)),
        400,
        "invalid-request",
        "Invalid request",
        "Recipient exceeds 255 characters.");

    expectNothingIsStored();
  }

  @Test
  public void missingPayloadIsRejected() throws Exception {
    expectProblem(
        send("""
            {"type": "PUSH", "recipient": "device-42"}"""),
        400,
        "invalid-request",
        "Invalid request",
        "Payload is mandatory.");

    expectNothingIsStored();
  }

  @Test
  public void missingTypeIsRejected() throws Exception {
    expectProblem(
        send("""
            {"recipient": "ops@example.com", "payload": "hello"}"""),
        400,
        "invalid-request",
        "Invalid request",
        "Type is mandatory.");

    expectNothingIsStored();
  }

  @Test
  public void channelFailureIsStoredAsAnError() throws Exception {
    expectProblem(
        send("EMAIL", "error-user@example.com"),
        502,
        "delivery-failed",
        "Delivery failed",
        "SMTP Timeout");

    history(1)
        .andExpect(jsonPath("$[0].status").value("ERROR"))
        .andExpect(jsonPath("$[0].detail").value("SMTP Timeout"));
  }

  @Test
  public void historyIsCappedAtTheTwentyNewestAttempts() throws Exception {
    for (int i = 0; i < 21; i++) {
      send("SMS", "+700000000" + i).andExpect(status().isOk());
    }

    history()
        .andExpect(jsonPath("$.length()").value(20))
        .andExpect(jsonPath("$[0].recipient").value("+70000000020"))
        .andExpect(jsonPath("$[19].recipient").value("+7000000001"));
  }

  @Test
  public void historyHonoursTheLimitTheCallerAsksFor() throws Exception {
    for (int i = 0; i < 3; i++) {
      send("SMS", "+700000000" + i).andExpect(status().isOk());
    }

    history(2)
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].recipient").value("+7000000002"))
        .andExpect(jsonPath("$[1].recipient").value("+7000000001"));
  }

  @Test
  public void limitOutsideTheAllowedRangeIsRefused() throws Exception {
    expectProblem(
        mockMvc.perform(get(NOTIFICATIONS).param("limit", "0")),
        400,
        "invalid-request",
        "Invalid request",
        "limit must be between 1 and 100.");
    expectProblem(
        mockMvc.perform(get(NOTIFICATIONS).param("limit", "101")),
        400,
        "invalid-request",
        "Invalid request",
        "limit must be between 1 and 100.");
  }

  /** A request the framework rejects before the controller runs answers in the API's own shape. */
  @Test
  public void frameworkRefusalsAreReportedInTheSameContract() throws Exception {
    expectProblem(
        send("not json"), 400, "invalid-request", "Invalid request",
        "The request body could not be read.");
    expectProblem(
        mockMvc.perform(post(NOTIFICATIONS).contentType(MediaType.TEXT_PLAIN).content("hello")),
        415,
        "unsupported-media-type",
        "Unsupported media type",
        "Only application/json is accepted.");
    expectProblem(
        mockMvc.perform(delete(NOTIFICATIONS)),
        405,
        "method-not-allowed",
        "Method not allowed",
        "DELETE is not supported.");
    expectProblem(
        mockMvc.perform(get(NOTIFICATIONS + "/missing")),
        404,
        "not-found",
        "Not found",
        "No resource at /api/v1/notifications/missing.");
    expectProblem(
        mockMvc.perform(get(NOTIFICATIONS).param("limit", "abc")),
        400,
        "invalid-request",
        "Invalid request",
        "limit must be a number.");
  }

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

  private void block(String recipient) throws Exception {
    mockMvc
        .perform(
            post(BLOCKLIST)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipient\": \"%s\"}".formatted(recipient)))
        .andExpect(status().isCreated());
  }

  private void unblock(String recipient) throws Exception {
    mockMvc
        .perform(delete(BLOCKLIST).param("recipient", recipient))
        .andExpect(status().isNoContent());
  }

  private void expectNothingIsStored() throws Exception {
    history().andExpect(jsonPath("$.length()").value(0));
  }

  private ResultActions history() throws Exception {
    return mockMvc
        .perform(get(NOTIFICATIONS))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON));
  }

  private ResultActions history(int limit) throws Exception {
    return mockMvc
        .perform(get(NOTIFICATIONS).param("limit", String.valueOf(limit)))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON));
  }

  private ResultActions send(String type, String recipient) throws Exception {
    return send("""
        {"type": "%s", "recipient": "%s", "payload": "hello"}""".formatted(type, recipient));
  }

  private ResultActions send(String body) throws Exception {
    return mockMvc
        .perform(post(NOTIFICATIONS).contentType(MediaType.APPLICATION_JSON).content(body));
  }
}
