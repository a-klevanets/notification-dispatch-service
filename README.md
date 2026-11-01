# Notification Dispatch Service

Sends `EMAIL`, `SMS` and `PUSH` notifications to a recipient, records the outcome of every attempt,
and refuses the ones the operational safeguards forbid: a blacklisted recipient, a recipient over
the rate limit, an unknown notification type, and dispatch while a run of consecutive delivery
failures has tripped the breaker.

## Current stack

- **Framework:** Spring Boot 4.1.2, wired by `@SpringBootApplication` and `application.yml`; the
  request is validated by Jakarta Bean Validation, from `spring-boot-starter-validation`
- **Requests:** `spring.threads.virtual.enabled: true`, so Tomcat gives each request a virtual thread
  instead of one of its own pool; the request spends most of its life blocked on JDBC, which is what
  the switch buys
- **Language and build:** Java 25 toolchain, Gradle 9.8.2 wrapper, executable jar with an embedded
  container (`bootJar`; the plain `jar` task is disabled)
- **Persistence:** `spring-boot-starter-data-jdbc` over in-memory H2, two tables - `notification_attempt`
  for the log and `blocked_recipient` for what the service refuses; `schema.sql` drops and recreates
  both on every startup. Spring Data owns the statements; the key of an attempt and its two timestamps
  stay the database's, the timestamps through column defaults
- **Tests:** thirty-two Jupiter tests over two suites, `NotificationApiIntegrationTest` for the
  dispatch rules and `BlocklistApiIntegrationTest` for the list's own contract, both driving the whole
  service through MockMvc under `@SpringBootTest` against a real H2 with nothing mocked

The framework and the boundaries around the dispatch logic are modernized; the flow itself is still
one method, in `NotificationDispatcher`. The three safeguards answer from their own classes - a table
for the blocklist, a Resilience4j window for the breaker, a Bucket4j bucket for the rate limit - and
the method keeps their order, the audit writes and the refusals. What changed is what that method
hands back - the stored attempt rather than the response the API prints - and who delivers: a
registry of senders keyed by notification type rather than a branch on the type string.

## Endpoints

The service listens on port 8080 and `./gradlew bootRun` starts it. The log is in memory, so every
start begins empty.

| Call | What comes back |
|---|---|
| `POST /api/v1/notifications` with `{"type", "recipient", "payload"}` | the stored attempt, `200` |
| `GET /api/v1/notifications?limit=N` | up to `N` of the newest attempts, newest first; `N` defaults to `20` and is refused outside `1..100` |
| `PATCH /api/v1/notifications/{id}` with `{"note"}` | the attempt with the note attached, `200`; `404` when no attempt has that id |
| `GET /api/v1/blocklist` | the recipients the service refuses, one address per entry |
| `POST /api/v1/blocklist` with `{"recipient"}` | adds an entry, `201`; adding the same address again changes nothing |
| `DELETE /api/v1/blocklist?recipient=...` | removes the entry, `204`, whether or not the list held it |

There is no separate endpoint for the newest attempt or for the state of the service: the newest row
is `?limit=1`, and what a run has accumulated shows on the next attempt, as a `503` and a `REJECTED`
row, rather than as a number to read. A limit outside the range is refused rather than clamped, so a
short answer always means the log ran out and never that the request was quietly cut down.

The blocklist is data, and an entry names one address exactly, matched once trimmed and without
folding case. Membership is the whole rule, so a recipient whose name merely contains `blocked` is
delivered to, and what is refused is what an operator put on the list.

An attempt is reported in the words the log stores: `status` is `NEW`, `SENT`, `ERROR`, `UNKNOWN` or
`REJECTED`, `recipient` names who the notification went to, and `detail` carries the last thing
said about the attempt - `START` while it runs, the delivery error, or `SENT`. Timestamps are
ISO-8601 in UTC. The response is a projection of the row: nothing is encoded for the wire, nothing
is computed at the boundary, and the column behind `recipient` keeps the log's own name, `target`.
`note` is what an update attached, and it is null until one does.

A refusal answers `application/problem+json` as defined by RFC 9457, with a code per reason - `400`
invalid input or unsupported type, `404` no such attempt, `403` blacklisted, `429` rate limited, `503`
breaker open, `502` channel failure. The legacy engine answered every one of these with `500`; giving
each refusal its own code is the one place where the observable behaviour changed. The `type` is a slug
under `errors.example.com`, a reserved example domain used as an identifier because the service has no
error documentation to link to. `NotificationApiIntegrationTest` asserts each of those seven. Errors
the framework raises before the dispatcher runs answer in the same body, each with its own slug: an
unreadable body or a limit that is not a number as `invalid-request`, an unsupported media type as
`unsupported-media-type` (`415`), a wrong method as `method-not-allowed` (`405`), and an unknown path
as `not-found` (`404`). `frameworkRefusalsAreReportedInTheSameContract` asserts those five.

## Behaviour that must be preserved

Whatever else changes, this is what the service has to keep doing. The named tests are the
contract:

| Behaviour | What the service does | Covered by |
|---|---|---|
| Channels | `EMAIL`, `SMS` and `PUSH` are supported, anything else is rejected | `emailSuccessIsStoredAsSent`; `allThreeChannelsAreDeliveredAndRecorded`; `unsupportedTypeIsStoredAsUnknown` |
| Blacklist | a recipient on the stored blocklist is refused - a list of addresses, not a text pattern | `blockedTargetFailsBeforeDispatchIsInserted`; `nameContainingBlockedIsDelivered`; `removingAnEntryLetsTheRecipientThrough` |
| Rate limit | a recipient is refused once three tokens are gone from its minute bucket; after a burst of three the refusal clears in about twenty seconds, not when the oldest attempt ages out | `rateLimitedTargetIsRefusedAfterThreeAttempts` |
| Input | a blank or missing recipient, type or payload is refused before anything is written, as is a type or recipient longer than its column | `missingTargetIsRejected`; `missingTypeIsRejected`; `missingPayloadIsRejected`; `overlongTypeAndTargetAreRejected` |
| Unknown type | stored with status `UNKNOWN`, then reported as an error | `unsupportedTypeIsStoredAsUnknown` |
| Failures and breaker | a failed attempt is stored as `ERROR` and counted, whatever its channel; the count is the three most recent calls this process made, so a success inside that window keeps it closed, and at three the breaker opens and attempts are stored as `REJECTED`; a probe five seconds later closes it if it delivers and reopens it if not | `channelFailureIsStoredAsAnError`; `threeFailuresOpenCircuitBreakerForNextAttempt`; `aSuccessBetweenFailuresKeepsTheBreakerClosed`; `anOpenBreakerClosesAfterASuccessfulProbe`; `aFailedProbeOpensTheBreakerAgain` |
| History | the history is the twenty newest attempts, newest first, and a caller may ask for fewer; an empty log answers with an empty list | `historyIsCappedAtTheTwentyNewestAttempts`; `historyHonoursTheLimitTheCallerAsksFor`; `limitOutsideTheAllowedRangeIsRefused` |
| Update | an update attaches a note to an attempt; what the attempt recorded - type, recipient, payload, status, detail - stays as it was written | `noteIsAttachedWithoutRewritingTheAttempt`; `updateLeavesTheRecordAsItWasWritten`; `updateOnUnknownAttemptIsRefused`; `blankNoteIsRefused` |

## Decisions and trade-offs

Each of these was a choice, not an omission, and each line names the alternative that lost.

- **The database is embedded for convenience.** H2 in memory keeps a run reproducible and the tests
  free of any environment; the price is that the log and the history a caller reads die on shutdown.
  Neither the breaker nor the limiter reads the table, so a durable database is a configuration change:
  the datasource url and the `DROP TABLE` that opens `schema.sql` are what stands in the way.
- **State is kept beside the log, not derived from it.** The breaker and the limiter hold their own
  totals in the process, so the rows say what happened and decide nothing that happens next. The
  price: a restart clears both rules and a second instance counts its own attempts, both of which
  deriving the state from the rows gave for free. The gain: neither rule queries the table any more,
  and the two counts and the two indexes that answered them went with the queries. What a run has
  accumulated is still not reported; it is a guard's own state now, not a property of a row.
- **Keys and clocks come from the database.** The identity sequence numbers the attempts and
  `current_timestamp` dates them, so two processes writing to the same log would agree on order and
  time. The JVM clock and `max(id) + 1` were cheaper to write and lost: they disagree the moment a
  second process appears.
- **Safeguards taken from libraries.** Resilience4j covers the breaker, Bucket4j the rate limit, each
  configured back to the rule the hand-written classes had, as far as their own model reaches. The
  breaker's window lands on it exactly - three calls at a 100% failure threshold - its recovery does
  not: five seconds, then one call probes the transport. The limiter does not either: a greedily
  refilled bucket returns its three tokens across the minute, so a refused recipient retries in about
  twenty seconds where the sliding minute held it until the oldest attempt aged out. Neither library is
  in the Boot BOM, so both versions are pinned by hand - the cost of admitting a dependency at all. The
  lock that used to serialize admission per recipient went away by itself: taking a token and
  answering is one compare-and-set, so there was no read-then-write left to protect, and fifty
  concurrent attempts for one recipient admitted three and stored three rows.
- **JDBC rather than an ORM, and Spring Data JDBC rather than hand-written mapping.** An ORM introduces
  session state, dirty checking and deferred flushing without reducing code size: a write reaches the
  database later than the call, and a read can be answered from the session. This flow writes and then
  reads the same log within one request, so a delayed write means a stale read. Spring Data JDBC
  executes each statement immediately and removes only the mapping. SQL is kept where the model cannot
  state the intent: the two partial updates, which write only the columns that changed, and the row
  count that says whether the attempt a note is meant for exists at all.
- **The response reports the log's own words, not the legacy encoding.** The old endpoint printed a
  letter per status, `Y` or `N` for the blacklist flag, and the id a second time as `dispatchCount`.
  None of the three is behaviour, and the task permits redesigning the API: the letters were a
  translation the log never stored, and the flag could only ever read `N`, because a blacklisted
  recipient is refused before a row is written. Keeping them was the alternative, and it would have
  spent that permission on fidelity to a contract no caller in this repository consumes.
- **The request declares its own rules.** `@NotBlank` and `@Size` sit on `NotificationRequest`, next
  to the fields they bound, and one handler turns them into the same problem body a dispatch refusal
  answers with. Explicit checks in the dispatcher were the alternative: they keep the order of the
  refusals deterministic, which annotations give up - of two violations in one body, the one
  reported is whichever Spring lists first.
- **The advice answers framework errors itself.** An unreadable body, a wrong method, an unknown path:
  Spring Boot renders these from its own advice, and leaving it to do so costs nothing - except a body
  with no `type`, so the same endpoint answered in two shapes. A handler per case the routes can reach
  puts a slug on each. Naming the difference in this file, as it was before, was the alternative that
  lost: a documented divergence is still a divergence a caller has to handle.
- **The blocklist is a table, not a string check.** The behaviour to preserve names a blacklist, and
  the engine imitated one by refusing any target whose text contained `blocked`: that refuses
  `unblocked@example.com` and lets a caller decide its own fate by what it calls itself. The list is
  now a table the API manages, so the refusal matches what an operator put there. A configuration
  file and pattern matching were the alternatives: the first cannot change without a restart, the
  second is the substring rule again with more syntax.
- **An update attaches a note; it does not rewrite the attempt.** The bonus item asks for an update
  endpoint, and a row is an audit record: an edited payload would claim a different message was sent,
  and an edited status would claim an outcome the transport never returned. The breaker no longer
  reads these rows, but the history a caller reads does. The one field an update may write is the
  note, which never took part in a delivery. Editing the payload and resending as a new attempt were
  the alternatives: the first makes the log lie, the second is a different operation and answers a
  different question.
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
