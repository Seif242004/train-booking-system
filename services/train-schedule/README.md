# Train & Schedule service

Spring Boot service (Java 21, Gradle, Spring MVC: controller → service → repository) that owns trains, coaches,
seats, standing capacity, routes, trips and stations (`CLAUDE.md`, "Services"). So far it has **stations**, **trains**,
**coaches** (with standing capacity) and **seats**, all admin CRUD with the audit log required by FR-A10. Routes, fares
and trips follow in later slices.

## Responsibility

- Stores its data in its own Oracle schema, `train_schedule`. It never reads another service's tables.
- Validates the Keycloak access token itself (OAuth2 resource server), even though the Gateway checked it first.
  Roles come from `realm_access.roles`; `/admin/**` requires `ADMIN`. There is no audience check, as in the Gateway.
- Writes an `audit_log` row (admin id = JWT `sub`, entity, action, before/after JSON) in the same transaction as every
  create, update and delete.

## Code layout

Feature first, then layer (`CLAUDE.md`, "Code standards"): `station`, `train`, `coach` and `seat` each have
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

Rules worth knowing:

- **Values:** train `status` is `ACTIVE` or `INACTIVE`; coach `class` is `FIRST`, `SECOND` or `THIRD` (our decision, the
  SRS gives no values); train `type` is free text. Codes and numbers are stored upper-case.
- **Coach position** is unique per train, starting at 1; coach `code` is unique per train. Standing capacity is 0 or more.
- **Seat class** is the class of its coach. The server sets it, a client can never send it, and it is updated for all of
  a coach's seats when the coach's class changes.
- **Bulk seat add is all or nothing.** Any invalid seat, a seat number or a row and column repeated inside the list (400),
  or a clash with an existing seat of the coach (409) rejects the whole request and saves nothing. Errors name the
  position in the list, for example `[2].seatNumber`. At most 200 seats per request.
- **Errors** have one JSON shape: `{ "error": { "code", "message", "details": [ { "field", "message" } ] } }`
  (`details` for validation and seat conflicts). A missing value reports only "is required". Security failures use the
  same shape: `401 unauthenticated` or `invalid_token`, `403 forbidden`.
- **TODOs for when trips and bookings exist:** refuse deleting a train that has future trips with confirmed bookings
  (FR-A4, Flow 7), and refuse capacity changes (removing seats or coaches, lowering standing capacity) that conflict
  with active holds or confirmed bookings (FR-A9). Until then these deletes and changes always go through.

## Database

Flyway migrations live in `src/main/resources/db/migration` and run at startup as the `train_schedule` Oracle user.

- `V1`: `station(id, code, name, city)` and `audit_log`.
- `V2`: `train`, `coach` and `seat`, with the unique and check constraints above and `ON DELETE CASCADE` from train to
  coach to seat.

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
