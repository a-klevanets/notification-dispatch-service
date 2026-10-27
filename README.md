# Notification Dispatch Service

Sends `EMAIL`, `SMS` and `PUSH` notifications to a recipient, records the outcome of every attempt,
and refuses the ones the operational safeguards forbid: a blacklisted recipient, a recipient over
the rate limit, an unknown notification type, and dispatch while a run of consecutive delivery
failures has tripped the breaker.

## Current stack

- **Framework:** Spring Boot 4.1.2, wired by `@SpringBootApplication` and `application.yml`
- **Language and build:** Java 25 toolchain, Gradle 9.8.2 wrapper, executable jar with an embedded
  container (`bootJar`; the plain `jar` task is disabled)
- **Persistence:** `spring-boot-starter-jdbc` over in-memory H2, one table `ntf_tx`; `schema.sql`
  drops and recreates it on every startup
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
  replicas possible, at the cost of a sequence and a lock instead of `max(id) + 1`.
- **State is read from the log, not from the process.** The rate limit counts the attempt rows
  of the last minute; the breaker reads the failure count stored on the newest row. Both are
  answers about the log, so a restart has nothing to restore, and the alternative that lost is a
  counter held in the running process: cheaper today, correct only while one process reads the log.
- **Safeguards written out rather than taken from a library.** Resilience4j and Bucket4j cover this
  ground, but their breaker recovers on a timer and their limiter refreshes a fixed period, while the
  behaviour here is a sliding minute and a breaker that never closes - a limitation kept, not a design.
  Adopting them would mean configuring them back to these semantics and paying a dependency for a
  query and a comparison; both rules are a method and a single comparison, so a library can take
  their place when the rules change.
- **JDBC rather than an ORM.** One table, plain reads, and writes that must be visible to the next
  call: an ORM would add mapping and flush semantics without removing code, and it would change when
  a running instance sees its own writes.
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
