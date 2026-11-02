# Payment service

Spring Boot service (Java 21, Gradle, Spring MVC: controller → service → repository) that owns the **Stripe payments, the
verification of Stripe's webhook, and the refunds** (`CLAUDE.md`, "Services"). Users never call it: Booking & Seat does, with the
shared API key, and Stripe does, through the Gateway.

## Responsibility

- Stores its data in its own Oracle schema, `payment` (tables `payment`, `refund`, `webhook_event`, `outbox`, `audit_log`).
- **Creates the payment of a booking** for the amount Booking & Seat worked out (the browser never sends an amount) and hands the
  browser only the *client secret* and the *publishable key* of the embedded Stripe form. Card data goes from the browser straight to
  Stripe; the secret key and the webhook secret never leave this service.
- **Cancels it when the hold ends, and invalidates it when the booking changes**, always before Booking releases anything (SRS 4.2):
  - *cancel* (the hold ended: the five minutes ran out, the user let go of everything, the trip was cancelled) closes the payment for
    good. If there was no payment, a closed **tombstone** row is written, so a creation that was already on its way is refused.
  - *invalidate* (a seat was picked or released after the user had been to the payment page) cancels the open payment so its amount
    can never differ from the booking's; a new payment (revision 2, a new Stripe idempotency key) may be created afterwards.
  - Both answer `NONE`, `CANCELLED`, `SUCCEEDED` (too late: the payment wins) or `PROCESSING` (Stripe is still working: the caller keeps
    the seats held and asks again). If Stripe cannot be reached the answer is 503 and nothing is assumed.
- **Handles Stripe's webhook** (`POST /webhooks/payment`, forwarded by the Gateway with the body untouched): the signature is verified
  with the webhook secret before anything is read (a missing, wrong or older-than-5-minutes signature is a 400), and every event is
  handled **at most once** — its id is the primary key of `webhook_event`, inserted in the same transaction as the effect. An event
  whose handling fails rolls back its record, so Stripe's retry processes it again.
- **Refunds** are always the full amount, once per booking (`refund.booking_id` is unique). The refund row is written first and its id is
  the Stripe idempotency key, so no retry can refund twice. A timeout never refunds (FR-P4). When Stripe cannot be reached the refund is retried
  with a backoff; when Stripe refuses it for good (a 4xx such as "already refunded") or an hour of retries failed, it is flagged `REFUND_FAILED`.
- **Tells Booking & Seat** (a payment succeeded, a refund changed) through a DB **outbox** delivered by a `@Scheduled` poller with a
  backoff (2, 4 ... 60 s), in order per booking. Booking answers whether the booking was confirmed or the money must be refunded.
- Never calls Stripe inside an open database transaction: each operation is a short transaction that decides, the Stripe call, then a
  short transaction that records the result and re-checks that nothing changed in between.

## Stripe test mode only

The service uses Stripe's **test mode (sandbox)** and refuses to start with anything else: `STRIPE_SECRET_KEY` must start with `sk_test_`, `STRIPE_PUBLISHABLE_KEY` with `pk_test_` and `STRIPE_WEBHOOK_SECRET` with `whsec_` (`StripeKeyGuard`; its message never contains the key). The keys are read from the root `.env` (and may be overridden in `services/payment/.env`); only the variable names are in `.env.example`. Payments are **cards only** (the intent allows only the `card` payment method type; this Stripe API version no longer accepts `payment_method_types`) in **EGP**, in piastres (amount × 100, exact). The sandbox accepts EGP. Stripe has a **minimum charge of about US$0.50 (roughly EGP 27)**: a smaller amount is refused with 409 `amount_too_small` and leaves nothing behind. The embedded Payment Element is used, never Stripe's hosted Checkout page. The Stripe test cards for manual runs: `4242 4242 4242 4242` (success), `4000 0000 0000 0002` (declined), `4000 0000 0000 3220` (3D Secure), with any future expiry and any 3-digit CVC.

## Endpoints

| Method | Path | Caller | Purpose |
|---|---|---|---|
| POST | `/internal/payments` | Booking & Seat (API key) | `{ bookingId, amount, currency: "EGP" }` → `{ paymentId, clientSecret, publishableKey, amount, currency }`. Asking again returns the same intent. 409 `payment_closed` (the hold ended), `already_paid`, `payment_in_progress`, `payment_outdated` (an open payment for another amount); 503 `stripe_unavailable` / `stripe_not_configured` |
| POST | `/internal/payments/{bookingId}/cancel` | Booking & Seat | The hold ended: `{ outcome }` as above |
| POST | `/internal/payments/{bookingId}/invalidate` | Booking & Seat | The booking changes: `{ outcome }` |
| POST | `/internal/refunds` | Booking & Seat | `{ bookingId, reason? }` → `{ refundId, bookingId, status: REFUND_PENDING \| REFUNDED \| REFUND_FAILED }`. Asking again returns the same refund. 404 `no_payment_to_refund` |
| POST | `/webhooks/payment` | Stripe, through the Gateway | Stripe events: `payment_intent.succeeded`, `.processing`, `.payment_failed`, `.canceled`, `refund.updated`, `refund.failed`. Others are acknowledged and ignored. 400 `invalid_signature` |

Payment calls Booking & Seat (`POST /internal/bookings/{id}/payment-result` and `/refund-status`) from the outbox.

## Environment variables

Set in `services/payment/.env` (git-ignored; copy `.env.example`), or in the process environment.

| Variable | Default | Meaning |
|---|---|---|
| `PAYMENT_DB_PASSWORD` | none, required | Password of the `payment` Oracle user (same as in the root `.env`) |
| `INTERNAL_API_KEY` | none, required | Shared key for service-to-service calls (the same value in Booking & Seat's `.env`) |
| `STRIPE_SECRET_KEY` | empty | Stripe **test** secret key (`sk_test_...`; a live key stops the service from starting). Without it the service starts, and every call that needs Stripe answers 503 `stripe_not_configured` (there is no fake Stripe) |
| `STRIPE_PUBLISHABLE_KEY` | empty | Stripe test publishable key (`pk_test_...`), handed to the browser with the client secret |
| `STRIPE_WEBHOOK_SECRET` | empty | Signing secret of the webhook (`whsec_...`, from `stripe listen` or the dashboard endpoint). Without it every webhook is refused |
| `BOOKING_SEAT_URL` | `http://localhost:8082` | Where Booking & Seat runs |
| `TRAINBOOKING_OUTBOX_FIXED_DELAY_MS` | `1000` | Delay between two runs of the outbox and of the refund retries |
| `PAYMENT_TEST_DB_PASSWORD` | none | Password of the `payment_test` user for the database tests; without it those tests are skipped |
| `PAYMENT_DB_URL`, `PAYMENT_DB_USER` | `jdbc:oracle:thin:@//localhost:1521/FREEPDB1`, `payment` | Connection |

The service listens on port **8083**.

## Run

Oracle must be running (`docker compose up -d` from the repository root). From the repository root:

```
gradle :services:payment:bootRun
```

To receive webhooks while developing, forward Stripe's events to the Gateway with the Stripe CLI of the dev profile of Compose (see `docker-compose.yml`): `docker compose --profile dev run --rm stripe-cli listen --print-secret` prints the `whsec_...` signing secret, which goes in `STRIPE_WEBHOOK_SECRET` in the root `.env`; then `docker compose --profile dev up stripe-cli` forwards the events to `http://host.docker.internal:8080/webhooks/payment`. The Gateway listens on 127.0.0.1 by default, so start it with `HOST=0.0.0.0` for the forwarding to reach it.

## Tests

```
gradle :services:payment:test
```

- The logic is tested against a real Oracle schema, **`payment_test`**, emptied before every test (a guard refuses to run on a schema
  that does not end in `_test`), with a **fake Stripe** and a fake Booking: creation, the race between creating a payment and the hold
  ending (the user never gets a client secret for a payment that is being cancelled), cancel and invalidate with every Stripe answer
  (succeeded, processing, down), refunds once, the webhook (real HMAC signatures: missing, wrong secret, tampered body, old timestamp,
  duplicate events, two deliveries at the same moment, rollback of the event record when handling fails), the outbox (backoff, order,
  rejections, a refund requested by Booking's answer) and the security of the internal endpoints.
- `LiveStripeTest` talks to the **real Stripe test mode** (create, idempotency, cancel, "too late to cancel" after a payment, full
  refund). It is skipped, and shown as SKIPPED, unless `STRIPE_SECRET_KEY` is a test key.
- If `PAYMENT_TEST_DB_PASSWORD` is not set the database tests are **skipped, not passed**: Gradle prints each one as `SKIPPED` and ends with
  a line such as `Payment tests: 58 run, 3 passed, 0 failed, 55 SKIPPED`. The schema is optional: `infra/oracle/init/01-create-schemas.sh`
  creates it on a fresh volume only if `PAYMENT_TEST_DB_PASSWORD` is set in the root `.env`. For an existing volume, create it once as
  SYS in `FREEPDB1` (a tablespace, `CREATE USER payment_test ... QUOTA UNLIMITED`, and `GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE,
  CREATE VIEW`), like `booking_seat_test`.
