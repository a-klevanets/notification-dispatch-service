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
| Failures and breaker | a failed attempt is stored as `E` and counted; at three consecutive failures the breaker opens and attempts are stored as `R` | `threeFailuresOpenCircuitBreakerForNextAttempt` |
| History and latest | the history is the twenty newest attempts, newest first, and an empty log has no latest state | `historyIsCappedAtTheTwentyNewestAttempts`; `latestStateIsNullBeforeAnythingIsSent` |

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
