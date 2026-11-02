# Notification service

Spring Boot service (Java 21, Gradle, Spring MVC: controller → service → repository) that owns the **emails** of the system: the ticket,
the cancellation and the refund (`CLAUDE.md`, "Services"). Users never call it: Booking & Seat does, with the shared API key, from its outbox.

## Responsibility

- Stores its data in its own Oracle schema, `notification` (tables `email` and `audit_log`).
- **A request is stored and answered at once** (202); a poller sends the email by SMTP afterwards (Spring Mail), with retries. So a mail
  server that is down, or an address that is refused, can never fail the payment, the confirmation or the cancellation that asked for the
  email (`CLAUDE.md`: an email failure never cancels a booking).
- **Retries:** a failed send is tried again after 2, 4 ... 300 seconds (the backoff is capped); after 300 attempts the email is marked `FAILED`
  and logged as an error. The ticket email keeps its PDF attached to every retry.
- **Idempotent:** the caller chooses a `dedupeKey` (for example `ticket:42:7`); asking again with the same key stores no second email (200
  instead of 202). So Booking can repeat a request whose answer it did not get.
- **The words** of the three emails are built here (`EmailTemplates`: plain text and HTML in one message; everything from a request is
  escaped in the HTML). The callers only say what happened. The times are shown in `TIMEZONE`.

## Endpoint

Needs the shared key in `X-Internal-Api-Key`; a user's token is not accepted and the Gateway never forwards it.

| Method | Path | Purpose |
|---|---|---|
| POST | `/internal/notifications` | `{ dedupeKey, kind TICKET\|CANCELLATION\|REFUND, to, toName?, reference, trip?{ train, fromStation, departure, toStation, arrival }, lines?[], amount?, currency?, detail?, attachment?{ fileName, contentType, contentBase64 } }` → 202 `{ id, status: PENDING }` (200 for a known `dedupeKey`). 400 for an invalid body |

## Environment variables

Set in `services/notification/.env` (git-ignored; copy `.env.example`), or in the process environment.

| Variable | Default | Meaning |
|---|---|---|
| `NOTIFICATION_DB_PASSWORD` | none, required | Password of the `notification` Oracle user (same as in the root `.env`) |
| `INTERNAL_API_KEY` | none, required | Shared key for service-to-service calls (the same value in Booking & Seat's `.env`) |
| `MAIL_HOST`, `MAIL_PORT` | `localhost`, `1025` | The SMTP server. The defaults are **Mailpit** of the dev profile of Compose |
| `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_AUTH`, `MAIL_STARTTLS` | empty, empty, `false`, `false` | SMTP login and TLS, for a real server |
| `MAIL_FROM` | `tickets@trainbooking.local` | The sender address |
| `TIMEZONE` | `Africa/Cairo` | Time zone of the times in the emails |
| `NOTIFICATION_TEST_DB_PASSWORD` | none | Password of the `notification_test` user for the database tests; without it those tests are skipped |

The service listens on port **8085**.

## Run

Oracle must be running (`docker compose up -d`), and for development Mailpit (`docker compose --profile dev up -d mailpit`; the mails are
shown at http://localhost:8025 and nothing is ever sent to a real address). From the repository root:

```
gradle :services:notification:bootRun
```

## Tests

```
gradle :services:notification:test
```

The templates need no database. The **database tests** run on the throwaway schema `notification_test` with a fake mail server: stored
pending and answered at once, the dedupe key (also for two requests at the same moment), sent once, a mail server that is down (waits, backs
off, goes out when it is back), the capped backoff, one refused address not blocking the others, giving up after the last attempt, and the
security and validation of the endpoint. `SmtpEmailSenderMailpitTest` sends through **real SMTP into Mailpit** and checks the text, the HTML
and the PDF attachment through Mailpit's API; it is skipped (shown as SKIPPED) when Mailpit is not running. Without
`NOTIFICATION_TEST_DB_PASSWORD` the database tests are **skipped, not passed**.
