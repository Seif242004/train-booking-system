# Train & Schedule service

Spring Boot service (Java 21, Gradle, Spring MVC: controller → service → repository) that owns trains, coaches,
seats, standing capacity, routes, trips and stations (`CLAUDE.md`, "Services"). This first slice has **stations** only:
admin CRUD, with the audit log required by FR-A10. Trains, coaches, seats and the rest follow in later slices.

## Responsibility

- Stores stations in its own Oracle schema, `train_schedule`. It never reads another service's tables.
- Validates the Keycloak access token itself (OAuth2 resource server), even though the Gateway checked it first.
  Roles come from `realm_access.roles`; `/admin/**` requires `ADMIN`. There is no audience check, as in the Gateway.
- Writes an `audit_log` row (admin id = JWT `sub`, entity, action, before/after JSON) in the same transaction as every
  create, update and delete.

## Endpoints

All require the `ADMIN` role. Browsers reach them through the Gateway (`/admin/stations/**`).

| Method | Path | Result |
|---|---|---|
| GET | `/admin/stations` | 200, array of `{ id, code, name, city }` ordered by code |
| GET | `/admin/stations/{id}` | 200, or 404 `station_not_found` |
| POST | `/admin/stations` | 201 with the station and a relative `Location`; 400 `validation_failed`; 409 `station_code_taken` |
| PUT | `/admin/stations/{id}` | 200 with the station; 400, 404, 409 as above |
| DELETE | `/admin/stations/{id}` | 204, or 404 |

Body of POST and PUT: `{ "code": "CAI", "name": "Cairo Ramses", "city": "Cairo" }`. `code` is 2 to 10 letters or digits
and is stored upper-case; `name` and `city` are required, at most 100 characters. A duplicate `code` is rejected by the
database's unique constraint (`ORA-00001`).

Errors always have one JSON shape: `{ "error": { "code": "...", "message": "...", "details": [ { "field", "message" } ] } }`
(`details` only for validation errors). Security failures use the same shape: `401 unauthenticated` or `invalid_token`,
`403 forbidden`.

## Database

Flyway migrations live in `src/main/resources/db/migration` and run at startup as the `train_schedule` Oracle user.
`V1` creates `station(id, code, name, city)` and `audit_log`.

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

Tests (no database needed; the security rules, validation and service logic):

```
./gradlew :services:train-schedule:test
```
