# Train & Schedule service

Spring Boot service (Java 21, Gradle, Spring MVC: controller → service → repository) that owns trains, coaches,
seats, standing capacity, routes, trips and stations (`CLAUDE.md`, "Services"). So far it has **stations**, **trains**,
**coaches** (with standing capacity), **seats**, **routes** with ordered stops and **fares**, all admin CRUD with the
audit log required by FR-A10. Trips follow in the next slice.

## Responsibility

- Stores its data in its own Oracle schema, `train_schedule`. It never reads another service's tables.
- Validates the Keycloak access token itself (OAuth2 resource server), even though the Gateway checked it first.
  Roles come from `realm_access.roles`; `/admin/**` requires `ADMIN`. There is no audience check, as in the Gateway.
- Writes an `audit_log` row (admin id = JWT `sub`, entity, action, before/after JSON) in the same transaction as every
  create, update and delete.

## Code layout

Feature first, then layer (`CLAUDE.md`, "Code standards"): `station`, `train`, `coach`, `seat`, `route` and `fare` each have
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
- **Errors** have one JSON shape: `{ "error": { "code", "message", "details": [ { "field", "message" } ] } }`
  (`details` for validation and seat conflicts). A missing value reports only "is required". Security failures use the
  same shape: `401 unauthenticated` or `invalid_token`, `403 forbidden`.
- **TODOs for when trips and bookings exist:** refuse deleting a route, or changing its stops, when trips use it; require
  a fare for every segment and class when a trip is created (slice 4); refuse deleting a train that has future trips with confirmed bookings
  (FR-A4, Flow 7), and refuse capacity changes (removing seats or coaches, lowering standing capacity) that conflict
  with active holds or confirmed bookings (FR-A9). Until then these deletes and changes always go through.

## Database

Flyway migrations live in `src/main/resources/db/migration` and run at startup as the `train_schedule` Oracle user.

- `V1`: `station(id, code, name, city)` and `audit_log`.
- `V2`: `train`, `coach` and `seat`, with the unique and check constraints above and `ON DELETE CASCADE` from train to
  coach to seat.
- `V3`: `route`, `route_stop` (primary key route and stop index, one row per station per route, no cascade from station)
  and `fare` (primary key route, segment and class; amount above 0; currency fixed to EGP). Deleting a route cascades to
  its stops and fares.

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
