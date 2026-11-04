# Ticket service

Spring Boot service (Java 21, Gradle, Spring MVC: controller → service → repository) that owns the **PDF ticket with its QR code** and its
invalidation (`CLAUDE.md`, "Services"). Users never call it: Booking & Seat does, with the shared API key, from its outbox.

## Responsibility

- Stores its data in its own Oracle schema, `ticket` (tables `ticket` and `audit_log`).
- **One ticket per booking** (all its seats and standing places in one PDF), made when the booking is confirmed. The row has `booking_id`
  (unique), `qr_token`, `status` (`VALID | INVALIDATED`), the `pdf` (a BLOB) and the timestamps. Asking again for the same booking returns the
  same ticket and the same QR code, also after an invalidation: a repeated request never brings a cancelled ticket back.
- **The PDF** (OpenPDF) shows the booking reference, the passenger, the train, the date, the origin and destination with their times (in the
  time zone `TIMEZONE`, default Africa/Cairo), the coach and seat numbers or standing places, the price paid, and the QR code (ZXing).
- **The QR code** carries `reference.nonce.signature`: an HMAC-SHA256 with a key only this service knows (`TICKET_SIGNING_KEY`), so a token
  cannot be made up or changed. Whether the ticket is still valid is the `status` of its row.
- **Invalidation** (a cancelled booking) sets `INVALIDATED` and `invalidated_at`; it is idempotent and keeps the PDF as the record.

## Endpoints

All of them need the shared key in `X-Internal-Api-Key`; a user's token is not accepted and the Gateway never forwards them.

| Method | Path | Purpose |
|---|---|---|
| PUT | `/internal/tickets/{bookingId}` | Makes the ticket (201) or returns the existing one (200). Body: `reference`, `passengerName`, `trainNumber`, `trainName`, `fromStation`, `departure`, `toStation`, `arrival`, `lines[{ type SEAT\|STANDING, coachCode, coachClass, seatNumber, qty, price }]`, `amount`, `currency: EGP`. The answer has `ticketId`, `bookingId`, `reference`, `status`, `createdAt`, `invalidatedAt` (never the QR token) |
| GET | `/internal/tickets/{bookingId}` | The same fields; 404 `ticket_not_found` |
| GET | `/internal/tickets/{bookingId}/pdf` | The PDF (`application/pdf`) |
| GET | `/internal/tickets/{bookingId}/qr` | The QR code of the ticket as a PNG (`image/png`): the token that is in the PDF, dark modules on white with a quiet zone of 4 modules and a whole number of pixels per module (`QrImages`, the one method used for the page and the PDF; the page copy is about 280 px, the PDF copy about 900 px). 404 until the ticket exists |

**Logo on the PDF.** The renderer draws a logo at the top of the page when the PNG `brand/logo-red.png` is on the classpath (`src/main/resources/brand/`, at least 1000 px wide so it stays sharp when printed; it is drawn 110 pt wide). OpenPDF cannot draw an SVG and no SVG library is added, so the official red logo has to be supplied as a PNG. Without the file the PDF uses the plain header and logs a warning.
| POST | `/internal/tickets/{bookingId}/invalidate` | `INVALIDATED`; idempotent; 404 `ticket_not_found` if there is no ticket |

## Environment variables

Set in `services/ticket/.env` (git-ignored; copy `.env.example`), or in the process environment.

| Variable | Default | Meaning |
|---|---|---|
| `TICKET_DB_PASSWORD` | none, required | Password of the `ticket` Oracle user (same as in the root `.env`) |
| `INTERNAL_API_KEY` | none, required | Shared key for service-to-service calls (the same value in Booking & Seat's `.env`) |
| `TICKET_SIGNING_KEY` | none, required (16+ characters) | Signs the QR tokens; changing it makes old QR codes unverifiable |
| `TIMEZONE` | `Africa/Cairo` | Time zone of the times printed on the ticket |
| `TICKET_TEST_DB_PASSWORD` | none | Password of the `ticket_test` user for the database tests; without it those tests are skipped |
| `TICKET_DB_URL`, `TICKET_DB_USER` | `jdbc:oracle:thin:@//localhost:1521/FREEPDB1`, `ticket` | Connection |

The service listens on port **8084**.

## Run

Oracle must be running (`docker compose up -d` from the repository root). From the repository root:

```
gradle :services:ticket:bootRun
```

## Tests

```
gradle :services:ticket:test
```

The QR token (genuine, changed reference, changed signature, another key, garbage), the QR image (decoded back to the token), and the PDF
(text extracted: reference, passenger, train, seats, standing places, prices, total, times converted to the local zone; the QR image is on
the page) need no database. The **database tests** run the whole application on the throwaway schema `ticket_test` (emptied before every
test; a guard refuses any schema not ending in `_test`): one ticket per booking even for two requests at the same moment, invalidation once,
no resurrection, a 300 KB BLOB round trip, the security of the internal endpoints and the validation of the input. Without
`TICKET_TEST_DB_PASSWORD` they are **skipped, not passed** (Gradle prints each as SKIPPED). The schema is optional: the Oracle init script creates
it on a fresh volume if the password is set in the root `.env`; for an existing volume create it once as SYS, like `booking_seat_test`.

## Logs, audit and traces (OpenObserve)

- **Structured logs.** With `LOGGING_STRUCTURED_FORMAT_CONSOLE=logstash` (set by Compose) every log line is one JSON object. `RequestLogContext` writes one `access` line per request with the method, the **path** (never the query string, a header or a body), the status and the duration, and puts the booking id and the trip id of the path into the logging context, so every line of that request carries them as fields.
- **Audit.** `AuditTrail.record` writes the `audit_log` row and, **after the transaction commits** (never for a change that rolls back), a log line from the logger `audit` with the fields `actor`, `entity`, `entityId`, `action`, `bookingId` / `tripId` and the `diff`, so every admin action and booking state change reaches OpenObserve with its timestamp and actor.
- **Traces and shipping.** The Docker image starts the service with the OpenTelemetry Java agent (`infra/docker/entrypoint-java.sh`). With `OTEL_EXPORTER_OTLP_ENDPOINT` and the OpenObserve login set (Compose does), the agent sends traces and the logs, with the logging-context fields as attributes, to OpenObserve. A call from the Gateway continues the Gateway's trace. Run on the host with Gradle, the service just logs to the console.
