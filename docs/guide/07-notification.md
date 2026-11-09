# 7. Notification service

## Purpose
Notification owns the **emails** of the system: the ticket email, the cancellation email and the refund email. Nobody outside the backend calls it.
Booking & Seat asks it for an email from its outbox (see chapter 4). The request is **stored and answered at once** (HTTP 202). A background poller
sends the email by SMTP afterwards, with retries. So a mail server that is down, or an address that is refused, can never fail the payment, the
confirmation or the cancellation that asked for the email (rule: an email failure never cancels a booking).

- Port **8085**. Oracle schema (database user) `notification`. Spring Boot, Spring MVC, Spring JDBC (`JdbcClient`), Flyway, Spring Mail.
- Called by **Booking & Seat** only, with the shared key in `X-Internal-Api-Key`. The Gateway never routes to it.
- Calls an **SMTP server**: Mailpit in development (SMTP 1025, web UI 8025).
- It never reads another service's tables. The words of the emails are written here (`EmailTemplates`); callers only say what happened.

## Folder structure
```
services/notification/
  build.gradle, README.md, .env.example
  src/main/resources/application.properties
  src/main/resources/db/migration/V1__create_notification_tables.sql
  src/main/java/com/trainbooking/notification/
    NotificationApplication.java
    audit/         audit_log writing and the audit log line
    common/        error shape, exception handler, config beans, request log filter
    mail/          EmailSender interface and the SMTP implementation
    notification/  controller, service, repository, model of the email feature
    security/      API-key filter and the two filter chains
  src/test/java/...  tests (same packages) and testsupport/
```

## Every file, one line each
| File | What it does |
|---|---|
| `NotificationApplication` | Starts Spring Boot. |
| `audit/AuditLogRepository` | INSERT into `audit_log`. |
| `audit/AuditTrail` | Writes the audit row and, after commit, a structured log line (logger `audit`) for OpenObserve. |
| `common/ApiError` | The shared JSON error shape `{ error: { code, message, details? } }`. |
| `common/GlobalExceptionHandler` | Turns validation, malformed JSON, unknown route, wrong method and unexpected errors into `ApiError`. |
| `common/NotificationConfig` | Beans: UTC clock, `EmailTemplates` with the configured time zone; enables scheduling. |
| `common/RequestLogContext` | One `access` log line per request (path only) and MDC fields. |
| `common/RequestValidationException` | A 400-style rule error with details (shared across services; not thrown by this service today). |
| `mail/EmailSender` | Interface: send one `Email`, throw if it cannot be handed to the mail server. |
| `mail/SmtpEmailSender` | Spring Mail implementation: text + HTML + optional PDF attachment. |
| `notification/controller/NotificationController` | `POST /internal/notifications`. |
| `notification/model/NotificationRequest` | The request body, with Bean Validation, plus nested `Trip` and `Attachment` and the `Kind` enum. |
| `notification/model/Email` | One stored email (record). |
| `notification/model/EmailStatus` | `PENDING`, `SENT`, `FAILED`. |
| `notification/repository/EmailRepository` | All SQL of table `email`. |
| `notification/service/NotificationService` | Accepts a request: dedupe, render, store, audit. |
| `notification/service/EmailTemplates` | Builds subject, plain text and HTML (pure class, no I/O). |
| `notification/service/EmailDispatcher` | One pass of the sender: send due emails, mark sent, back off, give up. |
| `notification/service/EmailScheduler` | Calls the dispatcher every second (can be switched off). |
| `security/SecurityConfig` | Two filter chains: `/internal/**` needs the key, everything else is denied. |
| `security/InternalApiKeyFilter` | Constant-time check of `X-Internal-Api-Key`. |
| `security/JsonErrorHandlers` | 401 / 403 written in the JSON error shape. |

### Tests
| File | What it does |
|---|---|
| `testsupport/DatabaseTest` | Base class: whole app on schema `notification_test`, emptied before every test. |
| `testsupport/DatabaseTestConfig`, `MutableClock`, `FakeEmailSender` | Replacement beans: movable clock and a fake mail server that can be down or refuse addresses. |
| `testsupport/RequiresTestDatabase`, `TestDatabase` | Skip the database tests when `NOTIFICATION_TEST_DB_PASSWORD` is missing; read the connection settings. |
| `notification/EmailTemplatesTest` | 8 pure tests of the wording per kind and payment method, time zone, HTML escaping. |
| `notification/NotificationDatabaseTest` | 12 tests: store pending, dedupe (also two at once), send once, server down, backoff cap, refused address, giving up, key and validation of the endpoint. |
| `mail/SmtpEmailSenderMailpitTest` | 1 test, real SMTP into Mailpit; skipped when Mailpit is not running. |

## Endpoint
| Method | Path | Auth | Request | Response | Errors |
|---|---|---|---|---|---|
| POST | `/internal/notifications` | `X-Internal-Api-Key` | see below | `202 { id, status: "PENDING" }` for a new email; `200` with the same shape when the `dedupeKey` was accepted before | `400 validation_failed` (with `details` per field), `400 malformed_request`, `401 invalid_api_key` |

Request body (`NotificationRequest`):
- `dedupeKey` (required, at most 100 characters): chosen by the caller, for example `ticket:42:7`. Asking again with the same key stores no second email.
- `kind` (required): `TICKET`, `CANCELLATION` or `REFUND`.
- `to` (required, valid address), `toName` (optional), `reference` (required, booking reference, at most 20 characters).
- `trip` (optional): `train`, `fromStation`, `departure`, `toStation`, `arrival`; the times are shown in `TIMEZONE`.
- `lines` (optional, at most 60 lines): extra lines such as one per seat.
- `amount` (optional, not negative), `currency` (only `EGP`), `detail` (optional, at most 500 characters).
- `attachment` (optional): `fileName`, `contentType`, `contentBase64` (the ticket PDF).
- `paymentMethod` (optional): `CARD` (default), `COUNTER` or `COMPLIMENTARY`. It changes the wording only.

Any other path under `/internal` answers 404 (after the key check); any path outside `/internal` answers 403 `forbidden`; a wrong method answers 405.

## Tables (Flyway V1)
- **`email`**: `id` (identity), `dedupe_key` (unique, `uk_email_dedupe`), `kind` (CHECK TICKET/CANCELLATION/REFUND), `booking_ref`, `recipient`,
  `recipient_name`; the finished message: `subject`, `body_text` (CLOB), `body_html` (CLOB), `attachment_name`, `attachment_type`, `attachment` (BLOB);
  the retry state: `status` (CHECK PENDING/SENT/FAILED, default PENDING), `attempts`, `next_attempt_at`, `last_error`; times `created_at`, `sent_at`.
  Index `ix_email_due (status, next_attempt_at)` serves the poller. The table is an **outbox**: the row is the work item.
- **`audit_log`**: `id`, `admin_id` (here the calling service name), `entity`, `entity_id`, `action`, `diff` (CLOB), `created_at`.

## Background jobs
- **`EmailScheduler`** runs `EmailDispatcher.dispatchDue()` with `@Scheduled(fixedDelay)`, default every 1000 ms
  (`trainbooking.notification.fixed-delay-ms`). `fixedDelay` waits for the previous run to end, so runs never overlap. It is off when
  `trainbooking.notification.enabled=false` (tests).

## Configuration
| Setting | Env variable | Meaning |
|---|---|---|
| `spring.datasource.*` | `NOTIFICATION_DB_URL`, `NOTIFICATION_DB_USER`, `NOTIFICATION_DB_PASSWORD` | Own Oracle schema; Flyway uses the same user. |
| `trainbooking.internal-api-key` | `INTERNAL_API_KEY` | Shared key; no default, the service does not start without it. |
| `spring.mail.host`, `port` | `MAIL_HOST`, `MAIL_PORT` | SMTP server (default Mailpit, localhost:1025). |
| `spring.mail.username`, `password`, `smtp.auth`, `starttls.enable` | `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_AUTH`, `MAIL_STARTTLS` | Login and TLS for a real server. |
| `mail.smtp.connectiontimeout`, `timeout`, `writetimeout` | fixed | 5 s, 10 s, 10 s, so a hung server cannot block the sender forever. |
| `trainbooking.mail.from` | `MAIL_FROM` | Sender address. |
| `trainbooking.timezone` | `TIMEZONE` | Time zone of the times in the emails (default Africa/Cairo). |
| `trainbooking.notification.fixed-delay-ms` | none | Poll interval. |

## Important code paths, step by step

### A. Booking asks for an email
1. `SecurityConfig` chain 1 sends `/internal/**` through `InternalApiKeyFilter`: a missing or wrong key gives 401 `invalid_api_key`.
2. `NotificationController.send` validates the body (`@Valid`); a failure becomes 400 through `GlobalExceptionHandler`.
3. `NotificationService.accept` (one `@Transactional` transaction): look up `dedupe_key`; if found, return it with `created=false` (HTTP 200).
4. Otherwise `EmailTemplates.render` builds subject, text and HTML; the base64 attachment is decoded.
5. `EmailRepository.insert` stores the row `PENDING`, `attempts 0`, `next_attempt_at = now`. If two requests with the same key race, the unique constraint
   raises ORA-00001 (`DuplicateKeyException`) for the loser, which then returns the winner's row (200).
6. `AuditTrail.record` adds an `audit_log` row (actor `booking-service`, action `QUEUE`) in the same transaction and logs after commit.
7. The controller answers 202 `{ id, status }`. Nothing was sent yet.

### B. Sending (`EmailDispatcher.dispatchDue`)
1. `findDueIds`: up to 20 `PENDING` rows with `next_attempt_at <= now`, oldest first.
2. For each id, `find` loads the whole row; `SmtpEmailSender.send` builds a multipart MIME message and calls the SMTP server. **No transaction is open
   during the call**, so a slow server holds no database lock.
3. Success: `markSent` runs `UPDATE ... WHERE status = 'PENDING'`; it changes the row only once, so an email is never counted twice.
4. Failure: `scheduleRetry` sets `next_attempt_at = now + min(300, 2^(attempts+1))` seconds (2, 4, 8 ... capped at 300) and keeps the error text. After
   300 attempts `markFailed` sets `FAILED` and an error is logged. One failing email does not block the others.
5. If the process dies after the SMTP server accepted the message but before `markSent`, the email is sent twice. This is accepted on purpose (same ticket).

### C. The wording (`EmailTemplates.render`)
- Ticket: "confirmed, ticket attached"; the payment line is "Total paid", "Paid at counter" or "Complimentary (no charge)" by payment method.
- Cancellation: a card booking promises the **full** refund to the card; a counter booking says the refund is handled at the support desk; a
  complimentary booking says nothing was charged.
- Refund: "the refund of X for booking R is done".
- Times use the configured zone; every request value is HTML-escaped in the HTML part.

## Tests
Counts by `@Test` methods: 8 template tests, 12 database tests, 1 Mailpit test. The database tests need `NOTIFICATION_TEST_DB_PASSWORD` and
run on `notification_test`; without it they show as SKIPPED. They were not run for this documentation step (comments only).

## Known limits
- At-least-once delivery: a crash between the SMTP send and `markSent` can send an email twice.
- An invalid base64 attachment is not validated as base64 by Bean Validation; it throws `IllegalArgumentException` and is answered as 500 `internal_error`.
- `RequestLogContext` removes a `userId` field and matches `/bookings|payments|tickets|refunds/<id>` paths that this service does not have; it is
  the same filter as in the other services. `RequestValidationException` and `AuditTrail.change` are not used here.
- The audit actor is the fixed text `booking-service`, whoever really called with the key.
- The backoff exponent is capped at 2^9 = 512 before the 300 s cap, so the wait is 2, 4, 8, 16, 32, 64, 128, 256, then 300 seconds.
- The email body stored in the database contains the passenger name, address and journey (needed to resend); there is no clean-up of old `SENT` rows.
