# Booking & Seat service

Spring Boot service (Java 21, Gradle, Spring MVC: controller → service → repository) that will own segment availability,
the 5-minute holds, bookings, cancellation and the live seat updates (`CLAUDE.md`, "Services"). So far it has the **copy of
the trips** that Train & Schedule pushes, the **inventory tables** (still empty: there is no hold logic yet) and the
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
| WebSocket | `/ws` (STOMP) | any valid token, offered as the subprotocol `access_token.<jwt>` | The live seat updates, reached through the Gateway. See "Live updates" |
| POST | `/bookings` | any signed-in user | **Holds** seats and standing places: `{ tripId, fromStop, toStop, seatIds[], standing[{ coachId, qty }] }`. 201 with the booking (`PENDING_PAYMENT`, `reference`, `amount`, `expiresAt`, `serverTime`, `items`). 409 `seats_unavailable` (nothing held; `details` list the lost items as `seat:<id>` / `standing:<coachId>`), 409 `trip_closed` / `trip_departed`, 400 for a selection that is not valid for the trip, 404 for an unknown trip |
| GET | `/bookings/{id}` | the owner | The booking; another user's booking is 404 `booking_not_found` |

### Live updates (Flow 2, SRS section 6)

Spring WebSocket with STOMP, pushed after every change of the availability of a trip. The endpoint is `/ws`; the token is checked at the handshake (`HandshakeAuthentication`) from the subprotocol `access_token.<jwt>` (never from the URL), with the same Keycloak validation as the HTTP endpoints, and the connection is named after the token's subject. Only the configured web origin (`CORS_ORIGIN`) may connect. A client may only **subscribe**, to `/topic/trips/{id}` (the events of a trip: `SEAT_HELD`, `SEAT_RELEASED`, `SEAT_BOOKED`, `STANDING_CHANGED`, `TRIP_CANCELLED`, each `{ type, tripId, seatIds, coachId, delta, segments, version }`) and to its own `/user/queue/holds` (`HOLD_EXPIRED` with the booking id and the reason `EXPIRED`, `TRIP_CANCELLED` or `RELEASED`); anything else, and every SEND, is refused and closes the connection (`SubscriptionGuard`).

The events are published inside the transaction of the change (each one takes the next `trip_version`) and delivered by `LiveUpdates` only with `@TransactionalEventListener(AFTER_COMMIT)`: an event of a transaction that rolled back is never sent. The version goes up by one per event, so a client that sees a gap, or that reconnects, loads the seat map again (`version` is in the seat map). One instance and the in-memory broker are assumed (SRS section 6).

### Holds (Flow 3) and the sweeper (Flow 5)

- **A hold is one database transaction, all or nothing:** the row of the trip is locked first (so a hold waits for a trip that is being closed or changed), then the booking and its items, one `seat_reservation` row per seat and per segment of the journey (inserted in sorted seat and segment order), and for standing places a conditional `UPDATE standing_usage ... WHERE used + :qty <= capacity` per segment (coaches and segments in ascending order; a statement that changes no row rolls everything back). The primary key rejects a seat that is taken on any segment (`ORA-00001`); deadlocks and lock timeouts are treated as a conflict too. The user is then told which items were lost.
- **The amount is worked out on the server** (`BookingPricing`): a seat costs the journey fare of its class, a standing place the journey fare of the class of its coach. The request has no amount field.
- **5 minutes exactly:** `expires_at` = the server's now + 5 min (`HoldService.HOLD_DURATION`), never extended. The answer carries `serverTime` so the browser corrects its countdown for a wrong clock.
- **The trip must be open** (`OPEN`, not `CANCELLING` or `CANCELLED`) and the train must not have left the boarding stop (the same cutoff as the cancellation).
- **An expired but unreleased hold keeps blocking its seat** and shows as held; nothing purges rows during a new hold.
- **The sweeper** (`HoldSweeper`, `fixedDelay`, every 1 s, configurable) asks `ExpiryService` to handle each hold past its expiry: **first** Payment is asked to cancel the pending Stripe payment (`PaymentPort`, outside any transaction); then, if it was cancelled (or there was no payment), the seats and standing places are released and the booking is `EXPIRED` in one transaction (`HoldReleaseService`, which locks the booking and does nothing if it was confirmed in the meantime); if Stripe says the payment **succeeded**, the booking is confirmed instead (`ConfirmationService`, the payment wins); if it is **processing**, the seats stay held and are asked about again a second later; if Stripe or Payment is **down**, the seats stay held and the next attempt is delayed 2, 4, 8 ... up to 60 seconds (`next_attempt_at`). A timeout never charges and never refunds: `PaymentPort` can only cancel.
- **One lock order** for everything that changes the inventory of a trip (`BookingLocks`): the row of the trip, then the row of the booking, then the seat and standing rows, then the version counter. Holds, releases, confirmations and the snapshot receiver follow it, so they can wait for each other but never in a circle (no `ORA-00060`; if one happens anyway it is a conflict).
- **The cancel-first rule is guarded in code:** `ExpiryService.expire` refuses to run inside a transaction, and `HoldReleaseService` is documented as callable only after the payment was cancelled. A booking that cannot be released (it keeps failing) is retried with a growing delay and logged as an error from the 5th attempt; a payment that succeeded for a hold that is already gone is logged as an error and refunded by Payment.
- **A booking stays readable as history:** its lines keep the seat number, coach and class that the passenger was shown (`booking_item`, migration V3), a trip that has any confirmed or cancelled booking keeps its stops, times and fares frozen, and a stop that no longer exists shows as unknown instead of failing the page. The token must carry an e-mail address (the ticket is sent by e-mail), otherwise the hold is refused with 400; a very long name is cut to 200 characters.
- **The version of the availability answer is read before its data**, so a client that drops events up to that version never misses a change.
- **Not done yet, on purpose:** one active hold per user and trip, and releasing a hold by hand (FR-H6, FR-H7, "Should" items, built after all the "Must" items); a hold has no limit per user until then (a request holds at most 20 seats and 10 standing entries of 20 places).
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
