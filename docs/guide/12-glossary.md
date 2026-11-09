# 12. Glossary

Alphabetical. Each entry: plain words, then the file where it is used most clearly (paths from the repository root; `B` = `services/booking-seat/src/main/java/com/trainbooking/bookingseat`).

## Annotations and keywords (Java, Spring, validation)

- **`@AuthenticationPrincipal`**: asks Spring to pass the logged-in principal (here the decoded `Jwt`) into a controller method. `B/booking/controller/BookingController.java`.
- **`@Bean`**: a method whose return value Spring creates once and shares (a clock, an HTTP client, a security chain). `B/common/TimeConfig.java` (and the `*Config` classes).
- **`@Component`**: a class Spring creates and injects by itself (no more specific role). `B/live/HandshakeAuthentication.java`.
- **`@Configuration`**: a class that holds `@Bean` methods. `B/live/WebSocketConfig.java`.
- **`@ConditionalOnMissingBean`**: create this bean only if nobody else defined one (lets tests replace it). `services/payment/src/main/java/com/trainbooking/payment/stripe/StripeConfig.java`.
- **`@ConditionalOnProperty`**: create this bean only if a configuration property has a given value. `services/payment/.../stripe/StripeConfig.java`.
- **`@DecimalMin`, `@Digits`, `@Max`, `@Min`, `@NotBlank`, `@NotEmpty`, `@NotNull`, `@Pattern`, `@Size`**: input validation rules on request fields (money above zero with two decimals, text length, regex, not empty). Checked when the controller parameter has `@Valid`. `B/booking/model/IssueRequest.java`.
- **`@EnableScheduling`**: switches on `@Scheduled` jobs. The `*Application` class or a config of each service.
- **`@EnableWebSecurity`**: switches on Spring Security's web rules; the `SecurityConfig` class defines the filter chains. `services/payment/.../security/SecurityConfig.java`.
- **`@EnableWebSocketMessageBroker`**: switches on STOMP over WebSocket with a message broker. `B/live/WebSocketConfig.java`.
- **`@ExceptionHandler`**: a method that turns one exception type into an HTTP answer (the shared `{ error: { code, message } }` JSON). `B/common/GlobalExceptionHandler.java`.
- **`@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, `@RequestMapping`**: bind a controller method to an HTTP method and path. `B/booking/controller/BookingController.java`.
- **`@JsonInclude`, `@JsonProperty`**: control the JSON field names and whether empty fields are written. The record classes in `model/`.
- **`@Order`**: the position of a filter chain or filter when there are several. `SecurityConfig` classes.
- **`@Override`**: marks that a method replaces one from an interface or parent class; the compiler checks it exists.
- **`@Repository`**: marks a class that does database access. `B/booking/repository/BookingRepository.java`.
- **`@RequestHeader`, `@RequestParam`, `@PathVariable`, `@RequestBody`**: read a value from a header, query string, path segment or JSON body into a method parameter. `B/booking/controller/AdminBookingController.java`.
- **`@RestController`**: a controller whose return values are written as JSON. Every `*Controller`.
- **`@RestControllerAdvice`**: a class that applies `@ExceptionHandler`s to every controller. `B/common/GlobalExceptionHandler.java`.
- **`@Scheduled`**: run this method repeatedly on a timer. `fixedDelay` waits a fixed time after the end of the previous run, so runs never overlap. `B/booking/service/HoldSweeper.java`.
- **`@Service`**: a class holding business logic. `B/booking/service/HoldService.java`.
- **`@SpringBootApplication`**: the entry class; enables component scanning and auto-configuration. Each `*Application.java`.
- **`@SuppressWarnings`**: silences a compiler warning on purpose.
- **`@Transactional`**: run the method inside a database transaction (commit on success, roll back on a runtime exception). `propagation = MANDATORY` means "fail if no transaction is open", used to guarantee that helper code runs inside the caller's transaction. `B/booking/service/BookingLocks.java`.
- **`@TransactionalEventListener(AFTER_COMMIT)`**: runs a listener only after the surrounding transaction has committed, so nothing is announced that could still be rolled back. `B/live/LiveUpdates.java`.
- **`@Valid`**: validate the request object with the rules above before the method runs. `B/booking/controller/BookingController.java`.
- **`@Value`**: inject a configuration property or environment value into a field or constructor parameter. `B/board/service/BoardService.java`.
- **`final`, `record`, `sealed`, `switch ->`**: Java language keywords. A `record` is an immutable data class (used for DTOs and results); `switch` with `->` is the expression form. `B/booking/service/CancellationService.java`.

## Patterns, terms and technologies

- **ABANDONED / EXPIRED / PENDING_PAYMENT / CONFIRMED / CANCELLED**: booking statuses. See chapter 1.
- **Access token**: the JWT the browser sends to the Gateway. `frontend/src/auth/keycloak.ts`.
- **Actor**: who did an action (the user's or admin's `sub`); stored as `cancelled_by` and in the audit log. `B/booking/service/CancellationService.java`.
- **After commit**: see `@TransactionalEventListener`.
- **All or nothing**: one transaction for a whole pick or a whole bulk create; if any part fails, nothing is saved. `B/booking/service/HoldService.java`.
- **Audit log**: table and log line recording every admin action and booking state change. `B/audit/AuditTrail.java`.
- **Authorization code flow with PKCE**: the login method of the browser with Keycloak: the browser gets a one-time code and exchanges it, proving ownership with a secret it generated (PKCE). No password passes through our code. `frontend/src/auth/keycloak.ts`.
- **Backoff**: waiting longer after each failure (2, 4, 8 ... seconds up to a limit) before retrying. `B/booking/service/ExpiryService.java` (`retryLater`).
- **Board**: the admin page that lists trips with live seat counts. `B/board/service/BoardService.java`, `frontend/src/pages/TripsBoard.tsx`.
- **BLOB**: Oracle column type for binary data (the PDF). `services/ticket/.../ticket/repository/TicketRepository.java`.
- **Client secret (Stripe)**: a one-use string that lets the browser confirm one PaymentIntent. `services/payment/.../payment/model/PaymentSession.java`.
- **Compose**: Docker Compose, the file `docker-compose.yml` that starts all services.
- **Constraint (primary key, unique, foreign key, check)**: rules the database enforces itself. `services/booking-seat/src/main/resources/db/migration/V1__create_snapshot_and_reservation_tables.sql`.
- **Controller / Service / Repository**: the three Spring layers: HTTP in and out; rules; SQL.
- **Counter booking / complimentary booking**: a booking an admin confirms without Stripe (paid in person, or free). `B/booking/service/BookingIssueService.java`.
- **CORS / origin**: the browser rule about which web address may call which server; the WebSocket also checks the origin. `gateway/src/wsproxy.js`.
- **Cutoff**: the last moment a booking can be cancelled: the departure of the booking's own boarding stop. `B/booking/service/CancellationService.java`.
- **Dedupe key**: a caller-chosen id so the same email request twice creates one email. `services/notification/.../NotificationService.java`.
- **DTO**: data transfer object, a plain class or record for a request or response.
- **Express / middleware**: Node.js web framework; middleware are functions run in order for each request. `gateway/src/app.js`.
- **fixedDelay**: see `@Scheduled`.
- **Flyway migration**: a numbered SQL file (`V1__...sql`) run once in order; never edited afterwards because Flyway checks its checksum. `services/*/src/main/resources/db/migration/`.
- **Gateway**: the Express app on port 8080, the only entry from the browser. `gateway/src/app.js`.
- **Hold**: seats reserved for 5 minutes while the user pays. `B/booking/service/HoldService.java`.
- **Idempotent**: doing it twice has the same result as once. Used for snapshots, tickets, webhooks, issue and cancel.
- **Idempotency key (Stripe)**: a string that makes Stripe return the same object for a repeated request. `services/payment/.../payment/service/PaymentService.java`.
- **`JdbcClient`**: Spring's small SQL helper with named parameters (`:name`); we use it instead of JPA so the SQL stays visible. Every `*Repository`.
- **JWKS**: the list of Keycloak's public keys used to verify token signatures. `gateway/src/auth.js`.
- **JWT**: a signed token that carries the user id (`sub`), the roles and the expiry. `gateway/src/auth.js`.
- **Keycloak**: the login server. `infra/keycloak/`.
- **Live update**: a seat or hold change pushed over WebSocket. `B/live/LiveUpdates.java`, `frontend/src/live/connectLive.ts`.
- **Lock (row lock)**: `SELECT ... FOR UPDATE` makes other transactions wait for the row. The fixed order is trip, booking, seats or standing, version. `B/booking/service/BookingLocks.java`.
- **MDC**: per-thread logging context (user id, booking id, trip id) added to each log line. `B/common/RequestLogContext.java`.
- **`MERGE`**: Oracle's insert-or-update statement (used for the snapshot). `B/snapshot/repository/`.
- **OpenObserve / OpenTelemetry**: where logs and traces are collected; OpenTelemetry is the standard used to send them. `infra/docker/entrypoint-java.sh`, `docker-compose.yml`.
- **ORA-00001 / ORA-00060**: Oracle errors: duplicate key (read as "seat taken") and deadlock (read as a conflict).
- **Outbox**: a table of jobs written in the same transaction as the state change and delivered later by a poller with retries. `B/outbox/service/OutboxWriter.java`, `OutboxDispatcher.java`.
- **Payment Element / PaymentIntent**: Stripe's embedded card form (in the browser) and Stripe's object for one payment attempt. `frontend/src/components/StripeCheckout.tsx`, `services/payment/.../stripe/StripeApiGateway.java`.
- **Payment wins**: a payment that succeeded confirms the booking even if the hold has just expired. `B/booking/service/PaymentResultService.java`.
- **Poller**: a `@Scheduled` job that looks for due work.
- **Port (hexagonal)**: an interface a service uses to talk to the outside (`PaymentPort`, `TicketPort`, `NotificationPort`), with a client class implementing it; tests use fakes. `B/payment/PaymentPort.java`.
- **Resource server**: a Spring service that accepts and validates JWTs. `SecurityConfig` classes.
- **Role**: `USER` or `ADMIN`, a realm role in Keycloak. `gateway/src/auth.js` (`requireRole`).
- **Segment**: the leg between two consecutive stops; the unit of availability. `B/availability/service/SegmentAvailability.java`.
- **Sequence / identity column**: Oracle ways to generate ids.
- **Snapshot**: the immutable copy of a trip (coaches, seats, stops, fares) that Train & Schedule pushes to Booking. `B/snapshot/service/`, `TS/trip/service/TripSnapshotPublisher.java`.
- **Standing capacity**: places without a seat; counted per coach and per segment in `standing_usage`. `B/availability/service/SegmentAvailability.java`.
- **STOMP**: a simple messaging protocol on top of WebSocket (subscribe to topics). `B/live/WebSocketConfig.java`, `frontend/src/live/connectLive.ts`.
- **Sweeper**: the 1-second job that releases expired holds. `B/booking/service/HoldSweeper.java`, `ExpiryService.java`.
- **`sub`**: the JWT claim holding the user id; used as a plain value, never a foreign key.
- **Tombstone**: a closed payment row without an intent that makes a late create request fail. `services/payment/.../payment/service/PaymentService.java`.
- **TransactionTemplate**: programmatic transactions (run a lambda in a transaction), used when a method must do remote calls outside the transaction. `B/booking/service/PaymentStartService.java`.
- **Version (per trip)**: a counter bumped in each hold transaction so the browser can detect missed events. `B/live/AvailabilityEvents.java`, `frontend/src/live/applySeatEvent.ts`.
- **Webhook**: a request Stripe sends to us when something happens; verified by signature, handled once per event id. `services/payment/.../webhook/service/WebhookService.java`.
- **WebSocket**: a long-lived two-way connection used for live updates.

## Frontend terms (React)

- **Component / props / state**: a function that returns screen elements; props come from the parent, state is data the component owns. `frontend/src/App.tsx`.
- **Context**: shared data for many components without passing props down (the auth state). `frontend/src/auth/AuthContext.tsx`.
- **Effect (`useEffect`)**: code that runs after render, with a cleanup function that runs on unmount or before the next run (timers, sockets). `frontend/src/useBoardData.ts`.
- **Guard**: a route wrapper that redirects when the user is not signed in or not an admin. `frontend/src/auth/guards.tsx`.
- **Hook**: a function named `use...` that adds state or effects to a component. `frontend/src/useBoardData.ts`.
- **Route**: a path mapped to a page. `frontend/src/App.tsx`.
- **Vite**: the dev server and build tool. `frontend/vite.config.ts`.
