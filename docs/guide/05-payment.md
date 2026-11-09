# 5. Payment service

## Purpose
Payment owns **everything that touches Stripe**: creating the payment of a booking, cancelling it when the hold ends or changes, checking Stripe's
webhook, and refunding. Users never call it. Booking & Seat calls it (with the shared API key) and Stripe calls it (through the Gateway).

Why a separate service: the Stripe secret key and the webhook secret live only here, and card data never reaches our servers at all. The browser
sends the card straight to Stripe; Payment only hands the page a **client secret** (the key to one payment intent) and the **publishable key**.

- Port **8083**. Oracle schema (user) `payment`. Spring Boot, Spring MVC, Spring JDBC (`JdbcClient`), Flyway, Stripe Java 34.1.0.
- **Test mode only.** `StripeKeyGuard` stops the service at start-up if a key does not start with `sk_test_`, `pk_test_` or `whsec_`.
- Cards only, EGP, amounts in piastres (`amount x 100`, exact). Stripe's minimum charge is about US$0.50 (roughly EGP 27): below it, 409 `amount_too_small`.

## Folder structure
```
services/payment/
  build.gradle, README.md, .env.example
  src/main/resources/application.properties
  src/main/resources/db/migration/V1__create_payment_tables.sql
  src/main/java/com/trainbooking/payment/
    PaymentApplication.java
    audit/ common/ security/        cross-cutting
    payment/  (controller, service, repository, model)
    refund/   (controller, service, repository, model)
    webhook/  (controller, service)
    outbox/   (model, repository, service)
    stripe/                         the only code that knows Stripe
  src/test/java/...                 the same packages + testsupport/
```

## Every file, one line each
| File | What it does |
|---|---|
| `PaymentApplication` | Entry point (`@SpringBootApplication`). |
| `audit/AuditLogRepository` | One `INSERT` into `audit_log`. |
| `audit/AuditTrail` | Writes the audit row in the caller's transaction and the `audit` log line **after the commit**. |
| `common/ApiError` | The shared error JSON `{ error: { code, message, details? } }`. |
| `common/GlobalExceptionHandler` | Maps each exception to a status and a stable code. |
| `common/RequestValidationException` | A 400 with a list of field problems (not used by a Payment endpoint today). |
| `common/RequestLogContext` | Filter: puts booking id and trip id in the log context, one `access` line per request (path only). |
| `common/TimeConfig` | The UTC `Clock` bean and `@EnableScheduling`. |
| `security/SecurityConfig` | Three filter chains: `/internal/**` (API key), `/webhooks/**` (open, the signature protects it), the rest denied. |
| `security/InternalApiKeyFilter` | Constant-time check of `X-Internal-Api-Key`; grants `ROLE_INTERNAL`. Not a bean. |
| `security/JsonErrorHandlers` | 401 and 403 in the shared JSON shape. |
| `payment/controller/PaymentController` | The three internal payment endpoints. |
| `payment/service/PaymentService` | Create, cancel, invalidate, and the `record...` methods the webhook uses. |
| `payment/repository/PaymentRepository` | SQL for `payment`, with the `FOR UPDATE` reads. |
| `payment/model/Payment, PaymentStatus, PaymentSession, CreatePaymentRequest, CancelOutcome, PaymentConflictException` | Row, status enum, answer for the page, validated request, the four outcomes, the 409 exception. |
| `refund/controller/RefundController` | `POST /internal/refunds`. |
| `refund/service/RefundService` | Request, attempt, apply Stripe's result; one full refund per booking. |
| `refund/service/RefundRetrier` | Asks Stripe again for refunds it could not reach (every second, 20 at a time). |
| `refund/repository/RefundRepository` | SQL for `refund`. |
| `refund/model/Refund, RefundRequest, RefundStatus, NoPaymentToRefundException` | Row, request, `PENDING/SUCCEEDED/FAILED`, the 404 exception. |
| `webhook/controller/WebhookController` | `POST /webhooks/payment`, raw body. |
| `webhook/service/WebhookService` | Verifies the signature, deduplicates the event, applies it. |
| `outbox/model/OutboxMessage` | One message for Booking (`PAYMENT_RESULT` or `REFUND_STATUS`). |
| `outbox/repository/OutboxRepository` | SQL for `outbox`, including the in-order `findDue`. |
| `outbox/service/OutboxWriter` | Writes a message inside the caller's transaction (`MANDATORY`). |
| `outbox/service/OutboxDispatcher` | Delivers the due messages to Booking, backoff, refund on a refused payment. |
| `outbox/service/OutboxScheduler` | The two `@Scheduled` loops (deliver, retry refunds). |
| `outbox/service/BookingNotifier, HttpBookingNotifier` | Interface for "tell Booking", and the real HTTP client. |
| `stripe/StripeGateway` | The small interface the logic uses for Stripe. |
| `stripe/StripeApiGateway` | The real implementation with the official client. |
| `stripe/StripeConfig` | Chooses the real gateway, or `NotConfigured` (everything answers 503) when there is no key. |
| `stripe/StripeKeyGuard` | Refuses live keys at start-up. |
| `stripe/StripeCallException, StripeRejectedException, StripeNotConfiguredException` | "No answer" (503), "refused for good" (502), "no key" (503). |

## Endpoints
All `/internal/**` need `X-Internal-Api-Key` (else 401 `invalid_api_key`). Errors use the shared shape.

| Method and path | Auth | Request | Answer | Errors |
|---|---|---|---|---|
| `POST /internal/payments` | API key | `{ bookingId, amount, currency: "EGP" }` | `{ paymentId, clientSecret, publishableKey, amount, currency }`; asking again returns the same intent | 400 validation; 409 `payment_closed`, `already_paid`, `payment_in_progress`, `payment_outdated`, `amount_too_small`; 503 `stripe_unavailable`, `stripe_not_configured`; 502 `stripe_rejected` |
| `POST /internal/payments/{bookingId}/cancel` | API key | none | `{ outcome }` = `NONE`, `CANCELLED`, `SUCCEEDED`, `PROCESSING` | 409 `payment_in_progress`; 503 |
| `POST /internal/payments/{bookingId}/invalidate` | API key | none | `{ outcome }` | same |
| `POST /internal/refunds` | API key | `{ bookingId, reason? }` | `{ refundId, bookingId, status }` = `REFUND_PENDING`, `REFUNDED`, `REFUND_FAILED` | 404 `no_payment_to_refund`; 400; 503 |
| `POST /webhooks/payment` | none; the `Stripe-Signature` header | raw Stripe event | `{ received: true, result }` = `HANDLED`, `DUPLICATE`, `IGNORED` | 400 `invalid_signature`; 503 `stripe_not_configured` (no webhook secret) |

Handled event types: `payment_intent.succeeded`, `.processing`, `.payment_failed`, `.canceled`, `refund.updated`, `refund.failed`. Others are acknowledged and ignored.

Payment calls Booking & Seat: `POST /internal/bookings/{id}/payment-result` and `/refund-status` (from the outbox, with the API key).

## Tables (V1__create_payment_tables.sql; never edit an applied migration)
- **`payment`**: one row per booking (`uk_payment_booking`). `stripe_payment_intent_id` (unique), `status` (`CREATING`, `PENDING`, `PROCESSING`, `SUCCEEDED`, `CANCELLED`),
  `amount`, `currency`, `last_error` (last failed card attempt), `revision` (1, 2, ...; part of the Stripe idempotency key), `ended` (1 once the hold ended), timestamps.
- **`refund`**: one per booking (`uk_refund_booking`), FK to `payment`. `stripe_refund_id` (unique), `status` (`PENDING`, `SUCCEEDED`, `FAILED`), `reason`, `failure_reason`,
  `attempts`, `next_attempt_at` (retry time while Stripe was not reached).
- **`webhook_event`**: primary key = Stripe event id. Inserting it first is the "handled at most once" gate.
- **`outbox`**: `kind` (`PAYMENT_RESULT`, `REFUND_STATUS`), `booking_id`, JSON `payload` (CLOB), `status` (`PENDING`, `SENT`, `FAILED`), `attempts`, `next_attempt_at`, `last_error`. Index `ix_outbox_due`.
- **`audit_log`**: who (`admin_id` = `booking-service` or `stripe`), entity, action, JSON diff.

## Key code paths, step by step
**Rule for all of them:** every change locks the payment row first (`SELECT ... FOR UPDATE`, `PaymentRepository.findByBookingForUpdate`), and **Stripe is never called inside a transaction**.
The pattern is: short transaction that decides, then the Stripe call, then a short transaction that records the result and re-checks that nothing changed.

1. **Create** (`PaymentService.create`). Refuse when no Stripe key or no publishable key (nothing written). Transaction 1 (`claimForCreation`): no row, insert `CREATING`
   (a clash on the unique key is `ORA-00001`, shown as `payment_in_progress`); a closed row is `payment_closed`; `SUCCEEDED` is `already_paid`; `PROCESSING` is `payment_in_progress`;
   `PENDING` with the same amount is reused, with another amount `payment_outdated`; an old `CREATING` (over 60 s) is taken over; `CANCELLED` restarts as the next revision.
   Then `StripeGateway.createIntent` with key `payment-<booking>-r<revision>`. Transaction 2 (`attachIntent`): if the row was cancelled meanwhile the intent is cancelled and the caller gets 409
   (the secret is never handed out, so nobody can pay for seats that are being released).
2. **Cancel and invalidate** (`cancel`, `planCancel`, `settleCancel`). `cancel` = the hold ended: the row gets `ended = 1`, or a **tombstone** (closed row, no intent) when there was no row, so a
   creation already on its way is refused. `invalidate` = the booking changed: the intent is cancelled and a new revision may follow. If Stripe says `succeeded`, the payment **wins**: `recordSuccess`
   queues the news and the answer is `SUCCEEDED`. If `processing`, the answer is `PROCESSING` and Booking keeps the seats and asks again. If Stripe is down: 503, the row stays as it was.
3. **Webhook** (`WebhookService.handle`). Secret set? Header present? `Webhook.constructEvent` verifies the signature and the 5 minute tolerance. Then one transaction: insert `webhook_event`
   (duplicate: `DUPLICATE`), apply the event (`recordSuccess` writes an `outbox` row in the same transaction). A failure rolls back the insert too, so Stripe's retry works.
4. **Outbox** (`OutboxDispatcher.dispatchDue`, every second). Takes up to 20 due messages, one per booking at a time (older pending message first, `NOT EXISTS` in `findDue`), calls Booking
   (no transaction), marks `SENT`. Transient failure: backoff 2, 4 ... 60 s. Booking refuses for good (404/400): `PAYMENT_RESULT` triggers a refund, then the message is `FAILED`.
   If Booking answers `REFUND_REQUIRED` (hold gone or amount differs), a refund is requested too. So money taken always ends as a booking or a refund.
5. **Refund** (`RefundService.request`, `attempt`). Needs a `SUCCEEDED` payment. Transaction: insert the refund (`PENDING`), outbox `REFUND_PENDING`, audit. Then Stripe with key `refund-<id>`.
   Stripe unreachable: stays `PENDING`, `RefundRetrier` retries (backoff to 60 s, logged every hour, never given up). Stripe refuses for good (4xx): `FAILED` at once. The result or a
   `refund.updated` webhook sets `SUCCEEDED` or `FAILED` once; a final refund never changes again. A timeout never reaches this code (FR-P4).

## Background jobs
`OutboxScheduler`: `deliver()` (outbox) and `retryRefunds()` every `trainbooking.outbox.fixed-delay-ms` (default 1000 ms), `fixedDelay` so runs never overlap. Disabled by `trainbooking.outbox.enabled=false` (tests).

## Configuration
`application.properties` (not commented in this stage): `server.port=8083`, datasource from `PAYMENT_DB_URL/USER/PASSWORD`, `INTERNAL_API_KEY`, `BOOKING_SEAT_URL`, `STRIPE_SECRET_KEY`,
`STRIPE_PUBLISHABLE_KEY`, `STRIPE_WEBHOOK_SECRET`, `trainbooking.outbox.fixed-delay-ms`. Only names here; values are in git-ignored `.env` files.

## Tests (72 `@Test` methods)
Database tests extend `DatabaseTest` and run the whole application on the throwaway schema `payment_test` (emptied before each test, refuses a schema not ending in `_test`; skipped without
`PAYMENT_TEST_DB_PASSWORD`). `FakeStripeGateway` (idempotency, outages, a hook to run code mid-call), `FakeBookingNotifier`, `MutableClock` replace the outside world.
`PaymentServiceDatabaseTest` (races of create versus cancel), `WebhookDatabaseTest` (signature, duplicates, replay), `WebhookRollbackDatabaseTest`, `RefundDatabaseTest`, `OutboxDatabaseTest`,
`PaymentControllerDatabaseTest` (API key, validation), `PaymentWithoutPublishableKeyDatabaseTest`, plain unit tests `StripeKeyGuardTest`, `StripeConfigTest`, and `LiveStripeTest` (real sandbox, only with a test key).

## Known limits (found while commenting, not changed)
- `RequestValidationException` is not thrown anywhere in Payment and `GlobalExceptionHandler` has no handler for it (copied from the other services).
- `AuditLogRepository` Javadoc still says "admin" and `CREATE/UPDATE/DELETE`; Payment writes `CREATE`, `INTENT`, `CANCEL`, `INVALIDATE`, `RESTART`, `TOMBSTONE`, `SUCCEEDED`, `REQUEST`, `FAILED`.
- `RequestLogContext` removes the `userId` key but Payment never sets it (no user token reaches this service).
- `RefundService.attempt` reads the payment with an unlocked `findByBooking`; safe because a succeeded payment never changes, but it relies on that.
- A refund stuck in `PENDING` because Stripe stays in `requires_action` is only finished by a webhook; there is no polling of Stripe.
- `OutboxRepository.markFailed` leaves a refused `REFUND_STATUS` message without any alert beyond an error log line.
