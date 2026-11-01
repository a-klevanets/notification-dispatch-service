# Notification Dispatch Service

Sends `EMAIL`, `SMS` and `PUSH` notifications to a recipient and records the outcome of every
attempt, refusing what the safeguards forbid: a recipient on the blocklist, a recipient over its
minute of rate limit, an unknown notification type, and any dispatch while the breaker is open.

## Current stack

- **Framework:** Spring Boot 4.1.2, wired by `@SpringBootApplication` and `application.yml`; the
  request is validated by Jakarta Bean Validation, from `spring-boot-starter-validation`
- **Requests:** `spring.threads.virtual.enabled: true`, so Tomcat gives each request a virtual thread
  instead of one of its own pool; the request spends most of its life blocked on JDBC, which is what
  the switch buys
- **Language and build:** Java 25 toolchain, Gradle 9.8.2 wrapper, executable jar with an embedded
  container (`bootJar`; the plain `jar` task is disabled)
- **Persistence:** `spring-boot-starter-data-jdbc` over in-memory H2, two tables - `notification_attempt`
  for the log and `blocked_recipient` for what the service refuses; `schema.sql` drops and recreates both
  on every startup. Spring Data owns the statements; the key of an attempt and its two timestamps stay
  the database's
- **Tests:** thirty-two Jupiter tests over two suites, `NotificationApiIntegrationTest` for the
  dispatch rules and `BlocklistApiIntegrationTest` for the list's own contract, both driving the whole
  service through MockMvc under `@SpringBootTest` against a real H2 with nothing mocked

The dispatch flow is still one method, in `NotificationDispatcher`: it keeps the order of the guards,
the audit writes and the refusals, and the guards answer from their own classes - a table, a
Resilience4j window, a Bucket4j bucket. What changed is who delivers, a registry keyed by
notification type instead of a branch on the type string, and what the method hands back, the stored
attempt rather than a response the API prints.

## Endpoints

The service listens on port 8080 and `./gradlew bootRun` starts it. The log is in memory, so every
start begins empty.

| Call | What comes back |
|---|---|
| `POST /api/v1/notifications` with `{"type", "recipient", "payload"}` | the stored attempt, `200` |
| `GET /api/v1/notifications?limit=N` | up to `N` of the newest attempts, newest first; `N` defaults to `20` and is refused outside `1..100` |
| `PATCH /api/v1/notifications/{id}` with `{"note"}` | the attempt with the note attached, `200`; `404` when no attempt has that id |
| `GET /api/v1/blocklist` | the recipients the service refuses, one address per entry, matched as a whole address and not as a pattern |
| `POST /api/v1/blocklist` with `{"recipient"}` | adds an entry, `201`; adding the same address again changes nothing |
| `DELETE /api/v1/blocklist?recipient=...` | removes the entry, `204`, whether or not the list held it |

There is no endpoint for the newest attempt or for the state of a run. What the guards have
accumulated answers on the next request instead: a rate-limited recipient is refused before a row is
written, an open breaker is refused after its row is stored as `REJECTED`. A short history means the
log ran out and never that the request was quietly cut down.

An attempt is reported in the words the log stores: `status` is `NEW`, `SENT`, `ERROR`, `UNKNOWN` or
`REJECTED`, and `detail` carries the last thing said about it - `START` while it runs, the delivery
error, or `SENT`. `note` is null until an update attaches one.

A refusal answers `application/problem+json` as defined by RFC 9457, with a code per reason - `400`
invalid input or unsupported type, `404` no such attempt, `403` blacklisted, `429` rate limited, `503`
breaker open, `502` channel failure - and a `type` that is a slug under `errors.example.com`, a
reserved domain used as an identifier because there is no error documentation to link to. The legacy
engine answered every one of these with `500`. Errors the framework raises before the dispatcher runs
answer in the same body, each with its own slug: an unreadable body or a limit that is not a number as
`invalid-request`, an unsupported media type as `unsupported-media-type` (`415`), a wrong method as
`method-not-allowed` (`405`), and an unknown path as `not-found` (`404`).

## How changes are documented

Each step changes one thing and records in its commit message what was decided and what was
rejected. Notes about work that has not been done yet stay out of the repository on purpose: this
file describes the service as it is, not as it is planned. Local working notes and tool state are
not tracked at all.

Two points of the history are worth checking out, each linked to the code as it stood in that commit:

- [docs(readme): correct the test count the length guard left stale](https://github.com/a-klevanets/notification-dispatch-service/tree/d6ad75139a4d0b0ee02f5884371d5eab5a68fea1) - the
  last commit where the original response shape was still the contract
- [build(config): enable virtual threads](https://github.com/a-klevanets/notification-dispatch-service/tree/88070a4ccd2b4fbf4f838ffd385551778e0713d8) - the last commit before the
  safeguards were taken from libraries

## Behaviour that must be preserved

Each row is a line of the task's `Behavior to preserve` list, restated in this service's terms. The
named tests are where each line holds:

| Behaviour | What the service does | Covered by |
|---|---|---|
| Channels | `EMAIL`, `SMS` and `PUSH` are delivered and stored as `SENT` | `emailSuccessIsStoredAsSent`; `allThreeChannelsAreDeliveredAndRecorded` |
| Blocked recipient | a recipient on the blocklist is refused, and no attempt is stored | `blockedTargetFailsBeforeDispatchIsInserted`; `removingAnEntryLetsTheRecipientThrough` |
| Rate-limited recipient | a recipient is refused once its three tokens for the minute are gone, and no attempt is stored | `rateLimitedTargetIsRefusedAfterThreeAttempts` |
| Unsupported type | a type outside the three is refused with `400` and stored as `UNKNOWN` | `unsupportedTypeIsStoredAsUnknown` |
| Failures tracked | a delivery failure is stored as `ERROR` with what the transport said | `channelFailureIsStoredAsAnError` |
| Rejection path | three failed calls open the breaker, the next attempt is stored as `REJECTED`, and a probe five seconds later closes it if it delivers | `threeFailuresOpenCircuitBreakerForNextAttempt`; `aSuccessBetweenFailuresKeepsTheBreakerClosed`; `anOpenBreakerClosesAfterASuccessfulProbe`; `aFailedProbeOpensTheBreakerAgain` |

## Decisions and trade-offs

Each of these was a choice, not an omission, and each line says what it bought and what it cost.

- **One instance writes at a time.** Two live instances would have to share state and agree on who owns
  it - a coordination layer. The task does not ask for one, and two of its own demands argue against
  it: no unnecessary complexity, and a solution proportional to the problem. Assumed instead is a
  failover: the next instance takes over the same log, and no two of them write it together.
- **The database is embedded for convenience.** H2 in memory keeps a run reproducible and the tests
  free of any environment; the price is that the log and the history a caller reads die on shutdown.
  Neither the breaker nor the limiter reads the table, so a durable database is a configuration change:
  the datasource url and the `DROP TABLE` that opens `schema.sql` are what stands in the way.
- **Keys and clocks come from the database.** The engine asked the table for the highest id and
  added one in Java, so two requests in one process could be handed the same number, and it dated
  rows by its own clock, which a failover would disagree with. The identity column now numbers an
  attempt as its insert runs and `current_timestamp` dates it.
- **Safeguards taken from libraries.** The old breaker counted failures since the last success
  and never closed again, and the old limiter counted rows in the minute behind a lock that existed
  only to make that count safe. A Resilience4j window replaced the counter and a Bucket4j bucket
  the rows, and the thresholds are unchanged, three failures to open the path and three a minute
  to hold a recipient. What differs comes from the mechanism: a window can half-open and let one
  call test the transport, so the breaker closes again, and a bucket refills smoothly, so a refusal
  lifts before the burst has aged out of its minute. The task named the behaviour to keep and not
  the counting behind it, and the table above is what the service is held to. Neither library is in
  the Boot BOM, so both versions are pinned by hand.
- **State is kept beside the log, not derived from it.** The table is a log now and nothing else: it
  answers reads, and the two guards hold their own totals in the process instead of asking it. The
  price: a restart starts both rules empty, which deriving the state from the rows covered for free.
  The gain: the two counts and the two indexes that answered those questions are gone with the
  queries.
- **JDBC rather than an ORM, and Spring Data JDBC rather than hand-written mapping.** An ORM brings
  machinery of its own, such as a session and a flush at the moment it picks, which buys nothing in a
  flow of two writes and a read. Spring Data JDBC names the columns itself, and a few annotations on
  the record are the whole price. SQL stays where those annotations cannot state the intent, in the
  two partial updates.
- **The response reports the log's own words, not the legacy encoding.** The old body carried a
  `dispatchCount` that could only ever equal the id, and a blacklist flag that could only ever read
  `N`, because a target naming itself blocked was refused before a row was written. No code here
  reads them.
- **The request declares its own rules.** `@NotBlank` and `@Size` sit on `NotificationRequest`, next
  to the fields they bound. One handler turns a violation into the same problem body a dispatch
  refusal answers with. The order of the refusals is what annotations give up. The advice prints the
  first field error of the list Spring assembled, and nothing in the request says which one that is.
- **The advice answers framework errors itself.** A caller reads one shape whichever layer refused.
  Boot registers advice for the same errors, so this class is ordered ahead of it.
  `frameworkRefusalsAreReportedInTheSameContract` asserts that the errors the framework raises before
  the dispatcher runs answer in the body a dispatch refusal gets.
- **An update attaches a note, not a rewrite of the attempt.** A row is an audit record: an edited
  payload would claim a different message was sent, and an edited status would claim an outcome the
  transport never returned. The one field an update may write is the note, which never took part in a
  delivery.
- **No idempotency.** Two identical requests are two attempts. A request key would decide between
  them, and what it decides turns on what a caller does when it hears nothing back, which nothing in
  this service sees. The assigned behaviour names no such key, and the key is not one field but a
  unique index and an answer for a caller who repeats.
- **No retries.** A failed delivery is stored as `ERROR` and counted by the breaker. The breaker opens
  once its three most recent calls have all failed, and the next attempt is refused before it sends,
  as a `REJECTED` row. The assigned behaviour answers repeated failures with rejection, and a retry
  answers them with a send. A caller who wants that send can already ask for it twice.

## Provenance

The repository was seeded from the task template, and the task statement is kept here as
[ASSIGNMENT.md](ASSIGNMENT.md); its list of behaviour to preserve is what the
`Behaviour that must be preserved` section restates in the project's own terms, and everything that
concerns only the exercise - the time box, the submission instructions, the bonus items - stays there.
