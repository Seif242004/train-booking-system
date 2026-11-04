# 4. Booking & Seat service

## Purpose
Booking & Seat is the **heart** of the system. It decides who may sit where, and it is the only service that changes the state of a booking.
It owns:

- the **copy of every trip** (stops, coaches, seats, standing capacity, fares) that Train & Schedule pushes to it (the "snapshot");
- **segment availability**: which seats and standing places are free for a journey;
- the **5-minute hold** (seats picked but not yet paid), the **sweeper** that releases expired holds, and the **live seat updates**;
- **bookings**: confirmation after payment, cancellation by the passenger, by an admin, and for a whole trip;
- counter and complimentary bookings made by an admin without Stripe;
- the **outbox** that calls Ticket, Notification and Payment (refund) later, with retries;
- the read side for "My bookings", admin support search, and the live trips board.

- Port **8082**. Oracle schema (database user) `booking_seat`. Spring Boot, Spring MVC, Spring JDBC (`JdbcClient`, no JPA), Spring WebSocket (STOMP), Flyway.
- Called by the **Gateway** with the user's Keycloak JWT; by **Train & Schedule** (snapshot) and **Payment** (payment result, refund status) with the shared internal API key.
- Calls **Payment** (create, invalidate and cancel a payment, request a refund), **Ticket**, **Notification** and **Train & Schedule** (mark a trip cancelled), all
  over REST with the internal API key.

## Folder structure
Feature first, then layer. Some small features (`live`, `payment`, `ticket`, `notification`, `schedule`) are flat because they hold only a port and its adapter.

```
services/booking-seat/
  build.gradle, README.md
  src/main/resources/application.properties
  src/main/resources/db/migration/V1..V8 (Flyway; never edit an applied one)
  src/main/java/com/trainbooking/bookingseat/
    BookingSeatApplication.java
    audit/ common/ security/            cross-cutting code
    snapshot/        receiver of the trip copy       (controller, service, repository, model)
    availability/    the full-journey rule           (controller, service, repository, model)
    booking/         holds, payment start, confirmation, expiry, cancellation, issue, queries
    board/           the admin live trips board
    tripcancel/      cancelling a whole trip
    outbox/          queue of calls to Ticket, Notification, Payment
    live/            WebSocket (STOMP) live updates
    payment/ ticket/ notification/ schedule/   ports (interfaces) and HTTP clients of other services
  src/test/java/...  the same packages, plus testsupport/ (fakes, base class, fixtures)
```

## Every file, one line each
### Root, audit, common, security
| File | What it does |
|---|---|
| `BookingSeatApplication` | Entry point; `@SpringBootApplication` scans the packages and configures Spring. |
| `audit/AuditLogRepository` | One `INSERT` into `audit_log`, inside the caller's transaction. |
| `audit/AuditTrail` | Writes the audit row, and the structured `audit` log line **after the commit** (with actor, booking id, trip id). |
| `common/ApiError` | The one JSON error shape `{ error: { code, message, details? } }`. |
| `common/GlobalExceptionHandler` | Maps every exception to a status and a stable code (400, 404, 409, 410, 503, 500). |
| `common/FieldProblems` | One message per field ("is required" wins); maps `coachClass` to the JSON name `class`. |
| `common/RequestValidationException` | A 400 with a list of field problems. |
| `common/RemoteServiceException` | A call to Ticket, Notification or Train & Schedule gave no usable answer; the caller retries. |
| `common/TripNotFoundException`, `common/CoachClass` | 404 `trip_not_found`; the enum `FIRST/SECOND/THIRD`. |
| `common/TimeConfig` | The `Clock` bean (UTC); tests replace it with a `MutableClock`. |
| `common/RequestLogContext` | Filter: user id, booking id, trip id into the log context; one `access` line per request (path only). |
| `security/SecurityConfig` | Two filter chains: `/internal/**` (API key) and the user chain (JWT, `/admin/**` needs ADMIN, `/ws/**` open at filter level). |
| `security/InternalApiKeyFilter` | Constant-time check of `X-Internal-Api-Key`; grants `ROLE_INTERNAL`. Not a bean. |
| `security/JsonErrorHandlers` | 401 and 403 answers in the shared JSON shape. |
| `security/KeycloakRealmRolesConverter` | `realm_access.roles` becomes `ROLE_USER` / `ROLE_ADMIN`. |

### Snapshot receiver and availability
| File | What it does |
|---|---|
| `snapshot/controller/SnapshotController` | `PUT /internal/trips/{id}/snapshot` and `DELETE /internal/trips/{id}`. |
| `snapshot/service/SnapshotService` | Applies a snapshot in one transaction: lock the trip, freeze rules, FR-A9 capacity checks, `MERGE`, audit. |
| `snapshot/service/SnapshotValidator` | The cross-field rules of a snapshot (stops in order, seats in coaches, fare for every segment and class). |
| `snapshot/repository/SnapshotRepository` | All the SQL of the trip copy: `MERGE`, deleting rows that are gone, locking, counts. |
| `snapshot/model/TripSnapshot`, `SnapshotResult`, `SnapshotConflictException` | The validated body, the answer, the 409 with a code. |
| `availability/controller/AvailabilityController` | `GET /trips/{id}/availability` (full or summary view). |
| `availability/service/AvailabilityService` | One read-only transaction: version first, then data, then the rule. |
| `availability/service/SegmentAvailability` | **The full-journey rule**, plain Java: seat free on every segment; standing = minimum over segments. |
| `availability/service/JourneyFares` | The price of a journey per class (sum of the segments' fares). |
| `availability/repository/AvailabilityRepository` | Read queries: trip header, seats, reservations of the journey, version, fares, standing counters. |
| `availability/model/*` | `Journey`, `AvailabilityResponse`, `JourneyAvailability`, `TripAvailability`, `SeatState`, `SeatStatus`, `SeatInfo`, `ReservationInfo`, `StandingInfo`, `CoachStandingLeft`, `FareInfo`, `TripHeader`. |

### Booking (holds, payment, confirmation, expiry, cancellation, issue)
| File | What it does |
|---|---|
| `booking/controller/BookingController` | Passenger endpoints: pick, release, release all, payment, my bookings, cancel, resend ticket, get. |
| `booking/controller/AdminBookingController` | Admin endpoints: search, details, passenger lookup, issue, refunded-at-counter, cancel. |
| `booking/controller/InternalPaymentController` | What Payment reports: payment result and refund status. |
| `booking/service/HoldService` | Pick and release, each its own all-or-nothing transaction; the 5-minute rule. |
| `booking/service/HoldReleaseService` | Gives a whole hold back (expired, trip cancelled, or abandoned) in one transaction. |
| `booking/service/ExpiryService` | The expiry of one hold: cancel the payment first, then release; payment wins; backoff on outages. |
| `booking/service/HoldSweeper` | The `@Scheduled` job (every 1 s) that calls `ExpiryService.sweep`. |
| `booking/service/PaymentStartService` | "Continue to payment": re-check the hold, set `payment_started`, then ask Payment (outside a transaction). |
| `booking/service/PaymentResultService` | Receives Payment's reports: confirm (amount must match) or ask for a refund; mirrors the refund status. |
| `booking/service/ConfirmationService` | **The only place** where a hold becomes CONFIRMED. |
| `booking/service/CancellationService` | One cancellation for passenger, admin and whole trip. |
| `booking/service/BookingIssueService` | Counter and complimentary bookings (an admin confirms their own hold without Stripe). |
| `booking/service/BookingInventory` | Gives seats and standing places back (shared by release and cancellation). |
| `booking/service/BookingLocks` | The one lock order: trip row, then booking row. |
| `booking/service/BookingPricing`, `BookingReferences` | Server-side pricing of lines; random references like `TB-7K2M9Q`. |
| `booking/service/BookingQueryService`, `AdminBookingQueryService` | Read side: own bookings, support search and details. |
| `booking/service/TicketResendService` | Queues a ticket e-mail again, once at a time. |
| `booking/repository/BookingRepository` | SQL of `booking` and `booking_item`, passenger lookup, search. |
| `booking/repository/InventoryRepository` | SQL of the inventory: reservations, standing counters and holds, trip lock, version counter. |
| `booking/model/*` | `Booking`, `BookingView`, `BookingItemView`, `BookingStatus`, `RefundStatus`, `PaymentMethod`, `PickRequest`, `ReleaseRequest`, `CancelRequest`, `IssueRequest`, `AdminBookingView`, `MyBooking`, `PaymentStart`, `AuthenticatedUser`, and the exceptions (`BookingConflictException`, `BookingNotFoundException`, `HoldConflictException`, `HoldExpiredException`, `TripClosedException`, `StandingUnavailableException`, `PaymentStartedMeanwhileException`, `PassengerNotFoundException`). |

### Board, trip cancellation, outbox, live updates, ports
| File | What it does |
|---|---|
| `board/controller/BoardController`, `board/service/BoardService` | The admin board: one row per trip with seat counts, state, FULL flag; detail with every seat. |
| `board/service/TripLifecycle`, `StandingBoardCounts` | Pure rules: UPCOMING / DEPARTED / ARRIVED / CANCELLED, and confirmed / held / free for standing places. |
| `board/repository/BoardRepository` | Aggregate SQL (counts per class, seat states with the reference). |
| `board/model/*` | `BoardResponse`, `BoardTrip`, `BoardTripDetail`, `BoardSeat`, `BoardSummary`, `SeatCounts`, `TripState`, `BoardCoachStanding`, `BoardRangeException`. |
| `tripcancel/controller/AdminTripController` | `POST /admin/trips/{id}/cancel`, `GET /admin/trips/{id}/cancellation`. |
| `tripcancel/service/TripCancellationService` | The idempotent passes of a whole-trip cancel. |
| `tripcancel/service/TripCancellationWorker` | `@Scheduled` (5 s) retry of every CANCELLING trip. |
| `tripcancel/repository/TripCancellationRepository` | Status columns of `trip` and booking counts. |
| `outbox/service/OutboxWriter`, `OutboxRepository`, `OutboxMessage` | Queue a job in the caller's transaction; the SQL; the record. |
| `outbox/service/OutboxDispatcher`, `OutboxScheduler`, `OutboxJob` | Run due jobs outside transactions with backoff (every 1 s). |
| `outbox/service/TicketEmailJob` | Issue the ticket, read the PDF, e-mail it. |
| `outbox/service/CancellationJobs` | Invalidate ticket, request refund, cancellation e-mail, refund e-mail. |
| `live/WebSocketConfig`, `HandshakeAuthentication`, `SubscriptionGuard` | The `/ws` endpoint, token check at the handshake, allowed destinations. |
| `live/AvailabilityEvents`, `LiveUpdates`, `SeatEvent`, `UserEvent` | Publish inside the transaction; deliver after the commit. |
| `payment/PaymentPort`, `RefundPort`, `PaymentClient`, 2 exceptions | What Booking needs from Payment, and the HTTP client. |
| `ticket/TicketPort`, `TicketClient` | Same for Ticket. |
| `notification/NotificationPort`, `NotificationClient` | Same for Notification. |
| `schedule/TrainSchedulePort`, `TrainScheduleClient` | Same for Train & Schedule (mark a trip cancelled). |

### Tests (`src/test/java/...`)
| File | What it does |
|---|---|
| `testsupport/DatabaseTest` | Base class: starts the whole app on the throwaway schema `booking_seat_test`, refuses other schemas, empties all tables before each test, builds the fixture trip. |
| `testsupport/DatabaseTestConfig`, `MutableClock`, `FakePaymentPort`, `FakeTicketPort`, `FakeNotificationPort`, `FakeTrainSchedulePort`, `CommittedEvents`, `Holds` | Test doubles: movable clock, fakes of the other services, collector of committed events, helper that makes a selection pick by pick. |
| `testsupport/RequiresTestDatabase`, `TestDatabase`, `TestJwts`, `Snapshots` | Skip rule when no test password, connection settings, mock JWTs, valid snapshot builder. |
| `availability/service/SegmentAvailabilityTest` | The full-journey rule without a database. |
| `availability/AvailabilityDatabaseTest`, `AvailabilityControllerTest`, `AvailabilityServiceTest`, `JourneyFaresTest` | Queries and constraints, HTTP contract, version-before-data order, pricing. |
| `snapshot/SnapshotDatabaseTest`, `SnapshotControllerTest`, `SnapshotValidatorTest` | The receiver on Oracle (rollback, freeze rules, FR-A9), HTTP contract, cross-field rules. |
| `booking/PickDatabaseTest`, `ConcurrencyDatabaseTest` | Picks, releases, `ORA-00001` races, standing places under load. |
| `booking/ExpiryDatabaseTest`, `PaymentRaceDatabaseTest`, `PaymentFlowDatabaseTest`, `SweeperWiringDatabaseTest`, `HoldSweeperTest` | Expiry, payment at the last second, payment page opened mid-change, the real schedule. |
| `booking/CancellationDatabaseTest`, `CancellationAtomicityDatabaseTest`, `IssueDatabaseTest`, `TicketEmailDatabaseTest`, `AdminBookingSearchDatabaseTest`, `BookingStopTimesDatabaseTest`, `BookingPricingTest` | Cancellation, all-or-nothing, counter/complimentary, ticket e-mail job, support search, time zone, pricing. |
| `booking/controller/BookingControllerTest`, `AdminBookingControllerTest`, `booking/model/HoldRequest` | HTTP contracts; a test-only description of a selection. |
| `board/BoardDatabaseTest`, `live/WebSocketDatabaseTest`, `tripcancel/*`, `audit/AuditTrailLogTest`, `payment/PaymentClientTest`, `ticket/TicketClientTest`, `notification/NotificationClientTest` | Board counting rule, real STOMP client, whole-trip cancel, audit log line, the three HTTP clients against a local fake server. |

## Endpoints
All JSON. Errors have the shape `{ "error": { "code", "message", "details"? } }`. "Owner" means the caller's JWT `sub` equals the booking's `user_id`; another user's booking answers 404.

| Method and path | Auth | Request | Response | Errors |
|---|---|---|---|---|
| `GET /trips/{id}/availability?from=&to=[&view=summary]` | any signed-in user (Gateway only) | stop indexes | totals per class, `fares`, `version`, `myHold`, per-seat states, standing per coach (summary view: only totals) | 400, 404 `trip_not_found` |
| `POST /bookings/pick` | any signed-in user | `{ tripId, fromStop, toStop, seatId }` or `{ ..., coachId, qty }` | 201 booking (first pick, timer starts) or 200 (joins the hold) | 400, 404, 409 `seats_unavailable` / `trip_closed` / `trip_departed` / `hold_for_another_journey` / `hold_too_large` / `payment_in_progress` / `already_paid`, 410 `hold_expired` |
| `POST /bookings/{id}/release` | owner | `{ seatId }` or `{ coachId, qty }` | booking (ABANDONED if it was the last item) | 404, 409 `not_picked` / `not_a_hold` / `payment_in_progress`, 410 |
| `DELETE /bookings/{id}/hold` | owner | none | booking (ABANDONED) | 404, 409, 410 |
| `POST /bookings/{id}/payment` | owner | none | `{ clientSecret, publishableKey, amount, currency, expiresAt, serverTime }` | 404, 409 `already_paid` / `not_a_hold` / payment codes, 410, 503 `payment_unavailable` / `stripe_not_configured` |
| `GET /bookings` | any signed-in user | none | `[ { booking, cancellable } ]`, newest first, at most 50 | 401 |
| `GET /bookings/{id}` | owner | none | booking | 404 |
| `POST /bookings/{id}/cancel` | owner | none | booking CANCELLED | 404, 409 `not_cancellable` / `trip_departed` / `cancel_at_desk` / `busy` |
| `POST /bookings/{id}/resend-ticket` | owner | none | 202 `{ queued }` | 404, 409 `not_confirmed` |
| `GET /admin/bookings?ref=&email=&tripId=&status=&refundStatus=&method=` | ADMIN | filters | at most 100 `AdminBookingView`, newest first | 400 (bad enum) |
| `GET /admin/bookings/{id}` | ADMIN | none | `AdminBookingView` | 404 |
| `GET /admin/passengers/lookup?q=` | ADMIN | exact e-mail | `{ id, name, email }` | 404 `passenger_not_found` |
| `POST /admin/bookings/{id}/issue` | ADMIN | `{ method: COUNTER or COMPLIMENTARY, note?, reason?, passenger: { mode: ACCOUNT or WALK_IN, email?, name?, phone? } }` | booking CONFIRMED | 400, 404, 409 `hold_expired` / `payment_started` / `not_issuable` / `busy` |
| `POST /admin/bookings/{id}/refunded-at-counter` | ADMIN | none | booking | 404, 409 `not_refund_due` |
| `POST /admin/bookings/{id}/cancel` | ADMIN | `{ reason }` (required, 500) | booking CANCELLED | 400, 404, 409 |
| `GET /admin/board/trips?from=&to=&state=` | ADMIN | dates `yyyy-MM-dd`, states list | `{ generatedAt, from, to, summary, trips }` | 400 |
| `GET /admin/board/trips/{id}` | ADMIN | none | trip, every seat (`FREE/HELD/TAKEN`), standing per coach | 404 |
| `POST /admin/trips/{id}/cancel` | ADMIN | `{ reason }` | `TripCancellationStatus` | 400, 404, 409 `trip_departed` |
| `GET /admin/trips/{id}/cancellation` | ADMIN | none | status, counts | 404 |
| `PUT /internal/trips/{id}/snapshot` | internal key | `TripSnapshot` (+ header `X-Acting-User`) | `SnapshotResult` | 400, 401 `invalid_api_key`, 409 `trip_not_open` / `trip_has_reservations` / `capacity_conflict` |
| `DELETE /internal/trips/{id}` | internal key | none | 204 | 409 `trip_has_reservations` |
| `POST /internal/bookings/{id}/payment-result` | internal key | `{ paymentId?, amount, currency: "EGP" }` | `{ result: CONFIRMED or REFUND_REQUIRED }` | 400, 404 |
| `POST /internal/bookings/{id}/refund-status` | internal key | `{ status: REFUND_PENDING, REFUNDED or REFUND_FAILED }` | `{ status }` | 400, 404 |
| WebSocket `/ws` (STOMP) | JWT in subprotocol `access_token.<jwt>` | subscribe to `/topic/trips/{id}` or `/user/queue/holds` | `SeatEvent` / `UserEvent` frames | 401 at the handshake |

Events: `SEAT_HELD`, `SEAT_RELEASED`, `SEAT_BOOKED`, `STANDING_CHANGED` (with `coachId`, `delta`), `TRIP_CANCELLED` (all with `tripId`, `segments`, `version`), and for one user `HOLD_EXPIRED` with reason `EXPIRED`, `TRIP_CANCELLED` or `RELEASED`.

## Tables (Flyway V1 to V8)
| Table | Columns and constraints |
|---|---|
| `trip` (V1, V7) | `id` (the Train & Schedule id, not generated), `departure_date`, `train_number/name/type`, `route_name`, `status` (`OPEN/CANCELLING/CANCELLED`), `updated_at`; V7 adds `cancel_reason`, `cancelled_by`, `cancel_requested_at`, `tns_marked` (0/1: Train & Schedule has been told). |
| `trip_stop` (V1) | PK `(trip_id, stop_index)`, `station_id`, `station_code`, `station_name`, `arrival_time`, `departure_time` (`TIMESTAMP WITH TIME ZONE`); cascade with the trip. |
| `trip_coach` (V1) | PK `(trip_id, coach_id)`, `code`, `class`, `position`. The standing capacity is **not** here. |
| `trip_seat` (V1) | PK `(trip_id, seat_id)`, `coach_id`, `seat_number`, `seat_row`, `seat_column`, `class`; FK to the coach. |
| `trip_fare` (V1) | PK `(trip_id, segment_index, class)`, `amount > 0`, `currency`. |
| `seat_reservation` (V1, V2) | **PK `(trip_id, seat_id, segment_index)`** (double booking is impossible: `ORA-00001`), `booking_id` (FK, V2), `expires_at` (NULL = confirmed). FK to `trip_seat`, so a reserved seat cannot be removed (`ORA-02292`). |
| `standing_usage` (V1) | PK `(trip_id, coach_id, segment_index)`, `capacity` (the only copy), `used`; `CHECK used <= capacity`, `CHECK used >= 0`. |
| `audit_log` (V1) | `id`, `admin_id`, `entity`, `entity_id`, `action`, `diff` (CLOB), `created_at`. |
| `booking` (V2, V4, V8) | `id`, `reference` (unique), `user_id` (Keycloak `sub`), `user_email`, `user_name`, `trip_id` (FK), `from_stop`, `to_stop`, `status`, `refund_status`, `amount`, `currency`, `expires_at`, `created_at`, `confirmed_at`, `cancelled_at`, `cancelled_by`, `cancelled_by_role`, `cancel_reason`, `next_attempt_at`, `attempts`; V4 `payment_started`; V8 `payment_method`, `charged_amount`, `issued_by`, `issued_at`, `issue_note`, `walk_in_name/phone/email`, `counter_refunded_by/at`. Indexes by user, trip+status and (status, expires_at). **V4 unique index `uk_booking_active_hold`**: one `PENDING_PAYMENT` booking per user and trip. |
| `booking_item` (V2, V3) | `id`, `booking_id`, `item_type` (`SEAT/STANDING`), `seat_id` or `coach_id`, `qty`, `price`, and (V3) `seat_number`, `coach_code`, `coach_class` copied so history stays readable. |
| `standing_hold` (V2) | PK `(booking_id, coach_id)`, `trip_id`, `from_segment`, `to_segment` (inclusive), `qty`: what to give back. |
| `trip_version` (V2) | `trip_id`, `version`: the per-trip event counter. |
| `outbox` (V5, V6) | `id`, `kind` (`TICKET_EMAIL/TICKET_INVALIDATE/REFUND_REQUEST/CANCELLATION_EMAIL/REFUND_EMAIL`), `booking_id`, `payload`, `status` (`PENDING/SENT/FAILED`), `attempts`, `next_attempt_at`, `last_error`, `created_at`, `sent_at`. |

## Background jobs
| Job | Class | Every | Does |
|---|---|---|---|
| Sweeper | `HoldSweeper` | 1 s after the previous run ends (`fixedDelay`) | `ExpiryService.sweep`: releases holds past `expires_at`. |
| Outbox dispatcher | `OutboxScheduler` | 1 s | `OutboxDispatcher.dispatchDue`: up to 20 due jobs. |
| Trip cancel worker | `TripCancellationWorker` | 5 s | One pass over each CANCELLING trip. |

All three can be switched off by a property (used by the tests).

## Configuration
`application.properties` (names only; values come from the environment or a git-ignored `.env`):

| Property / variable | Meaning |
|---|---|
| `server.port` | 8082. |
| `BOOKING_SEAT_DB_URL`, `BOOKING_SEAT_DB_USER`, `BOOKING_SEAT_DB_PASSWORD` | Oracle connection of the own schema (Flyway uses the same user). |
| `KEYCLOAK_ISSUER` | Expected token issuer; signing keys are fetched from it on the first request. |
| `CORS_ORIGIN` | The one origin allowed to open the WebSocket. |
| `INTERNAL_API_KEY` | Shared service-to-service key. **No default**; the service does not start without it. |
| `PAYMENT_URL`, `TICKET_URL`, `NOTIFICATION_URL`, `TRAIN_SCHEDULE_URL` | Where the other services run. Read timeouts: `trainbooking.payment/ticket/notification/train-schedule.read-timeout-ms`. |
| `TIMEZONE` | Railway time zone (default `Africa/Cairo`): stop times in answers carry its offset. |
| `trainbooking.sweeper.enabled`, `.fixed-delay-ms` | Sweeper switch and delay (default 1000). |
| `trainbooking.outbox.enabled`, `.fixed-delay-ms` | Outbox switch and delay (default 1000). |
| `trainbooking.tripcancel.enabled`, `.fixed-delay-ms` | Whole-trip worker switch and delay (default 5000). |
| `BOOKING_SEAT_TEST_DB_PASSWORD` (tests) | Password of the throwaway schema; without it the database tests are skipped. |

## Important code paths, step by step
### A. Picking a seat (Flow 3), `HoldService.pick`
1. `BookingController.pick` validates the body and takes the caller from the JWT (`AuthenticatedUser.of`). The token must carry an e-mail.
2. `prepare` (no lock): exactly one of seat or coach; the trip exists and is `OPEN`; the journey fits the trip; the seat or coach is on this trip; the price comes from the fares of the trip copy (`BookingPricing`).
3. If the user's active hold already had `payment_started`, `settlePaymentBefore` asks Payment **first** to invalidate the open payment (outside any transaction). `SUCCEEDED` becomes 409 `already_paid`, `PROCESSING` 409 `payment_in_progress`.
4. `pickInTransaction` (one transaction): lock the trip row (`FOR UPDATE`); refuse if the trip is not `OPEN` or the boarding stop has left; lock the user's active hold row.
   - **No hold yet:** insert the booking with `expires_at = now + 5 minutes`. **Hold exists:** it must not be over (410), must be for the same journey (409), and `expires_at` is never changed.
   - Seat: `INSERT` one `seat_reservation` row per segment (sorted). If any row exists, Oracle raises `ORA-00001` and the **whole pick** rolls back. Standing: `UPDATE standing_usage SET used = used + qty WHERE ... AND used + qty <= capacity` per segment in ascending order; a row count of 0 means "gone".
   - Recompute the booking amount from its lines, publish `SEAT_HELD` / `STANDING_CHANGED` (takes the next `trip_version`, delivered **after** the commit), write the audit row.
5. A `DuplicateKeyException`, `StandingUnavailableException` or lock timeout becomes 409 `seats_unavailable` for **that pick only**; earlier picks stay held.

### B. The sweeper and the 5-minute rule (Flow 5), `HoldSweeper` -> `ExpiryService`
1. Every second `findDueHoldIds` returns `PENDING_PAYMENT` bookings with `expires_at <= now` whose `next_attempt_at` is empty or due.
2. For each, outside any transaction: if `payment_started`, ask Payment to `cancelPending` **first**. `NONE`/`CANCELLED`: call `HoldReleaseService.release` (lock trip and booking, re-check, give seats back, set `EXPIRED`, send `HOLD_EXPIRED` to the user after commit).
   `SUCCEEDED`/`PROCESSING`: **keep the seats**, retry in 1 s; Payment's own report will confirm the booking (the sweeper never confirms by itself). Payment down: backoff 2, 4, ... 60 s in `next_attempt_at`.
3. A hold whose owner never opened the payment page is released without calling Payment, so a Payment outage cannot block seats. The release re-checks `payment_started` under the lock; if the page was opened meanwhile it throws `PaymentStartedMeanwhileException` and the next sweep asks Payment.
4. Until released, an expired hold keeps blocking its seat and the availability query shows it as **held**, never free (that query never reads the clock).

### C. Payment wins, `PaymentResultService.paymentSucceeded` -> `ConfirmationService`
1. Payment reports `{ amount }`. If the booking is still a hold and the amount differs from the booking's, the hold is ended and the answer is `REFUND_REQUIRED` (Payment refunds).
2. Otherwise `ConfirmationService.confirm`: lock trip and booking; if `PENDING_PAYMENT` set `expires_at` to NULL on its reservations, set `CONFIRMED`, publish `SEAT_BOOKED`, queue `TICKET_EMAIL` in the outbox, audit. This works **even if the five minutes have just run out**, as long as the hold was not released yet.
3. A booking that is already `EXPIRED`, `ABANDONED` or `CANCELLED` returns `NOT_CONFIRMABLE`, so the report answers `REFUND_REQUIRED`. A repeated report is harmless (`ALREADY_CONFIRMED` answers `CONFIRMED`).
4. The counter and complimentary path (`BookingIssueService`) uses the same `confirmLocked`, with no Stripe: it records `payment_method`, who issued it and what was charged.

### D. Cancellation (Flow 6), `CancellationService`
1. One transaction under `BookingLocks`: owner check (404 for another user), already `CANCELLED` returns as is, a passenger cannot cancel a desk-issued booking, only `CONFIRMED` can be cancelled, the cutoff (departure of the booking's own boarding stop still ahead) gives 409 `trip_departed`.
2. `BookingInventory.giveBack`, then `markCancelled` with `cancelled_by`, `cancelled_by_role` and (admin only) `cancel_reason`; `refund_status` is `REFUND_PENDING` (card), `MANUAL_REFUND_DUE` (counter) or `NOT_APPLICABLE` (complimentary).
3. Queue in the outbox, in this order: `TICKET_INVALIDATE`, `REFUND_REQUEST` (card only), `CANCELLATION_EMAIL`; audit.
4. Later, `OutboxDispatcher` runs them: invalidate the ticket, ask Payment for the full refund (never gives up; flagged `REFUND_FAILED` after 8 failed attempts so support sees it), e-mail the passenger. Payment's `REFUNDED` report queues `REFUND_EMAIL` once.

### E. The outbox, `OutboxWriter` -> `OutboxDispatcher`
A job row is written **in the same transaction** as the change, so they stand or fall together. The dispatcher runs jobs outside transactions; success marks `SENT`, failure reschedules with `2^attempt` seconds (max 300), after 300 attempts `FAILED` (not for refunds). Order is kept per booking only where it matters (ticket issue before invalidate).

### F. Whole-trip cancel (Flow 8), `TripCancellationService`
`request` locks the trip and sets `CANCELLING` (closed to new picks at once). Each `process` pass: tell Train & Schedule, cancel each hold's payment then release it, cancel each confirmed booking without the cutoff, and when nothing is left mark the trip `CANCELLED` and push `TRIP_CANCELLED`. The worker repeats it every 5 s.

### G. The snapshot, `SnapshotService.apply`
Lock the trip row; refuse a non-`OPEN` trip; if reservations or paid bookings exist, refuse changes to date, train, stops, times and fares and (with reservations) capacity losses; then `MERGE` trip, stops, coaches, seats, standing counters and fares in foreign-key order; audit. Any failure rolls back, and Train & Schedule rolls its own change back.

### H. Live updates
`AvailabilityEvents` publishes inside the transaction; `LiveUpdates` (`@TransactionalEventListener(AFTER_COMMIT)`) sends to `/topic/trips/{id}` or `/user/queue/holds`. The handshake checks the JWT from the subprotocol; `SubscriptionGuard` allows only two kinds of SUBSCRIBE and refuses SEND.

## Tests
`./gradlew :services:booking-seat:test`: **336 tests, all pass, none skipped** at the time of this stage (the throwaway schema was available). Most tests run the whole application against the throwaway Oracle schema `booking_seat_test` (see `testsupport/DatabaseTest`);
the rest are unit tests (the rule, the validator) and web-layer tests with mocks. Without `BOOKING_SEAT_TEST_DB_PASSWORD` the database tests are reported as SKIPPED.

## Known limits
See also chapter 13. For this service:
- `AdminBookingQueryService.toAdminView` and `BookingQueryService.toView` read the trip info and items with separate queries per booking (and two queries per stop): up to 100 bookings in a search and 50 in "My bookings" cost hundreds of small queries.
- The support search uses `UPPER(reference) = :ref`, which cannot use the unique index on `reference`.
- `BookingRepository.findPassengersByEmail` knows only users who already have a booking, and excludes the literal user id `demo-passenger`.
- The STOMP broker is in memory: with two instances of this service, a client would only get the events of the instance it is connected to.
- `OutboxDispatcher` runs jobs one after another in one thread (20 per tick). A slow Ticket or Notification (10 s read timeout) delays every other job.
- A late-paid hold on a `CANCELLING` trip is confirmed and then refunded (by the next pass) instead of being refused when the user clicks "Continue to payment".
- `HoldService.pick` maps a lock timeout (`PessimisticLockingFailureException`) to "seats unavailable", which can be a slightly misleading message for a busy database.
- `ExpiryService.retryLater` only logs an error from the 5th attempt; a hold whose release keeps failing stays held until someone looks at the log.
- `JsonErrorHandlers.handle` always says "The ADMIN role is required.", also if the 403 comes from another rule.
- `V1__create_snapshot_and_reservation_tables.sql` still contains a `TODO(slice 7)` comment that is done (migrations are never edited, because Flyway checks their checksum).
- Test smell: `FakeTicketPort.issue` has a redundant `!issueCalls.isEmpty()` test (it is always true after the add).
