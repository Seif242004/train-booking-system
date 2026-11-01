# Train & Schedule service

Spring Boot service (Java 21, Gradle, Spring MVC: controller → service → repository) that owns trains, coaches,
seats, standing capacity, routes, trips and stations (`CLAUDE.md`, "Services"). So far it has **stations**, **trains**,
**coaches** (with standing capacity), **seats**, **routes** with ordered stops, **fares** and **trips**, all admin CRUD
with the audit log required by FR-A10, plus passenger **trip search**. Every trip that it creates, changes or deletes is
also handed to Booking & Seat, which keeps its own copy (see "Trip snapshot").

## Responsibility

- Stores its data in its own Oracle schema, `train_schedule`. It never reads another service's tables.
- Validates the Keycloak access token itself (OAuth2 resource server), even though the Gateway checked it first.
  Roles come from `realm_access.roles`; `/admin/**` requires `ADMIN`. There is no audience check, as in the Gateway.
- Writes an `audit_log` row (admin id = JWT `sub`, entity, action, before/after JSON) in the same transaction as every
  create, update and delete.

## Code layout

Feature first, then layer (`CLAUDE.md`, "Code standards"): `station`, `train`, `coach`, `seat`, `route`, `fare` and `trip` each have
`controller/`, `service/`, `repository/` and `model/` (records, request bodies and the feature's exceptions).
`audit/` (audit log), `security/` (resource-server setup) and `common/` (error shape and handler) are shared.

## Endpoints

All require the `ADMIN` role. Browsers reach them through the Gateway. JSON field names are the SRS names.

| Method | Path | Result |
|---|---|---|
| GET / POST | `/admin/stations` | list ordered by code / create |
| GET / PUT / DELETE | `/admin/stations/{id}` | read / update / delete |
| GET | `/admin/trains?number=&name=&status=` | list; `number` and `name` match "contains" in any case, `status` is `ACTIVE` or `INACTIVE` |
| POST | `/admin/trains` | create: `{ number, name, type, status }`; 409 `train_number_taken` |
| GET / PUT / DELETE | `/admin/trains/{id}` | read / update / delete; a delete also deletes the train's coaches and seats |
| GET / POST | `/admin/trains/{id}/coaches` | list in train order (by `position`) / add: `{ code, class, position, standingCapacity }` |
| PUT / DELETE | `/admin/coaches/{id}` | update / delete; a delete also deletes the coach's seats |
| GET / POST | `/admin/coaches/{id}/seats` | list in seat-map order / **bulk add**: a JSON list of `{ seatNumber, row, column }` |
| PUT / DELETE | `/admin/seats/{id}` | update `{ seatNumber, row, column }` / delete |
| GET / POST | `/admin/routes` | list routes with their stops in order / create `{ name, stops: [stationId, ...] }` |
| GET / PUT / DELETE | `/admin/routes/{id}` | read / update (`?clearFares=true` to confirm a stops change, see below) / delete; a delete also deletes the route's stops and fares |
| GET / PUT | `/admin/routes/{id}/fares` | read the fares / **replace** the route's whole fare set with a JSON list of `{ segmentIndex, class, amount }` |
| GET / POST | `/admin/trips` | list trips by first departure (`?date=yyyy-MM-dd` to filter) / create `{ trainId, routeId, departureDate, stops: [{ arrivalTime, departureTime }, ...] }` |
| GET / PUT / DELETE | `/admin/trips/{id}` | read / replace train, route, date and all stop times / delete (the stops go with it) |
| GET | `/trips?from=CAI&to=ALX&date=2026-11-02` | **passenger trip search**, any signed-in user (see below) |
| GET | `/stations` | read-only station list for any signed-in user, so the search page can offer the stations |
| GET | `/trips/{id}/layout` | any signed-in user: the train, the stops with their times and every coach in train order with its seats and standing capacity, for the seat map. No availability (that is Booking & Seat's). Called by the Gateway, not by the browser; 404 for an unknown trip |

Rules worth knowing:

- **Values:** train `status` is `ACTIVE` or `INACTIVE`; coach `class` is `FIRST`, `SECOND` or `THIRD` (our decision, the
  SRS gives no values); train `type` is free text. Codes and numbers are stored upper-case.
- **Coach position** is unique per train, starting at 1; coach `code` is unique per train. Standing capacity is 0 or more.
- **Seat class** is the class of its coach. The server sets it, a client can never send it, and it is updated for all of
  a coach's seats when the coach's class changes.
- **Bulk seat add is all or nothing.** Any invalid seat, a seat number or a row and column repeated inside the list (400),
  or a clash with an existing seat of the coach (409) rejects the whole request and saves nothing. Errors name the
  position in the list, for example `[2].seatNumber`. At most 200 seats per request.
- **Routes:** `stops` are station ids in travel order (the first is stop 0), 2 to 50 of them, each an existing station, and
  a station may appear only once. Responses add each stop's station code, name and city. Segment *k* is the leg from
  stop *k* to stop *k*+1, so a route with *n* stops has *n*-1 segments.
- **Fares** are in EGP only (the response carries `currency: "EGP"`). `amount` is more than 0 with at most 2 decimals,
  `class` is `FIRST`, `SECOND` or `THIRD`, and `segmentIndex` must exist on the route. The PUT is all or nothing: any
  invalid fare, a segment and class repeated in the list, or a segment that does not exist rejects the request and the
  existing fares stay. A segment or class may be left without a fare for now; a journey will cost the sum of its segments.
- **Changing a route's stops while it has fares is refused** (409 `route_has_fares`), because fares belong to segment
  positions. Repeating the request with `clearFares=true` removes the fares and saves; the audit row records how many.
  Renaming a route never touches its fares.
- **A station that a route uses cannot be deleted** (409 `station_in_use`).
- **Trips:** a trip is a train on a route with a departure date and the time at every stop. `stops` has one entry per
  route stop, in the route's order, with times as `HH:mm` (24 hours, local time of `trainbooking.timezone`, default
  `Africa/Cairo`; stored with their offset). The first stop has only a departure and the last only an arrival; a time
  earlier than the one before it means the next day, so a trip can run past midnight, and a trip lasts at most 24 hours.
  The response has full local date-times such as `2026-11-03T00:15:00`. A trip starts `ACTIVE`; the API cannot set
  `CANCELLED`, which belongs to the whole-trip cancel (slice 12).
- **Creating or changing a trip is refused** (409) when the train is `INACTIVE` (`train_inactive`; changing only the times of
  an existing trip is still allowed), when the train has no coaches (`train_has_no_coaches`), or when the route's fares are
  **not complete** for the train (`route_fares_incomplete`): every segment needs a fare for every class that the train has
  a coach of, and every missing one is listed in `details`.
- **Trip search** returns the trips that leave the origin on the given date (the departure date at the origin stop, in the
  service time zone) and later arrive at the destination: cancelled trips are never returned, and only trips where the
  origin comes before the destination on the route. Each result has the times at both stations, the stop positions of the
  journey, **all the stops of the trip** (`stops`, in route order, with the same fields and local date-times as the admin
  trip: the stops outside the journey are included so a client can dim them) and the journey fare per class in EGP (the
  sum of the segments it uses). The answer is `{ "trips": [...], "nextAvailableDate": "2026-11-09" }`: when `date` is
  today (in the service time zone) the trips that have already left the origin are left out, and when there are no trips
  `nextAvailableDate` is the next date, after the searched one and never in the past, that has a trip from the origin to
  the destination; it is omitted when there is none, and always omitted when trips were found. `from` and `to` are station codes (any
  case); an unknown code, the same station twice or a bad date is a 400.
  TODO(slice 6): add the seats and standing places left for each journey, which Booking & Seat owns (FR-S2).
- **Trips use trains, routes and stations:** a train or a route that has trips cannot be deleted (409 `train_in_use`,
  `route_in_use`), and the stops of a route that has trips cannot change (409 `route_in_use`; renaming is fine).
- **Trip snapshot:** when a trip is created or changed, `TripSnapshotPublisher` builds the immutable snapshot (stops with
  their times and offsets, coaches with standing capacity, seats, fares) and pushes it to Booking & Seat with
  `PUT /internal/trips/{id}/snapshot`; when a trip is deleted it calls `DELETE /internal/trips/{id}`. The calls carry the
  shared `INTERNAL_API_KEY` and the admin's id (for Booking's audit log). The push is **synchronous and the last step of the
  admin request**, inside its transaction, so a trip is saved only after Booking has accepted it; there is no outbox for
  it (the outbox and retry poller are for calls that do not need an answer first, such as emails). Timeouts: 2 s to
  connect, 5 s to answer. Failures, all of which leave nothing saved: **409 `booking_conflict`** (Booking refuses: the trip
  has holds or bookings, so its stops, times, train, date and fares are frozen and it cannot be deleted; or the change would
  remove capacity that is in use; its explanation is passed on), **503 `booking_unavailable`** (not reachable, timeout,
  a 5xx, or a wrong key) and **502 `booking_rejected_snapshot`** (Booking found the snapshot invalid, which is a fault in
  our data, not something to retry). Pushing is idempotent, so after a failure the admin repeats the request. The one gap:
  if Booking accepts and this service's commit then fails, Booking keeps a copy of a trip that does not exist; nothing
  offers it to passengers and a later push of the same id overwrites it.
- **Errors** have one JSON shape: `{ "error": { "code", "message", "details": [ { "field", "message" } ] } }`
  (`details` for validation and seat conflicts). A missing value reports only "is required". Security failures use the
  same shape: `401 unauthenticated` or `invalid_token`, `403 forbidden`.
- **TODO, a separate slice before the hold logic:** the snapshot is pushed only when a trip is created, changed or deleted.
  Things that are inside a snapshot but are edited elsewhere (coaches, seats, standing capacity, fares, station names) are
  not pushed yet, so Booking's copy goes stale if they are edited after a trip exists. The planned rule: a fare edit
  re-publishes the snapshots of all trips on the route and is refused with 409 if any of those trips has reservations;
  the same for coach, seat and standing edits (Booking already refuses capacity changes that conflict with holds or
  bookings, FR-A9). Also still open: relax the train delete to FR-A4 exactly (refuse deleting a train that has future
  trips with confirmed bookings, Flow 7).

## Database

Flyway migrations live in `src/main/resources/db/migration` and run at startup as the `train_schedule` Oracle user.

- `V1`: `station(id, code, name, city)` and `audit_log`.
- `V2`: `train`, `coach` and `seat`, with the unique and check constraints above and `ON DELETE CASCADE` from train to
  coach to seat.
- `V3`: `route`, `route_stop` (primary key route and stop index, one row per station per route, no cascade from station)
  and `fare` (primary key route, segment and class; amount above 0; currency fixed to EGP). Deleting a route cascades to
  its stops and fares.
- `V4`: `trip` (train, route, departure date, status) and `trip_stop` (the stop times as `TIMESTAMP WITH TIME ZONE`; the
  stations and times are copied from the route and the request). Trains, routes and stations that trips use are protected
  by foreign keys without cascade; deleting a trip cascades to its stops.

Three SRS column names are Oracle reserved words, so the database columns are renamed (the JSON API keeps the SRS
names, and the migration comments say so): `train.number` is `train_number`, `seat.row` is `seat_row`, `seat.column` is
`seat_column`.

## Environment variables

Set in `services/train-schedule/.env` (git-ignored; copy `.env.example`), or in the process environment.

| Variable | Default | Meaning |
|---|---|---|
| `TRAIN_SCHEDULE_DB_PASSWORD` | none, required | Password of the `train_schedule` Oracle user (same as in the root `.env`) |
| `TRAIN_SCHEDULE_DB_URL` | `jdbc:oracle:thin:@//localhost:1521/FREEPDB1` | JDBC URL |
| `TRAIN_SCHEDULE_DB_USER` | `train_schedule` | Oracle user |
| `KEYCLOAK_ISSUER` | `http://localhost:8180/realms/train-booking` | Expected token issuer; the keys are fetched from it |
| `TRAINBOOKING_TIMEZONE` | `Africa/Cairo` | Time zone of trip times (entered, shown and used for "the date of a trip") |
| `INTERNAL_API_KEY` | none, required | Key shared with Booking & Seat for the snapshot push (the same value in both `.env` files) |
| `BOOKING_SEAT_URL` | `http://localhost:8082` | Where Booking & Seat runs |

The service listens on port **8081**.

## Run

Oracle and Keycloak must be running (`docker compose up -d` from the repository root). From the repository root:

```
./gradlew :services:train-schedule:bootRun
```

Tests (no database needed; security rules, validation and service logic):

```
./gradlew :services:train-schedule:test
```
