# Booking & Seat service

Spring Boot service (Java 21, Gradle, Spring MVC: controller → service → repository) that will own segment availability,
the 5-minute holds, bookings, cancellation and the live seat updates (`CLAUDE.md`, "Services"). So far it has the **copy of
the trips** that Train & Schedule pushes, the **inventory tables** and the
**segment availability** of a journey.

## Responsibility

- Stores its data in its own Oracle schema, `booking_seat`. It never reads another service's tables: the seats, coaches,
  stops, standing capacity and fares it needs are its own copy of what Train & Schedule pushes (the snapshot).
- Validates the Keycloak access token itself (OAuth2 resource server) on the user endpoints. The `/internal` endpoints are
  for other services only: they need the shared `INTERNAL_API_KEY` in the `X-Internal-Api-Key` header, a user's JWT is not
  accepted there, and the Gateway never forwards them.
- **Full-journey rule** (SRS 4.1): a journey from stop i to stop j uses segments i to j-1. A seat is available only if it is
  free on **every** one of them; a reservation on any other segment is ignored. The standing places left in a coach are the
  **minimum** of (capacity - used) over those segments (a missing counter row counts as no capacity). The total per class is
  the sum over the coaches of that class, each by its own minimum, because a standing passenger books places in one coach.
  A seat that is held on one segment and booked on another shows as booked. A hold whose time has run out but that the
  sweeper has not released yet is still **held**, never free (the availability query does not look at the clock).
  The rule is plain Java in `SegmentAvailability`, tested without a database.

## Endpoints

| Method | Path | Caller | Purpose |
|---|---|---|---|
| GET | `/trips/{id}/availability?from=&to=` | any signed-in user (the Gateway calls it for the browser) | Availability of the trip for the journey. `from` and `to` are **stop indexes** of the trip (the boarding stop and the destination stop). Returns `tripId`, `tripStatus`, `fromStop`, `toStop`, `summary { seatsLeft, standingLeft }` (counts per class) and, unless `view=summary`, `fares` (the price of the whole journey per class in EGP, the sum of its segments; a class with a missing segment fare is left out), `seats [{ seatId, coachId, state AVAILABLE\|HELD\|BOOKED }]` and `standing [{ coachId, capacity, remaining }]`. 400 for a journey that is not valid for the trip, 404 for an unknown trip |
| PUT | `/internal/trips/{id}/snapshot` | Train & Schedule (API key) | Stores or updates the copy of a trip, all or nothing. Idempotent. Header `X-Acting-User` (optional): the admin whose action caused it, written to the audit log |
| DELETE | `/internal/trips/{id}` | Train & Schedule (API key) | Removes the copy of a deleted trip (204; also 204 if there is none); 409 if the trip has holds, bookings or any booking history |
| POST | `/bookings/{id}/payment` | the owner | **Continue to payment** (Flow 4): while the hold is active (410 `hold_expired` once it ran out, even before the sweeper released it; 409 `already_paid` / `not_a_hold`), sets `payment_started` and asks Payment for a payment of the amount the server worked out. Returns `{ clientSecret, publishableKey, amount, currency, expiresAt, serverTime }` for the embedded Stripe form; the countdown goes on with the remaining time. 409 with the code Payment gave (`payment_closed`...), 503 `payment_unavailable` / `stripe_not_configured` |
| GET | `/bookings` | any signed-in user | **My bookings**: the caller's holds, confirmed and cancelled bookings (and an expired hold that was paid and is being refunded), newest first, at most 50: `[ { booking, cancellable } ]`. `cancellable` is true for a confirmed booking whose train has not yet left the booking's boarding stop |
| POST | `/bookings/{id}/cancel` | the owner | **Cancel my booking** (Flow 6, way A): instant, no approval, refunded in full. 404 for another user's booking, 409 `not_cancellable` (not a confirmed booking), 409 `trip_departed` (the train has left the boarding stop). An already cancelled booking is returned as it is |
| POST | `/admin/bookings/{id}/cancel` | role ADMIN | **Support cancels on the passenger's request** (way B): `{ reason }` (required, at most 500 characters). The same cancellation as the passenger's; records which admin did it and why. 403 for a passenger |
| GET | `/admin/bookings?ref=&email=&tripId=&status=&refundStatus=` | role ADMIN | **Support search** (Flow 7, FR-C6): every filter optional and combined; `ref` exact ignoring case, `email` a case-insensitive part (LIKE characters are literal), `status` and `refundStatus` enum names (400 for another value); `refundStatus=REFUND_FAILED` lists the refunds that need a follow-up. At most 100, newest first. Each result: the booking, the passenger (`userId`, `userEmail`, `userName`), `paymentStatus` PAID / NOT_PAID, `confirmedAt`, who cancelled it (`cancelledAt`, `cancelledBy`, `cancelledByRole`, `cancelReason`) and `cancellable` |
| GET | `/admin/bookings/{id}` | role ADMIN | The same for one booking; 404 for an unknown id |
| GET | `/admin/board/trips?from=&to=&state=` | role ADMIN | **Live trips board** (read-only aggregate, no per-seat data). Per trip: train, route, origin and destination, times, `state` (UPCOMING, DEPARTED, ARRIVED, CANCELLED from the trip's own times and the clock; CANCELLING counts as cancelled), `full` (an upcoming trip with places but none free in any class), counts `{ capacity, confirmed, held, free }` for `first`, `second`, `third`, `standing` and `totals`, and for departed or arrived trips `departedAt`, `totalSeats`, `confirmedAtDeparture`. **Counting rule (full journey):** a seat is *free* only if no segment of it is reserved; *confirmed* if a confirmed booking has it on any segment; *held* if it has no confirmed booking but a running hold on any segment (a hold past its five minutes that the sweeper has not released yet still counts as held). Standing: free = minimum over all segments of capacity minus used, confirmed = most places confirmed bookings use on one segment, held = the rest. `from`/`to` are local dates (inclusive, at most 62 days, 400 otherwise); default: last 24 hours to today + 7 days; `state` is a comma list of UPCOMING, DEPARTED, ARRIVED, CANCELLED, FULL; at most 300 trips; `summary` covers the whole range before the state filter |
| GET | `/admin/board/trips/{id}` | role ADMIN | One trip of the board with the state of every seat for the full journey (`FREE`, `HELD`, `TAKEN`; the booking reference only on `TAKEN`) and the standing counts per coach. 404 for an unknown trip |
| POST | `/admin/trips/{id}/cancel` | role ADMIN | **Cancel a whole trip** (Flow 8): `{ reason }`. See "Cancelling a whole trip". The answer is the progress below |
| GET | `/admin/trips/{id}/cancellation` | role ADMIN | `{ status OPEN\|CANCELLING\|CANCELLED, reason, cancelledBy, trainScheduleNotified, pendingHolds, pendingConfirmed, cancelledBookings, refundsFailed }`; CANCELLING is shown to the admin as "CANCELLED - bookings pending" |
| POST | `/bookings/{id}/resend-ticket` | the owner | **Resend ticket** (FR-T4): queues the ticket email again. 202 `{ queued }` (`false` if an email is already waiting, so the button cannot flood an inbox), 409 `not_confirmed` for a booking without a ticket, 404 for another user's booking |
| POST | `/internal/bookings/{id}/payment-result` | Payment (API key) | A payment succeeded: `{ paymentId, amount, currency }` → `{ result: CONFIRMED | REFUND_REQUIRED }`. The booking is confirmed (even if its five minutes have just run out and the sweeper has not released it: the payment wins). `REFUND_REQUIRED` when the hold is gone or the amount differs (then the hold is ended, never confirmed); Payment refunds the money. Idempotent |
| POST | `/internal/bookings/{id}/refund-status` | Payment (API key) | `{ status: REFUND_PENDING | REFUNDED | REFUND_FAILED }` is mirrored in `refund_status` of the booking |
| WebSocket | `/ws` (STOMP) | any valid token, offered as the subprotocol `access_token.<jwt>` | The live seat updates, reached through the Gateway. See "Live updates" |
| POST | `/bookings/pick` | any signed-in user | **Picks** one seat `{ tripId, fromStop, toStop, seatId }` or standing places `{ tripId, fromStop, toStop, coachId, qty }`. The first pick starts the hold: 201 with the booking (`PENDING_PAYMENT`, `reference`, `amount`, `expiresAt` = now + 5 min, `serverTime`, `items`). A later pick joins the hold: 200, same `expiresAt`. 409 `seats_unavailable` (this pick only; the held items stay held; `details` name the item as `seat:<id>` / `standing:<coachId>`), 409 `hold_for_another_journey`, 409 `trip_closed` / `trip_departed`, 410 `hold_expired`, 400 for a pick that is not valid for the trip, 404 for an unknown trip |
| POST | `/bookings/{id}/release` | the owner | Releases one seat `{ seatId }` or standing places `{ coachId, qty }` of the hold at once. Releasing the last item ends the hold (`ABANDONED`). 409 `not_picked` (the item is not in the hold), 409 `not_a_hold` (the booking is not an active hold), 409 `payment_in_progress`, 404 for another user's booking |
| DELETE | `/bookings/{id}/hold` | the owner | Releases everything and ends the hold (`ABANDONED`) |
| GET | `/bookings/{id}` | the owner | The booking; another user's booking is 404 `booking_not_found` |

### Live updates (Flow 2, SRS section 6)

Spring WebSocket with STOMP, pushed after every change of the availability of a trip. The endpoint is `/ws`; the token is checked at the handshake (`HandshakeAuthentication`) from the subprotocol `access_token.<jwt>` (never from the URL), with the same Keycloak validation as the HTTP endpoints, and the connection is named after the token's subject. Only the configured web origin (`CORS_ORIGIN`) may connect. A client may only **subscribe**, to `/topic/trips/{id}` (the events of a trip: `SEAT_HELD`, `SEAT_RELEASED`, `SEAT_BOOKED`, `STANDING_CHANGED`, `TRIP_CANCELLED`, each `{ type, tripId, seatIds, coachId, delta, segments, version }`) and to its own `/user/queue/holds` (`HOLD_EXPIRED` with the booking id and the reason `EXPIRED`, `TRIP_CANCELLED` or `RELEASED`); anything else, and every SEND, is refused and closes the connection (`SubscriptionGuard`).

The events are published inside the transaction of the change (each one takes the next `trip_version`) and delivered by `LiveUpdates` only with `@TransactionalEventListener(AFTER_COMMIT)`: an event of a transaction that rolled back is never sent. The version goes up by one per event, so a client that sees a gap, or that reconnects, loads the seat map again (`version` is in the seat map). One instance and the in-memory broker are assumed (SRS section 6).

### Holds (Flow 3) and the sweeper (Flow 5)

- **The hold starts at the first pick (Flow 3):** there is no confirm step. The first pick creates the booking (`expires_at` = now + 5 min); every further pick is joined to it with the hold's own `expires_at`, so the timer is never extended. A user has one active hold per trip (unique index `uk_booking_active_hold`, migration V4). Releasing an item is immediate and the last release ends the hold as `ABANDONED`. Before an item changes, Payment is asked to invalidate any payment created for the earlier amount (`PaymentPort.invalidate`); when the hold ends it is asked to cancel it (`cancelPending`), always before the seats are released.
- **A pick is one database transaction, all or nothing:** the row of the trip is locked first (so a hold waits for a trip that is being closed or changed), then the booking and its items, one `seat_reservation` row per seat and per segment of the journey (inserted in sorted seat and segment order), and for standing places a conditional `UPDATE standing_usage ... WHERE used + :qty <= capacity` per segment (coaches and segments in ascending order; a statement that changes no row rolls everything back). The primary key rejects a seat that is taken on any segment (`ORA-00001`); deadlocks and lock timeouts are treated as a conflict too. The user is then told which items were lost.
- **The amount is worked out on the server** (`BookingPricing`): a seat costs the journey fare of its class, a standing place the journey fare of the class of its coach. The request has no amount field.
- **5 minutes exactly:** `expires_at` = the server's now + 5 min (`HoldService.HOLD_DURATION`), never extended. The answer carries `serverTime` so the browser corrects its countdown for a wrong clock.
- **The trip must be open** (`OPEN`, not `CANCELLING` or `CANCELLED`) and the train must not have left the boarding stop (the same cutoff as the cancellation).
- **An expired but unreleased hold keeps blocking its seat** and shows as held; nothing purges rows during a new hold.
- **The sweeper** (`HoldSweeper`, `fixedDelay`, every 1 s, configurable) asks `ExpiryService` to handle each hold past its expiry: **first** Payment is asked to cancel the pending Stripe payment (`PaymentPort`, outside any transaction); then, if it was cancelled (or there was no payment), the seats and standing places are released and the booking is `EXPIRED` in one transaction (`HoldReleaseService`, which locks the booking and does nothing if it was confirmed in the meantime); if Stripe says the payment **succeeded**, the booking is confirmed instead (`ConfirmationService`, the payment wins); if it is **processing**, the seats stay held and are asked about again a second later; if Stripe or Payment is **down**, the seats stay held and the next attempt is delayed 2, 4, 8 ... up to 60 seconds (`next_attempt_at`). A timeout never charges and never refunds: `PaymentPort` can only cancel.
- **One lock order** for everything that changes the inventory of a trip (`BookingLocks`): the row of the trip, then the row of the booking, then the seat and standing rows, then the version counter. Holds, releases, confirmations and the snapshot receiver follow it, so they can wait for each other but never in a circle (no `ORA-00060`; if one happens anyway it is a conflict).
- **The cancel-first rule is guarded in code:** `ExpiryService.expire` refuses to run inside a transaction, and `HoldReleaseService` is documented as callable only after the payment was cancelled. A booking that cannot be released (it keeps failing) is retried with a growing delay and logged as an error from the 5th attempt; a payment that succeeded for a hold that is already gone is logged as an error and refunded by Payment.
- **A booking stays readable as history:** its lines keep the seat number, coach and class that the passenger was shown (`booking_item`, migration V3), a trip that has any confirmed or cancelled booking keeps its stops, times and fares frozen, and a stop that no longer exists shows as unknown instead of failing the page. The token must carry an e-mail address (the ticket is sent by e-mail), otherwise the hold is refused with 400; a very long name is cut to 200 characters.
- **The version of the availability answer is read before its data**, so a client that drops events up to that version never misses a change.
- **Payment (Flow 4, 5):** `PaymentPort` is the only thing Booking knows of Payment (`PaymentClient` is the HTTP implementation, 2 s connect and 10 s read timeouts; a failure to get an answer is `PaymentUnavailableException`, a 409 is `PaymentRejectedException`). **Continue to payment** sets `payment_started` in a short transaction under the booking lock (the same lock order as every hold change) and only then calls Payment, outside any transaction. `payment_started` is **never cleared**: once the user has been to the payment page, every pick, release, release-all and the sweeper go through Payment first (`invalidate` when an item changes, `cancelPending` when the hold ends), which is cheap and idempotent when there is nothing open. A change that looked at the booking just before the flag was set is detected under its own lock (`PaymentStartedMeanwhileException`), rolled back, and retried after cancelling the payment, so an open payment can never be missed. If Payment is down the change is refused with 503 and the seats stay held.
- **Ticket and email (Flow 4, last part):** confirming a booking queues a `TICKET_EMAIL` job in the **same transaction** (`OutboxWriter`, table `outbox`, migration V5; kinds for the cancellation flow are already allowed by its CHECK). A `@Scheduled` dispatcher (`OutboxDispatcher`, every second) runs the jobs outside any transaction, in order per booking, with a backoff (2 ... 300 s) and a log line per failure; after 300 attempts a job is marked `FAILED` and logged as an error. `TicketEmailJob` asks Ticket for the ticket (`PUT /internal/tickets/{id}`, idempotent: one ticket per booking), reads the PDF, and asks Notification for the email with the PDF attached (`ticket:<booking>:<job id>` as the dedupe key). Both calls are idempotent, so a job repeated after a failure in the middle makes no second ticket and no second email. **A ticket or an email that cannot be made never touches the booking**: it stays CONFIRMED. If the booking is no longer confirmed when the job runs, no email is sent.
- **Cancellation (Flow 6, SRS 7.2, FR-C1 to FR-C5, FR-C7):** `CancellationService` is the one implementation for the passenger and the admin. In ONE transaction under the booking lock (trip row, then booking row, the usual order) it re-checks that the booking is CONFIRMED and that the departure time of the booking's own boarding stop is still ahead, gives the seats and standing places back (`BookingInventory.giveBack`, the SEAT_RELEASED and STANDING_CHANGED events go out after the commit), sets CANCELLED with `cancelled_by`, `cancelled_by_role` (USER or ADMIN), `cancelled_at`, the reason for an admin only, and `refund_status = REFUND_PENDING`, and queues in the outbox, in this order: `TICKET_INVALIDATE`, `REFUND_REQUEST`, `CANCELLATION_EMAIL`. Those run afterwards with retries (the jobs of one booking run in order, one per second): Ticket invalidates the QR, Payment is asked for the full refund (`RefundPort`, idempotent: one refund per booking), Notification emails the passenger. When Payment reports `REFUNDED` (its refund webhook) the status is mirrored and `REFUND_EMAIL` is queued once; `REFUND_FAILED` is only flagged (admin follow-up, no email). `RefundPort` is separate from `PaymentPort` on purpose: the expiry of a hold can only cancel a payment, so a timeout can never refund. A Ticket, Payment or Notification that is down never blocks or undoes a cancellation.
- **Cancelling a whole trip (Flow 8, SRS 7.3, FR-C8):** `TripCancellationService.request` locks the trip row, refuses a trip that has left its first stop (409 `trip_departed`), sets the copy of the trip CANCELLING with the admin and the reason (migration V7), which **closes it to new holds at once** (a pick refuses a trip that is not OPEN), and does one pass. A pass is idempotent and every step is independent, so a failure only leaves work for the next pass: **(1)** Train & Schedule is told (`TrainSchedulePort`, `POST /internal/trips/{id}/cancel`; flag `tns_marked`), **(2)** every active hold: its payment is cancelled FIRST (for a hold with `payment_started`; a payment that already succeeded or is still processing waits for the next pass, when Payment's report has turned it into a confirmed booking), then the hold is released as EXPIRED with the reason TRIP_CANCELLED, so its user's browser is sent back with "This trip was cancelled", **(3)** every confirmed booking is cancelled with `CancellationService` as the admin, with the reason "The trip was cancelled: ..." (one transaction per booking, a failing one does not stop the others), **(4)** when no hold or confirmed booking is left and Train & Schedule knows, the trip becomes CANCELLED and TRIP_CANCELLED is pushed to everyone viewing it. `TripCancellationWorker` repeats the pass every 5 s for trips that are CANCELLING (`trainbooking.tripcancel.fixed-delay-ms`); asking again is safe (already cancelled bookings are skipped, the first reason stays).
- **Limits:** a hold has at most 20 seats and, per coach, at most 20 standing places.
- **Events** (`SeatEvent`, `UserEvent`, version counter per trip) are published inside the transaction and meant to be delivered after the commit; every event takes the next number of `trip_version`.
- Every state change of a booking is written to `audit_log` with its actor (the user, or `system` for the sweeper).

### The snapshot

The body is `{ departureDate, train { number, name, type }, routeName, stops [...], coaches [...], seats [...], fares [...] }`,
with the same JSON names as Train & Schedule (`class`, `row`, `column`). Stop times carry their offset. It is checked
twice: field by field (400 `validation_failed`) and as a whole by `SnapshotValidator` (stops numbered 0 to n-1, a station
only once, no arrival at the first stop and no departure at the last, every seat in a coach of the snapshot with the class of
that coach, unique ids, codes and positions, a fare of at most 2 decimals in EGP for every segment and every class the trip
has a coach of). Errors name the field, for example `seats[3].coachId`.

It is applied in one transaction, with the row of the trip locked first. Everything is written with `MERGE` (the same snapshot
twice gives the same rows) and rows that are gone are deleted by comparing keys in Java. The status of a trip is `OPEN` when
it is created and is never changed by a snapshot. Refusals are **409**, and nothing is changed:

| `error.code` | When |
|---|---|
| `trip_not_open` | the trip is `CANCELLING` or `CANCELLED` |
| `trip_has_reservations` | the trip has holds or bookings (any seat reserved or any standing place in use) and the snapshot changes its date, train, stops, times or fares; or the trip is being deleted |
| `capacity_conflict` | a seat with reservations would be removed, moved to another coach or given another class, or the standing capacity of a coach would drop below the places in use, or a coach with standing places in use would be removed or given another class (FR-A9). The foreign key from `seat_reservation` to the seat and the `CHECK (used <= capacity)` are the last line of defence |

Changes that only add capacity, or that repeat the same snapshot, are always accepted.

## Database

Flyway migrations in `src/main/resources/db/migration`, run at startup as the `booking_seat` user:

- `V1`: the copy of the trip (`trip`, `trip_stop`, `trip_coach`, `trip_seat`, `trip_fare`, all keyed by the trip id of Train &
  Schedule), `seat_reservation` with the primary key `(trip_id, seat_id, segment_index)` that makes double booking impossible
  (`ORA-00001` means "seat taken"), `standing_usage` with `CHECK (used <= capacity)`, and `audit_log`.
  The standing capacity of a coach is stored only in `standing_usage.capacity` (one row per coach with standing places and
  per segment).
- `V3`: `booking_item` gets `seat_number`, `coach_code` and `coach_class` (the line carries what the passenger was shown).
- `V2`: `booking` (status, `refund_status`, amount, `expires_at`, the actor columns of a cancellation, `next_attempt_at`), `booking_item`, `standing_hold`, `trip_version` and the foreign key from `seat_reservation.booking_id` to `booking`. A trip with any booking cannot be deleted.

## Environment variables

Set in `services/booking-seat/.env` (git-ignored; copy `.env.example`), or in the process environment.

| Variable | Default | Meaning |
|---|---|---|
| `BOOKING_SEAT_DB_PASSWORD` | none, required | Password of the `booking_seat` Oracle user (same as in the root `.env`) |
| `PAYMENT_URL` | `http://localhost:8083` | Where the Payment service runs |
| `TRAIN_SCHEDULE_URL` | `http://localhost:8081` | Where Train & Schedule runs (to tell it a trip is cancelled) |
| `TIMEZONE` | `Africa/Cairo` | The railway's own time zone: the stop times in the API (`trip.from.departureTime`, ...) carry its offset, whatever zone the JVM or the database session runs in. The web app shows the clock part of them as it is |
| `TICKET_URL`, `NOTIFICATION_URL` | `http://localhost:8084`, `http://localhost:8085` | Where the Ticket and Notification services run |
| `TRAINBOOKING_OUTBOX_FIXED_DELAY_MS` | `1000` | Delay between two runs of the outbox |
| `INTERNAL_API_KEY` | none, required | Shared key for service-to-service calls (the same value in Train & Schedule's `.env`); the service does not start without it |
| `BOOKING_SEAT_TEST_DB_PASSWORD` | none | Password of the `booking_seat_test` user for the database tests (see below); without it those tests are skipped |
| `BOOKING_SEAT_DB_URL` | `jdbc:oracle:thin:@//localhost:1521/FREEPDB1` | JDBC URL |
| `BOOKING_SEAT_DB_USER` | `booking_seat` | Oracle user |
| `KEYCLOAK_ISSUER` | `http://localhost:8180/realms/train-booking` | Expected token issuer; the keys are fetched from it |
| `CORS_ORIGIN` | `http://localhost:5173` | The one origin that may open the WebSocket |
| `TRAINBOOKING_SWEEPER_FIXED_DELAY_MS` | `1000` | Delay between two sweeps of expired holds (`fixedDelay`) |

The service listens on port **8082**.

## Run

Oracle and Keycloak must be running (`docker compose up -d` from the repository root). From the repository root:

```
gradle :services:booking-seat:bootRun
```

The trips arrive by themselves when an admin creates or changes a trip in Train & Schedule. For trips that existed before
this service did, re-save them once (a `PUT /admin/trips/{id}` with the data they already have pushes the snapshot).

## Tests

```
gradle :services:booking-seat:test
```

Most tests need no database: the full-journey rule, the snapshot validator, and the controllers (security, validation, error
shapes). The **database tests** run the whole application against a real Oracle schema, `booking_seat_test`, which is emptied
before every test; a guard refuses to run on any schema whose name does not end in `_test`, so they never touch the dev data.
They cover the queries that feed the availability, the constraints (`ORA-00001`, `ORA-02291`, `ORA-02290`), and the snapshot
receiver, including that a failing step rolls back the whole push.

If `BOOKING_SEAT_TEST_DB_PASSWORD` is not set, the database tests are **skipped, not passed**: Gradle prints each one as
`SKIPPED` and ends the run with a line such as `Booking & Seat tests: 90 run, 58 passed, 0 failed, 32 SKIPPED`.

The test schema is **optional**. `infra/oracle/init/01-create-schemas.sh` creates it on a fresh Oracle volume only if
`BOOKING_SEAT_TEST_DB_PASSWORD` is set in the root `.env`; without it Compose starts as usual and the database tests are skipped.
An existing volume does not
run that script again; create the schema once with this (the password is the value of `BOOKING_SEAT_TEST_DB_PASSWORD` in the
root `.env`), then put the same password in `services/booking-seat/.env`:

```
docker exec -i tbs-oracle bash -c 'sqlplus -s / as sysdba' <<'SQL'
ALTER SESSION SET CONTAINER = FREEPDB1;
ALTER SESSION SET db_create_file_dest = '/opt/oracle/oradata';
CREATE TABLESPACE ts_booking_seat_test DATAFILE SIZE 50M AUTOEXTEND ON NEXT 50M MAXSIZE 1G;
CREATE USER booking_seat_test IDENTIFIED BY "<password>" DEFAULT TABLESPACE ts_booking_seat_test QUOTA UNLIMITED ON ts_booking_seat_test;
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE VIEW TO booking_seat_test;
SQL
```

## Logs, audit and traces (OpenObserve)

- **Structured logs.** With `LOGGING_STRUCTURED_FORMAT_CONSOLE=logstash` (set by Compose) every log line is one JSON object. `RequestLogContext` writes one `access` line per request with the method, the **path** (never the query string, a header or a body), the status and the duration, and puts the user id (the token's `sub`), the booking id and the trip id of the path into the logging context, so every line of that request carries them as fields.
- **Audit.** `AuditTrail.record` writes the `audit_log` row and, **after the transaction commits** (never for a change that rolls back), a log line from the logger `audit` with the fields `actor`, `entity`, `entityId`, `action`, `bookingId` / `tripId` and the `diff`, so every admin action and booking state change reaches OpenObserve with its timestamp and actor.
- **Traces and shipping.** The Docker image starts the service with the OpenTelemetry Java agent (`infra/docker/entrypoint-java.sh`). With `OTEL_EXPORTER_OTLP_ENDPOINT` and the OpenObserve login set (Compose does), the agent sends traces and the logs, with the logging-context fields as attributes, to OpenObserve. A call from the Gateway continues the Gateway's trace. Run on the host with Gradle, the service just logs to the console.
