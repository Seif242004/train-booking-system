# 1. Architecture overview

This chapter explains how the pieces fit together. The authoritative design is `docs/ARCHITECTURE.md` (the diagram and the flows) and `docs/SRS.md`
(requirements). This chapter is the map to read before the per-service chapters.

## The boxes

```
Browser (React, port 5173)
   |  login / registration (PKCE)            |  every API call, with the JWT
   v                                         v
Keycloak (8180) <--- public keys (JWKS) --- API Gateway (Express, 8080)
                                              |                      |
                      +-----------------------+                      +----------------------+
                      v                                                                     v
         Train & Schedule (8081)  <--- snapshot push ---  Booking & Seat (8082) --- WebSocket (STOMP) ---> browser (through the Gateway)
         stations, trains, routes, trips                  holds, bookings, sweeper,
                                                          cancellation, board
                                                             |            |             |
                                                             v            v             v
                                                       Payment (8083)  Ticket (8084)  Notification (8085)
                                                             |                              |
                                                             v                              v
                                                          Stripe                     SMTP (Mailpit in dev)

Oracle (1521): one schema (database user) per service.   OpenObserve (5080): logs and traces of every service.
```

| Box | Technology | Owns |
|---|---|---|
| Web app | React, Vite, TypeScript (served by nginx) | screens only; never trusted for prices or payment results |
| Keycloak | Keycloak on Oracle | users, passwords, roles `USER` and `ADMIN`, JWTs |
| Gateway | Node.js, Express | verifies the JWT, checks the role, routes, composes two answers (search, seat map) plus the board detail, proxies WebSocket and the Stripe webhook |
| Train & Schedule | Spring Boot | stations, trains, coaches, seats, routes, fares, trips, trip search, trip layout |
| Booking & Seat | Spring Boot | its own copy of the trips (snapshot), segment availability, holds, bookings, confirmation, cancellation, sweeper, live updates, the admin board, whole-trip cancellation |
| Payment | Spring Boot | Stripe payments, webhook verification, refunds |
| Ticket | Spring Boot | the PDF ticket with its QR code, invalidation |
| Notification | Spring Boot | emails with retries |

## Who calls whom

- The browser talks to the Gateway only (and to Keycloak directly for login). The Gateway has no Keycloak proxy; it only fetches Keycloak's public keys (JWKS) and caches them.
- Gateway to Train & Schedule and to Booking & Seat. The Gateway forwards the user's JWT; each Spring service validates it again as an OAuth2 resource server.
- Train & Schedule to Booking & Seat: pushes the **trip snapshot** (`PUT /internal/trips/{id}/snapshot`) when a trip is created or edited.
- Booking & Seat to Payment, Ticket, Notification and Train & Schedule (mark a trip cancelled). Payment to Booking & Seat (payment result, refund status).
- Payment to Stripe. Notification to SMTP.
- **Service-to-service calls** carry a shared secret in the header `X-Internal-Api-Key` (paths `/internal/**`). The Gateway never forwards that header and never
  routes `/internal/**`. The only entry without a login is the Stripe webhook, which is authenticated by Stripe's signature.
- A service never reads another service's tables. The user is identified by the JWT `sub` as a plain value, never as a foreign key.

## Data ownership

Each service has its own Oracle schema and its own Flyway migrations (`src/main/resources/db/migration`). Booking & Seat keeps **its own copy** of seats and
standing capacity (tables `trip`, `trip_stop`, `trip_coach`, `trip_seat`, `trip_fare`), so availability never needs a join into Train & Schedule.
A copy must be kept current: that is the snapshot push, and the re-publish when trains, coaches, seats, fares, routes or stations change
(`TripSnapshotRefresher` in Train & Schedule).

## The patterns used everywhere

- **Layers** inside a Spring service: controller (HTTP only), service (rules), repository (SQL with `JdbcClient`, no JPA). Packages are feature first, then layer.
- **Database-enforced rules.** `seat_reservation` has the primary key `(trip_id, seat_id, segment_index)`; a second insert fails with `ORA-00001`, which the code
  reads as "seat taken". Standing places use conditional `UPDATE`s (check the updated row count) and a `CHECK used <= capacity`.
- **One lock order**: trip row, then booking row, then seat or standing rows, then the version counter (`BookingLocks`). It avoids deadlocks.
- **Full-journey availability**: a journey from stop i to stop j uses segments i..j-1; a seat is available only if it is free on every one
  (`availability/service/SegmentAvailability`).
- **No call to another service or to Stripe inside an open DB transaction.** The code decides in a short transaction, calls outside, then records in a second
  short transaction that re-checks.
- **Outbox plus retry poller** instead of a message broker. A row in an `outbox` table is written in the same transaction as the state change; a `@Scheduled` job
  delivers it with a backoff. Used by Booking (ticket and email, ticket invalidation, refund request, cancellation email), Payment (payment result and refund
  status to Booking) and Notification (its `email` table is the outbox).
- **Idempotency**: calls that may be repeated are safe to repeat (`PUT` of a ticket, `MERGE` of a snapshot, the Stripe idempotency key, the webhook event id as a
  primary key, the `dedupeKey` of emails).
- **Events after commit**: live updates are published inside the transaction but delivered only by `@TransactionalEventListener(AFTER_COMMIT)`.
- **Audit**: every admin action and booking state change writes an `audit_log` row (same transaction) and a log line from the logger `audit` (after commit),
  shipped to OpenObserve.
- **Server-side money**: amounts are always computed from the held seats and the stored fares, never taken from the client.

## Booking states

`PENDING_PAYMENT` (the hold) leads to `CONFIRMED` (paid by card, or issued by an admin), or to `EXPIRED` (timeout, or the trip was cancelled), or to `ABANDONED`
(the user released the last item). `CONFIRMED` leads to `CANCELLED`. The refund state is a separate column `refund_status` (`NONE`, `REFUND_PENDING`, `REFUNDED`,
`REFUND_FAILED`, and for counter bookings `NOT_APPLICABLE`, `MANUAL_REFUND_DUE`, `REFUNDED_AT_COUNTER`). The booking's `payment_method` is `CARD`, `COUNTER` or
`COMPLIMENTARY` (see `docs/design/COUNTER-BOOKING.md`). Booking's copy of a trip is `OPEN`, `CANCELLING` or `CANCELLED`; Train & Schedule's trip is `ACTIVE` or `CANCELLED`.

## Background jobs (all `@Scheduled` with `fixedDelay`)

| Job | Service | Every | Does |
|---|---|---|---|
| `HoldSweeper.sweep` | Booking | 1 s | expires holds past `expires_at` (`ExpiryService`) |
| `OutboxScheduler` calling `OutboxDispatcher.dispatchDue` | Booking | 1 s | ticket and email, ticket invalidation, refund request, cancellation and refund emails |
| `TripCancellationWorker` | Booking | 5 s | continues whole-trip cancellations |
| `OutboxScheduler` (two jobs) | Payment | 1 s | delivers results to Booking (`OutboxDispatcher.dispatchDue`); retries refunds (`RefundRetrier.retryDue`) |
| `EmailScheduler` calling `EmailDispatcher.dispatchDue` | Notification | 1 s | sends the stored emails by SMTP |

## Security in short

Keycloak issues the JWT (authorization code with PKCE). The Gateway (`gateway/src/auth.js`: `authenticate`, `requireAuth`, `requireRole`) verifies signature,
expiry and role; admin paths answer 403 to non-admins. Each Spring service checks the JWT again. Internal paths need the shared key. Stripe's webhook needs a valid
signature. Card data goes from the browser to Stripe (Payment Element) and never touches our servers.
