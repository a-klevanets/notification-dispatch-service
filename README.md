# Notification Dispatch Service

Sends `EMAIL`, `SMS` and `PUSH` notifications to a recipient, records the outcome of every attempt,
and refuses the ones the operational safeguards forbid: a blacklisted recipient, a recipient over
the rate limit, an unknown notification type, and dispatch while a run of consecutive delivery
failures has tripped the breaker.

## Current stack

- **Framework:** Spring Boot 4.1.2, wired by `@SpringBootApplication` and `application.yml`
- **Language and build:** Java 25 toolchain, Gradle 9.8.2 wrapper, executable jar with an embedded
  container (`bootJar`; the plain `jar` task is disabled)
- **Persistence:** `spring-boot-starter-data-jdbc` over in-memory H2, one table `notification_attempt`;
  `schema.sql` drops and recreates it on every startup. Spring Data owns the statements; the key of an
  attempt and its two timestamps stay the database's, the timestamps through column defaults
- **Tests:** twelve Jupiter integration tests in `LegacyNotificationEngineIntegrationTest`, run with
  `@SpringBootTest`

The framework underneath the service has been modernized; the dispatch logic in
`LegacyNotificationEngine` has not been redesigned yet.

## Behaviour that must be preserved

Whatever else changes, this is what the service has to keep doing. The named tests are the
contract:

| Behaviour | What the service does | Covered by |
|---|---|---|
| Channels | `EMAIL`, `SMS` and `PUSH` are supported, anything else is rejected | `emailSuccessIsStoredAsSent`; `smsAndPushAreDeliveredAndRecorded` |
| Blacklist | a target containing `blocked` is refused - a substring check, not a stored list | `blockedTargetFailsBeforeDispatchIsInserted`; `targetWithBlockedInsideItsNameIsAlsoRefused` |
| Rate limit | a target with three or more attempts in the last minute is refused | `rateLimitedTargetIsRefusedAfterThreeAttempts` |
| Input | a blank or missing target is refused before anything is written, and a missing payload is stored as empty text | `missingTargetIsRejected`; `missingPayloadIsStoredAsEmptyText` |
| Unknown type | stored with status `U`, then reported as an error; a missing type is one more unknown type | `unsupportedTypeIsStoredAsUnknown`; `missingTypeIsStoredAsUnknown` |
| Failures and breaker | a failed attempt is stored as `E` and counted; at three consecutive failures the breaker opens and attempts are stored as `R`, and nothing closes it again | `threeFailuresOpenCircuitBreakerForNextAttempt` |
| History and latest | the history is the twenty newest attempts, newest first, and an empty log has no latest state | `historyIsCappedAtTheTwentyNewestAttempts`; `latestStateIsNullBeforeAnythingIsSent` |

## Decisions and trade-offs

Each of these was a choice, not an omission, and each line names the alternative that lost.

- **The database stays embedded, so the state lives per process.** A production deployment would run a
  real database in a container and keep the log across restarts; here H2 in memory makes a run
  reproducible and the tests independent of any environment. The price: the log is gone on shutdown,
  and the rules computed from it go with it. A shared database would outlive the process and make
  replicas possible: the keys come from a sequence, whose only price is gaps after a rolled-back
  insert, and a second process would still need a lock around the breaker's check-then-write.
- **State is derived from the log, not kept beside it.** The rate limit and the breaker are computed
  from the same rows the audit writes, so one place knows the outcome of every attempt and there is
  nothing to keep in sync. In-process counters are cheaper now, and would have to be replaced the
  moment the log outlives the process. Derivation is not free: the `failureCount` every response
  carries is a window over the whole log, since the run a row belongs to can start arbitrarily far
  back, and neither `LIMIT 20` nor `LIMIT 1` bounds it. The decisions do not pay that - the breaker
  asks a scalar count that the `(status, id)` index answers without touching the table.
- **Keys and clocks come from the database.** The identity sequence numbers the attempts and
  `current_timestamp` dates them, so two processes writing to the same log would agree on order and
  time. The JVM clock and `max(id) + 1` were cheaper to write and lost: they disagree the moment a
  second process appears.
- **Safeguards written out rather than taken from a library.** Resilience4j and Bucket4j cover this
  ground, but their breaker recovers on a timer and their limiter refreshes a fixed period, while the
  behaviour here is a sliding minute and a breaker that never closes - a limitation kept, not a design.
  Adopting them would mean configuring them back to these semantics and paying a dependency for a
  query and a comparison; both rules are a method and a single comparison, so a library can take
  their place when the rules change.
- **JDBC rather than an ORM, and Spring Data JDBC rather than hand-written mapping.** An ORM introduces
  session state, dirty checking and deferred flushing without reducing code size: a write reaches the
  database later than the call, and a read can be answered from the session. This flow writes and then
  reads the same log within one request, so a delayed write means a stale read. Spring Data JDBC
  executes each statement immediately and removes only the mapping. SQL is kept where the model cannot
  state the intent: the failure-count window read, which has no column, and the state update,
  which writes only the columns that changed.
- **No idempotency.** A repeated request is a second notification: the assigned behaviour has no
  request key, and inventing one would change the contract rather than improve it.
- **No retries.** Without an idempotent key and a schedule, a retry is a duplicate message or a storm
  against a failing transport; the task asks for neither, and the failure is already recorded and
  counts toward the breaker.

## How changes are documented

Each step changes one thing and records in its commit message what was decided and what was
rejected. Notes about work that has not been done yet stay out of the repository on purpose: this
file describes the service as it is, not as it is planned. Local working notes and tool state are
not tracked at all.

## Provenance

The repository was seeded from the task template, and the task statement is kept here as
[ASSIGNMENT.md](ASSIGNMENT.md). Its list of behaviour to preserve is what the section above
restates in the project's own terms. Everything that concerns only the exercise - the time box, the
submission instructions, the bonus items - stays in that file and is not repeated here.
