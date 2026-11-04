# 3. Train & Schedule service

## Purpose
Train & Schedule owns the **timetable data**: stations, trains, coaches (with standing capacity), seats, routes with ordered stops, fares per route
segment per coach class, and trips. Admins edit all of it. Passengers only **search trips** and read the station list.

It does not sell anything. Booking & Seat keeps its **own copy** of every trip (a "snapshot"), and Train & Schedule pushes that copy whenever a trip,
or something inside a trip, changes. A service never reads another service's tables, which is why the copy exists.

- Port **8081**. Oracle schema (database user) `train_schedule`. Spring Boot, Spring MVC, Spring JDBC (`JdbcClient`, no JPA, so the SQL is explicit), Flyway.
- Called by the **Gateway** with the user's Keycloak JWT, and by **Booking & Seat** with the shared internal API key.
- Calls **Booking & Seat** (`PUT/DELETE /internal/trips/{id}...`) on every trip change.

## Folder structure
Feature first, then layer (`controller` receives HTTP, `service` holds the rules, `repository` holds the SQL, `model` holds records, request bodies and exceptions).

```
services/train-schedule/
  build.gradle, README.md, .env.example
  src/main/resources/application.properties
  src/main/resources/db/migration/V1..V4 (Flyway; never edit an applied one)
  src/main/java/com/trainbooking/trainschedule/
    TrainScheduleApplication.java
    audit/       audit_log writing
    common/      error shape, handler, validation helpers, config beans, request log filter
    security/    resource-server setup, role mapping, internal API key chain
    station/ train/ coach/ seat/ route/ fare/ trip/   (each: controller, service, repository, model)
  src/test/java/...   the same packages
```

## Every file, one line each
### Root, audit, common, security
| File | What it does |
|---|---|
| `TrainScheduleApplication` | Entry point; `@SpringBootApplication` scans the packages and configures Spring from the classpath. |
| `audit/AuditLogRepository` | One `INSERT` into `audit_log`, in the caller's transaction. |
| `audit/AuditTrail` | Builds the before/after JSON, writes the row, and writes the structured `audit` log line **after the commit**. |
| `common/ApiError` | The one JSON error shape `{ error: { code, message, details? } }`. |
| `common/GlobalExceptionHandler` | Maps every exception to a status and a stable error code (400, 404, 409, 502, 503, 500). |
| `common/FieldProblems` | One message per field: "is required" wins over format rules; maps `coachClass` to the JSON name `class`. |
| `common/ListValidation` | Validates a JSON list item by item and reports every problem with its position (`[2].amount`). |
| `common/RequestValidationException` | A 400 with a list of field problems, thrown by services. |
| `common/SnapshotChanges` | Interface: "something inside the trip snapshots changed". Implemented by `TripSnapshotRefresher`. |
| `common/BookingSeatConfig` | Builds the `RestClient` for Booking & Seat (2 s connect, 5 s read timeout). |
| `common/TimeConfig` | Beans for the railway time zone and the UTC clock. |
| `common/RequestLogContext` | Filter: puts user id / booking id / trip id in the log context and writes one `access` line per request (path only). |
| `security/SecurityConfig` | Filter chain order 2: `/admin/**` needs ADMIN, everything else needs a valid token; stateless; JWT converter. |
| `security/InternalSecurityConfig` | Filter chain order 1 for `/internal/**`: only the shared API key. |
| `security/InternalApiKeyFilter` | Constant-time check of `X-Internal-Api-Key`; grants `ROLE_INTERNAL`. Not a bean. |
| `security/JsonErrorHandlers` | 401 / 403 answers in the shared JSON shape. |
| `security/KeycloakRealmRolesConverter` | `realm_access.roles` becomes `ROLE_USER` / `ROLE_ADMIN`. |

### Stations, trains, coaches, seats
| File | What it does |
|---|---|
| `station/controller/StationController` | Admin CRUD `/admin/stations`. |
| `station/controller/StationListController` | Read-only `GET /stations` for any signed-in user. |
| `station/service/StationService` | Rules, audit, snapshot re-push on rename; "in use" from the foreign key error. |
| `station/repository/StationRepository` | SQL for `station`. |
| `station/model/Station, StationRequest` | The record and the validated request body. |
| `station/model/StationCodeTakenException, StationInUseException, StationNotFoundException` | 409, 409, 404. |
| `train/controller/TrainController` | Admin CRUD `/admin/trains` with filters. |
| `train/service/TrainService` | Rules, filter parsing, audit with cascade counts, re-push. |
| `train/repository/TrainRepository` | SQL; the LIKE search with escaped wildcards. |
| `train/model/Train, TrainRequest, TrainStatus` | Record, request, `ACTIVE`/`INACTIVE`. |
| `train/model/TrainInUseException, TrainNotFoundException, TrainNumberTakenException` | 409, 404, 409. |
| `coach/controller/CoachController` | `/admin/trains/{id}/coaches` and `/admin/coaches/{id}`. |
| `coach/service/CoachService` | Rules; re-classes the seats when the class changes; re-push. |
| `coach/repository/CoachRepository` | SQL for `coach`. |
| `coach/model/Coach, CoachClass, CoachRequest` | Record, `FIRST/SECOND/THIRD`, request. |
| `coach/model/CoachCodeTakenException, CoachPositionTakenException, CoachNotFoundException` | 409, 409, 404. |
| `seat/controller/SeatController` | `/admin/coaches/{id}/seats` (bulk add) and `/admin/seats/{id}`. |
| `seat/service/SeatService` | All-or-nothing bulk add, clash checks, re-push. |
| `seat/repository/SeatRepository` | SQL for `seat`, JDBC batch insert. |
| `seat/model/Seat, SeatRequest, SeatConflictException, SeatNotFoundException` | Record, request, 409 with details, 404. |

### Routes, fares, trips
| File | What it does |
|---|---|
| `route/controller/RouteController` | Admin CRUD `/admin/routes`. |
| `route/service/RouteService` | Stop checks, `clearFares` rule, "trips use it" rule, audit, re-push. |
| `route/repository/RouteRepository` | SQL for `route` and `route_stop` (joined with `station`). |
| `route/model/Route, RouteStop, RouteRequest` | Records and the request. |
| `route/model/RouteHasFaresException, RouteInUseException, RouteNotFoundException` | 409, 409, 404. |
| `fare/controller/FareController` | `GET/PUT /admin/routes/{id}/fares`. |
| `fare/service/FareService` | Replace the whole fare set, all or nothing; EGP, 2 decimals. |
| `fare/repository/FareRepository` | SQL for `fare`, batch insert. |
| `fare/model/Fare, FareRequest` | Record and request item. |
| `trip/controller/TripController` | Admin CRUD `/admin/trips`. |
| `trip/controller/TripSearchController` | `GET /trips` passenger search. |
| `trip/controller/TripLayoutController` | `GET /trips/{id}/layout` for the Gateway's seat map. |
| `trip/controller/InternalTripController` | `POST /internal/trips/{id}/cancel`, called by Booking & Seat. |
| `trip/service/TripService` | Create/update/delete/cancel a trip; all the create rules; pushes the snapshot last. |
| `trip/service/TripTimeline` | Pure logic: `HH:mm` times become date-times, past-midnight roll, 24 h limit. |
| `trip/service/TripSearchService` | Search window, today rule, next available date, journey fares. |
| `trip/service/TripLayoutService` | Builds the seat-map layout from the train, coaches and seats. |
| `trip/service/TripSnapshotPublisher` | Builds the snapshot and sends it (or asks to drop it). |
| `trip/service/TripSnapshotRefresher` | Implements `SnapshotChanges`: re-pushes the affected future trips just before the commit. |
| `trip/service/BookingSeatClient` | HTTP calls to Booking; turns answers into three exceptions. |
| `trip/repository/TripRepository` | SQL for `trip`, `trip_stop`, search, "future active trips of a train/route/station". |
| `trip/model/Trip, TripStop, TripStatus, TripRequest, StopTimeRequest` | Records, enum, request bodies. |
| `trip/model/TripLayout, TripSearchResponse, TripSearchResult, JourneyFare` | Response shapes. |
| `trip/model/BookingSnapshot` | The snapshot sent to Booking (train, route name, stops, coaches, seats, fares). |
| `trip/model/BookingConflictException, BookingSnapshotRejectedException, BookingUnavailableException` | 409, 502, 503. |
| `trip/model/TrainInactiveException, TrainHasNoCoachesException, RouteFaresIncompleteException, TripNotFoundException` | 409, 409, 409 (with details), 404. |

### Tests (`src/test/java/...`, same packages)
| File | What it tests |
|---|---|
| `*ControllerTest` (11 files: station, station list, train, coach, seat, route, fare, trip, trip layout, trip search, internal trip) | The web layer with MockMvc: 401/403, status codes, JSON field names, validation details, error codes. Service mocked. |
| `*ServiceTest` (station, train, coach, seat, route, fare, trip, trip search, trip layout) | The rules with mocked repositories. |
| `TripTimelineTest`, `FieldProblemsTest`, `KeycloakRealmRolesConverterTest`, `TrainRepositoryTest` | Pure logic. |
| `BookingSeatClientTest` | The HTTP contract with Booking, using `MockRestServiceServer`. |
| `TripSnapshotPublisherTest`, `TripSnapshotRefresherTest` | Snapshot content, and "pushed once, just before the commit". |
| `testsupport/TestJwts` | Fake admin and passenger authentication for MockMvc. |

## Endpoints
All JSON. Errors have the shape `{ "error": { "code", "message", "details"? } }`. Browsers call the Gateway, never this service directly.

| Method and path | Auth | Request | Response | Errors |
|---|---|---|---|---|
| `GET /admin/stations`, `GET /admin/stations/{id}` | ADMIN | none | station(s) `{ id, code, name, city }` | 404 `station_not_found` |
| `POST /admin/stations` | ADMIN | `{ code, name, city }` | 201 station + `Location` | 400, 409 `station_code_taken` |
| `PUT /admin/stations/{id}` | ADMIN | same | station | 400, 404, 409, plus Booking errors (below) |
| `DELETE /admin/stations/{id}` | ADMIN | none | 204 | 404, 409 `station_in_use` |
| `GET /stations` | any signed-in user | none | station list ordered by code | 401 |
| `GET /admin/trains?number=&name=&status=` | ADMIN | none | trains ordered by number | 400 (bad status) |
| `POST /admin/trains`, `PUT /admin/trains/{id}` | ADMIN | `{ number, name, type, status }` | train (201 on create) | 400, 404, 409 `train_number_taken` |
| `GET /admin/trains/{id}`, `DELETE ...` | ADMIN | none | train / 204 | 404, 409 `train_in_use` |
| `GET /admin/trains/{id}/coaches` | ADMIN | none | coaches in train order | 404 |
| `POST /admin/trains/{id}/coaches` | ADMIN | `{ code, class, position, standingCapacity }` | 201 coach | 400, 404, 409 `coach_code_taken` / `coach_position_taken` |
| `PUT/DELETE /admin/coaches/{id}` | ADMIN | same / none | coach / 204 | 404, 409 |
| `GET /admin/coaches/{id}/seats` | ADMIN | none | seats by row, column | 404 |
| `POST /admin/coaches/{id}/seats` | ADMIN | list of `{ seatNumber, row, column }` (1 to 200) | 201 created seats | 400 (position in list), 409 `seat_conflict` |
| `PUT/DELETE /admin/seats/{id}` | ADMIN | `{ seatNumber, row, column }` / none | seat / 204 | 400, 404, 409 |
| `GET /admin/routes`, `GET /admin/routes/{id}` | ADMIN | none | routes with stops (station code, name, city) | 404 |
| `POST /admin/routes` | ADMIN | `{ name, stops: [stationId...] }` (2 to 50, each once) | 201 route | 400 (names the bad stop) |
| `PUT /admin/routes/{id}?clearFares=true` | ADMIN | same | route | 409 `route_has_fares`, 409 `route_in_use` |
| `DELETE /admin/routes/{id}` | ADMIN | none | 204 | 409 `route_in_use` |
| `GET /admin/routes/{id}/fares` | ADMIN | none | `[ { segmentIndex, class, amount, currency } ]` | 404 |
| `PUT /admin/routes/{id}/fares` | ADMIN | list of `{ segmentIndex, class, amount }` | new list | 400, 404, 409 `booking_conflict` (fares frozen) |
| `GET /admin/trips[?date=]` | ADMIN | none | trips with stops (local date-times) | 400 |
| `POST /admin/trips`, `PUT /admin/trips/{id}` | ADMIN | `{ trainId, routeId, departureDate, stops: [{ arrivalTime, departureTime }] }` | trip (201 on create) | 400, 409 `train_inactive` / `train_has_no_coaches` / `route_fares_incomplete`, Booking errors |
| `GET /admin/trips/{id}`, `DELETE ...` | ADMIN | none | trip / 204 | 404, Booking 409 |
| `GET /trips?from=&to=&date=` | any signed-in user | station codes, `yyyy-MM-dd` | `{ trips, nextAvailableDate? }` | 400 `missing_parameter`, `invalid_parameter`, `validation_failed` |
| `GET /trips/{id}/layout` | any signed-in user | none | trip, stops, coaches with seats and standing capacity | 404 |
| `POST /internal/trips/{id}/cancel` | internal API key | optional header `X-Acting-User` | `{ id, status }` | 401 `invalid_api_key`, 404 |

**Booking & Seat errors on any trip-changing call** (nothing is saved): 409 `booking_conflict` (holds or bookings, frozen trip or fares), 502
`booking_rejected_snapshot` (Booking found the snapshot invalid: our data bug), 503 `booking_unavailable` (unreachable or timed out).
The Gateway forwards the exact GET paths `/trips` and `/stations` to passengers; `/trips/{id}/layout` is called by the Gateway itself when it composes the seat map, and has no public route.

## Tables (Flyway V1 to V4)
| Table | Columns and constraints |
|---|---|
| `station` (V1) | `id`, `code` (unique), `name`, `city`. |
| `audit_log` (V1) | `id`, `admin_id` (JWT `sub`), `entity`, `entity_id`, `action` (`CREATE/UPDATE/DELETE/ADD_SEATS/SET_FARES/CANCEL`), `diff` (JSON, CLOB), `created_at`. |
| `train` (V2) | `id`, `train_number` (unique; "number" is reserved in Oracle), `name`, `type` (free text), `status` (`ACTIVE/INACTIVE`). |
| `coach` (V2) | `id`, `train_id` (FK, cascade), `code`, `class`, `position` (1 or more), `standing_capacity` (0 or more); unique `(train_id, code)` and `(train_id, position)`. |
| `seat` (V2) | `id`, `coach_id` (FK, cascade), `seat_number`, `seat_row`, `seat_column` ("row"/"column" are reserved), `class`; unique `(coach_id, seat_number)` and `(coach_id, seat_row, seat_column)`. |
| `route`, `route_stop` (V3) | `route(id, name)`; `route_stop(route_id, stop_index, station_id)`, primary key `(route_id, stop_index)`, unique `(route_id, station_id)`, FK to `station` (no cascade: that is why a used station cannot be deleted). |
| `fare` (V3) | primary key `(route_id, segment_index, coach_class)`, `amount NUMBER(10,2) > 0`, `currency` fixed `'EGP'`. |
| `trip` (V4) | `id`, `train_id`, `route_id` (FKs, no cascade: a used train or route cannot be deleted), `departure_date` (DATE), `status` (`ACTIVE/CANCELLED`). |
| `trip_stop` (V4) | primary key `(trip_id, stop_index)`, `station_id`, `arrival_time`, `departure_time` (`TIMESTAMP WITH TIME ZONE`, at least one not null); cascade with the trip. |

## Background jobs
None. Everything happens inside the request. (Retried calls live in other services.)

## Configuration
`application.properties` (names only; values come from the environment or a git-ignored `.env`, see `.env.example`):

| Property / variable | Meaning |
|---|---|
| `server.port` | 8081. |
| `TRAIN_SCHEDULE_DB_URL`, `TRAIN_SCHEDULE_DB_USER`, `TRAIN_SCHEDULE_DB_PASSWORD` | Oracle connection of this service's own schema. Flyway uses the same user. |
| `KEYCLOAK_ISSUER` | Issuer of the tokens; Spring finds the signing keys from it (looked up on the first request, so the service can start before Keycloak). |
| `BOOKING_SEAT_URL` | Base URL of Booking & Seat. |
| `INTERNAL_API_KEY` | Shared service-to-service key. **No default**: the service does not start without it. |
| `TRAINBOOKING_TIMEZONE` | Railway time zone (default `Africa/Cairo`). Trip times are entered and shown in it. |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE`, OpenTelemetry variables | Set by Compose (JSON logs, traces to OpenObserve); see chapter 10. |

## Important code paths, step by step
### A. Admin creates a trip (the main path)
1. Gateway checks token and ADMIN, forwards `POST /admin/trips`. `SecurityConfig` checks again. `TripController.create` validates the body (`@Valid`).
2. `TripService.create` (one transaction) calls `plan(...)`: train and route exist (400 otherwise), train is `ACTIVE`, train has coaches, stop count equals route stop count,
   `TripTimeline.resolve` turns `HH:mm` into date-times (a time earlier than the previous one means next day; max 24 h), and `requireCompleteFares` checks there is a fare for every segment and every class the train has.
3. `TripRepository.insert` + `insertStops` (JDBC batch), then `AuditTrail.change` (row now, log line after commit).
4. **Last step:** `TripSnapshotPublisher.publish` builds the snapshot and `BookingSeatClient.putSnapshot` sends it. If Booking says 409/400/5xx or is down, an exception is thrown and
   Spring **rolls back everything above**. A trip therefore exists here only if Booking accepted it.

### B. Editing something inside trips (train, coach, seat, fare, route, station)
1. The edit service saves its change and writes its audit row, then calls `SnapshotChanges.trainChanged / routeChanged / stationChanged`.
2. `TripSnapshotRefresher.register` stores the change on the current transaction and registers a `beforeCommit` callback once. Several edits in one request give one push per trip.
3. Just before the commit the callback finds the **future, ACTIVE** trips (first stop departs after now) of that train/route/station and publishes each once.
   If Booking refuses (for example a reserved seat would be removed: `capacity_conflict`), the commit fails and the edit is rolled back.
   Trips that already left keep their old copy.

### C. Trip search
`TripSearchService.search`: both station codes must exist and differ; the window is the local day (for today it starts at "now"); one SQL query finds trips whose origin stop comes
before the destination stop; the stops of all matches are loaded with one query; fares are the sum of segments `fromStop..toStop-1` per class. No match: the next date with a trip is returned.
The Gateway then adds seats left from Booking & Seat.

### D. Whole-trip cancel (Flow 8, step 3)
Booking calls `POST /internal/trips/{id}/cancel` with the API key; `TripService.markCancelled` sets `CANCELLED` (idempotent) and audits. No snapshot is pushed back.

## Tests
`./gradlew :services:train-schedule:test`: **209 tests, all pass** at the time of this stage. None need Oracle or a running Keycloak (controller tests use MockMvc with fake authentication; services use Mockito mocks).

## Known limits
See also chapter 13. For this service:
- Duplicate and "in use" errors are recognised by **searching the database error text for a constraint name** (`UK_COACH_POSITION`, `FK_ROUTE_STOP_STATION` ...). Renaming a constraint in a migration would silently turn these 409 answers into 500.
- `TripRepository.findStops` uses an `IN (:ids)` list; Oracle allows at most 1000 values (`ORA-01795`). A search or admin list returning more than 1000 trips would fail.
- `GET /admin/trips` and the admin lists have no paging.
- `TripLayoutService` runs one seat query per coach.
- The push to Booking happens inside the database transaction, so an admin request holds a connection while Booking answers (up to 5 s read timeout).
  The rare gap (Booking accepts, then the local commit fails) is documented in `TripSnapshotPublisher`.
- A trip's `status` can be `CANCELLED` and `TripService.update` does not check it; Booking refuses the push (`trip_not_open`), which protects the data.
- `JsonErrorHandlers.handle` always says "The ADMIN role is required", also for the internal chain.
- `audit_log.admin_id` is 64 characters; the `X-Acting-User` header is stored as given, so a longer value would make the cancel fail with a 500.
