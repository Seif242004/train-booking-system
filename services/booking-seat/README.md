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
| GET | `/trips/{id}/availability?from=&to=` | any signed-in user (the Gateway calls it for the browser) | Availability of the trip for the journey. `from` and `to` are **stop indexes** of the trip (the boarding stop and the destination stop). Returns `tripId`, `tripStatus`, `fromStop`, `toStop`, `summary { seatsLeft, standingLeft }` (counts per class) and, unless `view=summary`, `seats [{ seatId, coachId, state AVAILABLE\|HELD\|BOOKED }]` and `standing [{ coachId, capacity, remaining }]`. 400 for a journey that is not valid for the trip, 404 for an unknown trip |
| PUT | `/internal/trips/{id}/snapshot` | Train & Schedule (API key) | Stores or updates the copy of a trip, all or nothing. Idempotent. Header `X-Acting-User` (optional): the admin whose action caused it, written to the audit log |
| DELETE | `/internal/trips/{id}` | Train & Schedule (API key) | Removes the copy of a deleted trip (204; also 204 if there is none) |

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
| `capacity_conflict` | a seat with reservations would be removed or moved to another coach, or the standing capacity of a coach would drop below the places in use, or a coach with standing places in use would be removed (FR-A9). The foreign key from `seat_reservation` to the seat and the `CHECK (used <= capacity)` are the last line of defence |

Changes that only add capacity, or that repeat the same snapshot, are always accepted.

## Database

Flyway migrations in `src/main/resources/db/migration`, run at startup as the `booking_seat` user:

- `V1`: the copy of the trip (`trip`, `trip_stop`, `trip_coach`, `trip_seat`, `trip_fare`, all keyed by the trip id of Train &
  Schedule), `seat_reservation` with the primary key `(trip_id, seat_id, segment_index)` that makes double booking impossible
  (`ORA-00001` means "seat taken"), `standing_usage` with `CHECK (used <= capacity)`, and `audit_log`.
  The standing capacity of a coach is stored only in `standing_usage.capacity` (one row per coach with standing places and
  per segment).
  TODO(slice 7): `seat_reservation.booking_id` gets its foreign key when the `booking` table is created.

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
