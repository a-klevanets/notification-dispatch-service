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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The blocklist's own contract: what the list holds, what order it answers in, what happens to an
 * entry added twice or removed twice, and the two refusals its body can earn. Whether an entry
 * stops a dispatch is the notification suite's business, so nothing here sends a notification.
 * MockMvc is wired from the context for the reason given in {@code NotificationApiIntegrationTest}.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(
    properties = "spring.datasource.url=jdbc:h2:mem:test;MODE=PostgreSQL;DB_CLOSE_ON_EXIT=FALSE")
public class BlocklistApiIntegrationTest {

  private static final String BLOCKLIST = "/api/v1/blocklist";

  @Autowired
  private WebApplicationContext context;

  private MockMvc mockMvc;

  @BeforeEach
  public void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
  }

  @Test
  public void entryIsListedOnceItIsAdded() throws Exception {
    block("buyer@example.com");

    list()
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0]").value("buyer@example.com"));
  }

  @Test
  public void sameAddressAddedTwiceIsListedOnce() throws Exception {
    block("buyer@example.com");
    block("buyer@example.com");

    list().andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  public void listAnswersInAddressOrder() throws Exception {
    block("c@example.com");
    block("a@example.com");
    block("b@example.com");

    list()
        .andExpect(jsonPath("$[0]").value("a@example.com"))
        .andExpect(jsonPath("$[1]").value("b@example.com"))
        .andExpect(jsonPath("$[2]").value("c@example.com"));
  }

  @Test
  public void entryIsTrimmedBeforeItIsStored() throws Exception {
    block("  buyer@example.com  ");

    list().andExpect(jsonPath("$[0]").value("buyer@example.com"));
  }

  @Test
  public void blankEntryIsRefused() throws Exception {
    expectProblem(
        mockMvc.perform(post(BLOCKLIST).contentType(MediaType.APPLICATION_JSON).content("{}")),
        400,
        "invalid-request",
        "Invalid request",
        "Recipient is mandatory.");

    list().andExpect(jsonPath("$.length()").value(0));
  }

  /** One over the column's width; the insert would fail, so the request is refused first. */
  @Test
  public void overlongEntryIsRefused() throws Exception {
    expectProblem(
        blockRaw("r".repeat(256)),
        400,
        "invalid-request",
        "Invalid request",
        "Recipient exceeds 255 characters.");

    list().andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  public void removingAnEntryTakesItOffTheList() throws Exception {
    block("buyer@example.com");

    mockMvc
        .perform(delete(BLOCKLIST).param("recipient", "buyer@example.com"))
        .andExpect(status().isNoContent());

    list().andExpect(jsonPath("$.length()").value(0));
  }

  /** Removing is idempotent: the second caller asking for the same absence is not at fault. */
  @Test
  public void removingAnAbsentEntryIsNotAnError() throws Exception {
    mockMvc
        .perform(delete(BLOCKLIST).param("recipient", "ghost@example.com"))
        .andExpect(status().isNoContent());

    list().andExpect(jsonPath("$.length()").value(0));
  }

  private ResultActions list() throws Exception {
    return mockMvc
        .perform(get(BLOCKLIST))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON));
  }

  private void block(String recipient) throws Exception {
    blockRaw(recipient).andExpect(status().isCreated());
  }

  private ResultActions blockRaw(String recipient) throws Exception {
    return mockMvc.perform(
        post(BLOCKLIST)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipient\": \"%s\"}".formatted(recipient)));
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
}
