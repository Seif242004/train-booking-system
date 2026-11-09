# 6. Ticket service

## Purpose
Ticket owns the **PDF ticket** of a booking, with its **QR code**, and the **invalidation** of that ticket when the booking is cancelled. It is a small
service: it makes one ticket per booking, stores it, serves it, and turns it off. It decides nothing about money or seats.

- Port **8084**. Oracle schema (database user) `ticket`. Spring Boot, Spring MVC, Spring JDBC (`JdbcClient`), Flyway, OpenPDF (the PDF), ZXing (the QR image).
- **Internal only.** Users never call it and the Gateway has no route to it. The only caller is **Booking & Seat**, from its outbox jobs, with the shared key
  in the header `X-Internal-Api-Key`. A user's JWT is not accepted.
- It calls no other service.
- Everything printed on the ticket arrives in the request body. Ticket never looks up a booking, a trip or a user (a service never reads another service's data).

## Folder structure
```
services/ticket/
  build.gradle, README.md, .env.example
  src/main/resources/application.properties
  src/main/resources/brand/logo-red.png            the logo drawn on the PDF (optional; a warning is logged if missing)
  src/main/resources/db/migration/V1__create_ticket_tables.sql
  src/main/java/com/trainbooking/ticket/
    TicketApplication.java
    audit/       audit_log writing
    common/      error shape, handler, request log filter, config beans
    security/    the internal API key chain
    ticket/      controller, service (QR, PDF), repository, model
  src/test/java/...   testsupport/ and ticket/
```

## Every file, one line each
### Root, audit, common, security
| File | What it does |
|---|---|
| `TicketApplication` | Entry point; `@SpringBootApplication` scans the packages and configures Spring from the classpath. |
| `audit/AuditLogRepository` | One `INSERT` into `audit_log`, in the caller's transaction. |
| `audit/AuditTrail` | Builds the before/after JSON, writes the row, and writes the structured `audit` log line **after the commit**. |
| `common/ApiError` | The one JSON error shape `{ error: { code, message, details? } }`. |
| `common/GlobalExceptionHandler` | Maps exceptions to the shape: 400 `validation_failed` / `malformed_request`, 404 `ticket_not_found` / `not_found`, 405, 500 `internal_error`. |
| `common/RequestValidationException` | Shared error type for rules Bean Validation cannot express; this service does not throw it. |
| `common/RequestLogContext` | Filter: puts the booking id in the logging context and writes one `access` line per request (path only, no query string). |
| `common/TicketConfig` | Beans: the UTC `Clock`, `QrTokens` (with the signing key), `TicketPdfRenderer` (with the time zone). |
| `security/SecurityConfig` | Two filter chains: `/internal/**` needs the key; everything else is denied. |
| `security/InternalApiKeyFilter` | Reads `X-Internal-Api-Key`, compares it in constant time, logs the caller in as `ROLE_INTERNAL`. |
| `security/JsonErrorHandlers` | Writes the 401 and 403 answers in the shared JSON shape (they happen before any controller). |

### Ticket feature
| File | What it does |
|---|---|
| `ticket/controller/TicketController` | The five internal endpoints; no logic. |
| `ticket/service/TicketService` | Issue once (idempotent), read, PDF, QR picture, invalidate (idempotent). All transactions are here. |
| `ticket/service/QrTokens` | Makes and verifies the QR token `reference.nonce.signature` (HMAC-SHA256). |
| `ticket/service/QrImages` | Draws a token as a PNG QR image (used for the web page copy and for the PDF). |
| `ticket/service/TicketPdfRenderer` | Draws the one-page A5 landscape PDF with OpenPDF. No state, no database. |
| `ticket/repository/TicketRepository` | The SQL of the table `ticket`. |
| `ticket/model/Ticket` | A ticket row without the PDF. |
| `ticket/model/TicketStatus` | `VALID` or `INVALIDATED`. |
| `ticket/model/TicketRequest` | The request body of the issue call, with its validation rules; nested `Line`. |
| `ticket/model/TicketNotFoundException` | Becomes 404 `ticket_not_found`. |

### Tests (`src/test/java/...`)
| File | What it tests |
|---|---|
| `testsupport/DatabaseTest` | Base class: starts the whole app on the schema `ticket_test`, empties the tables before each test, refuses a schema not ending in `_test`. |
| `testsupport/DatabaseTestConfig`, `MutableClock` | Replace the clock with one the test moves by hand. |
| `testsupport/RequiresTestDatabase`, `TestDatabase` | Skip the database tests (reported as SKIPPED) when `TICKET_TEST_DB_PASSWORD` is not set; read the connection settings. |
| `ticket/QrTokensTest` | Genuine token, different tokens, forged token, garbage, missing key, QR decodes back (6 tests). |
| `ticket/QrImagesTest` | QR decodes at both sizes, black on white with a 4-module quiet zone and whole-pixel modules, and the QR inside the PDF decodes (3 tests). |
| `ticket/TicketPdfRendererTest` | What the PDF shows: all facts, time zone, one A5 landscape page, QR and logo, three payment lines, many seats, long names, odd characters (15 tests, no database). |
| `ticket/TicketServiceDatabaseTest` | Issue once, repeat, two at the same moment, per booking, invalidate once, no resurrection, not found, large BLOB (8 tests). |
| `ticket/TicketControllerDatabaseTest` | The key (401), 201 then 200, QR PNG, PDF, invalidate, input validation, closed paths (7 tests). |

## Endpoints
All need `X-Internal-Api-Key`. A missing or wrong key is `401 invalid_api_key`. Any path outside `/internal/**` is refused.

| Method and path | Auth | Request | Response | Errors |
|---|---|---|---|---|
| `PUT /internal/tickets/{bookingId}` | key | JSON `TicketRequest` (below) | `201` new ticket, `200` existing ticket: `{ ticketId, bookingId, reference, status, createdAt, invalidatedAt }` | `400 validation_failed`, `400 malformed_request` |
| `GET /internal/tickets/{bookingId}` | key | none | same fields as above | `404 ticket_not_found` |
| `GET /internal/tickets/{bookingId}/pdf` | key | none | `application/pdf`, `Content-Disposition: inline` | `404 ticket_not_found` |
| `GET /internal/tickets/{bookingId}/qr` | key | none | `image/png` (about 280 px), `Cache-Control: private, no-store` | `404 ticket_not_found` |
| `POST /internal/tickets/{bookingId}/invalidate` | key | none | `200` the ticket, now `INVALIDATED` | `404 ticket_not_found` |

The answer never contains the QR token: whoever has the token has the QR code.

**`TicketRequest` fields:** `reference` (max 20), `passengerName` (max 200), `trainNumber` (max 50), `trainName` (optional), `fromStation`, `departure`,
`toStation`, `arrival` (ISO date-times with offset), `lines` (1 to 60), `amount` (at least 0.01), `currency` (must be `EGP`), `paymentMethod` (optional:
`CARD`, `COUNTER`, `COMPLIMENTARY`; absent means `CARD`). A line has `type` (`SEAT` or `STANDING`), `coachCode`, `coachClass`, `seatNumber` (not for standing),
`qty` (at least 1) and `price` (not negative). The amount is the amount the server worked out for the booking; Ticket does not compute prices.

## Tables (Flyway V1)
- **`ticket`**: `id` (identity), `booking_id` (the id in Booking & Seat, a plain number, `UNIQUE`), `reference`, `qr_token` (`UNIQUE`), `status` (`CHECK` `VALID` or
  `INVALIDATED`), `pdf` (`BLOB`), `created_at`, `invalidated_at` (empty while valid). A row is never deleted.
- **`audit_log`**: `id`, `admin_id` (here the calling service, `booking-service`), `entity`, `entity_id`, `action` (`CREATE`, `INVALIDATE`), `diff` (`CLOB`), `created_at`.

## Background jobs
None. Ticket only answers calls. The retries live in Booking & Seat (its outbox repeats the issue and invalidate calls until they succeed), which is why both calls are idempotent.

## Configuration
| Setting (environment variable) | Meaning |
|---|---|
| `TICKET_DB_URL`, `TICKET_DB_USER`, `TICKET_DB_PASSWORD` | Connection of the `ticket` Oracle user; Flyway uses the same user. |
| `INTERNAL_API_KEY` | The shared key. No default: the service does not start without it. |
| `TICKET_SIGNING_KEY` | Signs the QR tokens. At least 16 characters, no default. Changing it makes old QR codes unverifiable. |
| `TIMEZONE` | Zone of the printed times (default `Africa/Cairo`). |
| `TICKET_TEST_DB_PASSWORD` | Password of `ticket_test`; without it the database tests are skipped. |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE`, `OTEL_*` | Set by Compose: JSON logs and the OpenTelemetry agent to OpenObserve. |

## Important code paths, step by step
### A. Issue a ticket (`TicketService.issue`)
1. Booking & Seat confirms a booking and its `TICKET_EMAIL` job calls `PUT /internal/tickets/{bookingId}`.
2. Spring Security runs `InternalApiKeyFilter`: wrong key means 401 and no controller runs. Then `@Valid` checks the body (400 on failure).
3. `TicketService.issue` (one transaction) first looks for a ticket of this booking. If there is one, it returns it unchanged with `created = false`
   (200). This is also true for an invalidated ticket, so a repeat never revives a cancelled ticket.
4. Otherwise `QrTokens.issue` makes `reference.nonce.signature` (12 random bytes, HMAC-SHA256 with `TICKET_SIGNING_KEY`).
5. `TicketPdfRenderer.render` draws the PDF with that token inside the QR; `QrImages.png` draws the QR at 900 px so the print stays sharp.
6. `TicketRepository.insert` stores the row. The `UNIQUE (booking_id)` key makes a second ticket impossible: if two calls race, the loser gets
   `DuplicateKeyException` (ORA-00001) and answers with the winner's ticket.
7. An `audit_log` row (`CREATE`) is written in the same transaction; the audit log line goes out after the commit. The answer is 201.

### B. Invalidate (`TicketService.invalidate`)
1. A cancelled booking queues `TICKET_INVALIDATE`; the job calls `POST .../invalidate`.
2. The row is read with `SELECT ... FOR UPDATE`, so two invalidations run one after the other.
3. If `VALID`: `UPDATE ... WHERE status = 'VALID'` sets `INVALIDATED` and the time, and an `INVALIDATE` audit row is written. If already invalidated: returned as it is.
4. The PDF stays as the record. Missing ticket: 404 (Booking & Seat retries until the issue job has made it).

### C. The QR code
The token has three parts separated by dots: the reference, a random nonce and the signature. Nobody without the key can make or change one
(`QrTokens.verify` checks it in constant time). The token says nothing about validity: whether the ticket is still good is the `status` of its row. No
service calls `verify` today; it is ready for a gate scanner. The page copy of the QR comes from `GET .../qr`, drawn from the stored token with the same
method as the PDF.

### D. The PDF
`TicketPdfRenderer` draws one A5 landscape page by absolute coordinates: card, header with logo, route with big times (converted to `TIMEZONE`), a grid
of facts (date, train, passenger, class, coach, seats or places), the QR panel with the reference, and a footer with the payment line. Built-in fonts only,
and every text goes through `safe()` so an unusual name never breaks the rendering. A long seat list shrinks and ends with "and N more" only as a last resort.

## Tests
Unit tests need no database: QR tokens, QR images and the PDF (text read back from the file). The database tests use the schema `ticket_test` and are
**skipped, not passed** without `TICKET_TEST_DB_PASSWORD`. Counting the `@Test` methods: 6 + 3 + 15 + 8 + 7 = 39 (24 without a database, 15 with).

## Known limits
- `QrTokens.verify` has no caller yet: there is no gate-scanner endpoint (not in the SRS list of endpoints).
- The QR token is a bearer secret; the answer of `/qr` and the PDF contain it, so only trusted services may call these routes (they are internal-only).
- The PDF is drawn before the `INSERT`, inside the transaction; a race loser draws a PDF that is thrown away.
- `RequestValidationException` and `AuditTrail.change` are shared code that this service does not use.
- The audit actor is always `booking-service` (the real user is not known here), and the column is named `admin_id`.
- Only English text; characters outside the Windows-1252 set (Arabic, emoji) are replaced by `?` on the PDF.
- The logo needs a PNG file (OpenPDF cannot draw SVG); without it the PDF has a plain header.
