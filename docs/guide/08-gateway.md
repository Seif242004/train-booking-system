# 8. API Gateway

## Purpose
The Gateway is the **single entry point** of the system. The browser (and nobody else) calls it. It does four jobs:

1. **Checks the login.** Every request except `/health` and the Stripe webhook must carry a Keycloak access token. The Gateway checks the token's
   signature, issuer and expiry, and reads the realm roles (`USER`, `ADMIN`).
2. **Checks the role.** Admin routes answer `403` to a normal user, so the request never reaches a service.
3. **Routes.** It forwards each call to the service that owns it, with the caller's token (each Spring service checks the token again).
4. **Composes.** A few answers need two services. The Gateway asks both and merges the result: trip search, seat map, board detail, ticket PDF/QR.

It also proxies the **WebSocket** for live seat updates, forwards the **Stripe webhook**, and ships its own logs and traces to OpenObserve.

The Gateway has no database, no sessions, no login page and never sees a password. The browser talks to Keycloak directly (authorization code flow
with PKCE). The Gateway only downloads Keycloak's **public keys** (JWKS, a list of keys) to verify tokens.

- Port **8080** (`PORT`). Node.js 22 and Express 5. No proxy library: forwarding uses Node's built-in `fetch`.
- Calls: Train & Schedule (8081), Booking & Seat (8082), Payment (8083, webhook only), Ticket (8084, internal key), Keycloak (keys), OpenObserve.

## Folder structure
```
gateway/
  package.json, package-lock.json, README.md
  src/   server.js app.js auth.js config.js errors.js proxy.js search.js seatmap.js board.js ticket.js wsproxy.js log.js accesslog.js telemetry.js
  test/  *.test.js   (node:test, the Node built-in runner; run with `npm test`)
```

## Every file, one line each
| File | What it does |
|---|---|
| `src/server.js` | Entry point. Imports `telemetry.js` first, builds the app, listens on `HOST:PORT`, attaches the WebSocket proxy. |
| `src/app.js` | `createApp()`: CORS, access log and every route (table below), the 404 fallback and the error handler. |
| `src/auth.js` | `authenticate()` verifies a bearer token against Keycloak's keys; `requireAuth` and `requireRole(role)` middleware. |
| `src/config.js` | All settings from environment variables, with development defaults. |
| `src/errors.js` | `HttpError` and `sendError`: the one JSON error shape `{ error: { code, message } }`. |
| `src/proxy.js` | `forwardTo(baseUrl, pathPrefix, {requestHeaders})`: the generic forwarder. |
| `src/search.js` | `tripSearch()`: trips from Train & Schedule plus availability per trip from Booking & Seat. |
| `src/seatmap.js` | `composeSeatMap` (pure) and `seatMap()`: layout plus availability for one journey. |
| `src/board.js` | `composeBoardDetail` (pure) and `boardTripDetail()`: the admin board detail of one trip. |
| `src/ticket.js` | `ticketPdf()` and `ticketQr()`: ownership check, then the file from the internal Ticket service. |
| `src/wsproxy.js` | `attachWebSocketProxy(server)`: checks the token on the HTTP upgrade of `/ws`, then pipes the socket to Booking & Seat. |
| `src/log.js` | Structured JSON logs on the console, shipped in batches to the OpenObserve stream `gateway`. |
| `src/accesslog.js` | Middleware: one `request` log line per finished request (path only). |
| `src/telemetry.js` | OpenTelemetry traces to OpenObserve; cuts query strings from span attributes. Does nothing unless `OTEL_EXPORTER_OTLP_ENDPOINT` is set. |
| `test/accesslog.test.js` | One log line per request; no query string or token in it; the span redactor. |
| `test/adminbookings.test.js` | Admin bookings and trip-cancel routes refuse a missing or garbage token. |
| `test/board.test.js` | `composeBoardDetail`, the board list and detail routes, 401/403 behaviour. |
| `test/counter.test.js` | Counter/complimentary admin routes need a token; hold expiry on the board detail. |
| `test/search.test.js` | Search composition with fake services: availability, timeouts, null on failure, relayed errors. |
| `test/seatmap.test.js` | Seat map composition, validation of ids, relayed 404/400, 502/504. |
| `test/ticket.test.js`, `test/ticketqr.test.js` | PDF and QR routes: ownership, admin, 409 codes, internal key towards Ticket. |
| `test/webhook.test.js` | Webhook forwarded byte for byte, only two headers, POST on the exact path only, 502 when Payment is down. |
| `test/wsproxy.test.js` | Token from the subprotocol, 401/403/404/502/503 refusals, piping both ways, nothing sensitive in the log. |

## Authentication and roles (`auth.js`)
1. `requireAuth` reads `Authorization: Bearer <jwt>`. A missing header gives `401 unauthenticated`.
2. `jwtVerify` (library `jose`) checks: signature with the key named by the token's `kid` (only `RS256` is accepted, which blocks `alg: none` tricks),
   issuer equal to `KEYCLOAK_ISSUER`, expiry (5 s clock tolerance). A bad token gives `401 invalid_token`.
3. No audience check on purpose: Keycloak puts `aud: account` in these tokens and the Gateway is not a Keycloak client.
4. The keys are cached for 10 minutes; an unknown `kid` refreshes them at most every 30 s; a hung Keycloak times out after 5 s. If the keys cannot be
   fetched the answer is `503 auth_unavailable` (an outage is not the caller's fault).
5. The caller becomes `req.user = { sub, name, email, roles }`; roles come only from `realm_access.roles`.
6. `requireRole('ADMIN')` answers `403 forbidden` when the role is missing.

## Routes
"Any user" means any valid token. All forwarded routes pass `Authorization`, `Content-Type` and `Accept` only (cookies and `Host` stay behind).

| Method and path | Auth | What happens |
|---|---|---|
| `GET /health` | none | `{ "status": "UP" }`. Not logged. |
| `GET /me` | any user | `{ sub, name, email, roles }` from the token. |
| `GET /admin/ping` | ADMIN | `{ message: "pong", admin: sub }`; shows the role check. |
| `POST /admin/trips/{id}/cancel` | ADMIN | Forward to Booking & Seat (Flow 8, cancel the whole trip). Registered before the generic trip rules. |
| `GET /admin/trips/{id}/cancellation` | ADMIN | Forward to Booking & Seat (progress of the trip cancellation). |
| `GET /admin/passengers/lookup` | ADMIN | Forward to Booking & Seat (one passenger by exact email for a counter booking, else 404). |
| `GET /admin/board/trips` | ADMIN | Forward to Booking & Seat (live trips board list, query string kept). |
| `GET /admin/board/trips/{id}` | ADMIN | **Composed** by `board.js` (layout from Train & Schedule + seat states from Booking & Seat). |
| `ANY /admin/stations`, `/admin/trains`, `/admin/coaches`, `/admin/seats`, `/admin/routes`, `/admin/trips` (and below) | ADMIN | Forward to Train & Schedule. |
| `GET /trips?from=&to=&date=` | any user | **Composed** by `search.js`. |
| `GET /stations` | any user | Forward to Train & Schedule (station list for the search page). |
| `GET /trips/{id}/seat-map?from=&to=` | any user | **Composed** by `seatmap.js`. |
| `GET /bookings/{id}/ticket` | any user (owner or ADMIN) | PDF through `ticket.js`. |
| `GET /bookings/{id}/ticket/qr` | any user (owner or ADMIN) | QR PNG through `ticket.js`. |
| `ANY /bookings` (and below) | any user | Forward to Booking & Seat: pick, release, hold, payment, cancel, my bookings, resend ticket. Booking checks the owner and answers 404 for other users. |
| `ANY /admin/bookings` (and below) | ADMIN | Forward to Booking & Seat: support search, one booking, admin cancel, counter and complimentary issue. |
| `POST /webhooks/payment` | none (Stripe signature) | Forward to Payment with the raw body; only `Content-Type` and `Stripe-Signature` are passed. |
| `WebSocket /ws` | any user (token in the subprotocol) | Proxy to Booking & Seat (see below). |
| anything else | n/a | `404 not_found`. |

Route order matters in Express: the first match wins. So the two exact trip-cancel routes come before the `/admin/trips` prefix, the two ticket routes
before the `/bookings` prefix, and `/trips` and `/stations` are exact paths so that `/trips/{id}/layout` and `/trips/{id}/availability` have **no
public route** (they are called only by the Gateway itself). `/internal/**` is never forwarded.

### Errors the Gateway produces
| Status | Code | When |
|---|---|---|
| 400 | `bad_request`, `validation_failed` | an id or stop index that is not plain digits; a path that would leave the service |
| 401 | `unauthenticated`, `invalid_token` | no token; bad or expired token |
| 403 | `forbidden` | the role is missing |
| 404 | `not_found`, `booking_not_found` | unknown route; not your booking (ticket routes) |
| 409 | `ticket_not_available`, `ticket_not_ready` | booking not confirmed; ticket still being made |
| 500 | `internal_error` | unexpected error (logged, no details sent) |
| 502 | `upstream_unavailable` | a service is down or answered nonsense |
| 503 | `auth_unavailable`, `ticket_unavailable` | Keycloak keys unreachable; `INTERNAL_API_KEY` empty |
| 504 | `upstream_timeout` | a service did not answer within `UPSTREAM_TIMEOUT_MS` |

Errors from the services (their own `{ error: { code, message } }` bodies) are relayed unchanged.

## Code paths, step by step
### The generic forwarder (`proxy.js`, `forwardTo`)
1. Builds the target URL from the original URL (path and query string) and the service's base URL.
2. Refuses (`400`) if the origin differs or the path is outside the allowed prefix.
3. Copies only the allowed request headers; sends the body only when `express.raw` produced a non-empty Buffer.
4. `fetch` with `redirect: 'manual'` and a timeout. Relays status, body and `Content-Type`, `Location`, `WWW-Authenticate`.
5. Timeout gives `504`; any other failure gives `502` and a log line with the host only.

### Trip search (`search.js`)
Ask Train & Schedule for `/trips`. If it is not a `200` with a `trips` array, relay it as it is. Otherwise ask Booking & Seat for
`/trips/{id}/availability?from=&to=&view=summary` for every trip **at the same time** (timeout `AVAILABILITY_TIMEOUT_MS`) and add
`availability: { seatsLeft, standingLeft }`, or `null` when Booking cannot answer. A Booking outage must not hide the timetable.

### Seat map (`seatmap.js`)
Validates the id, `from` and `to` (digits only). Asks Train & Schedule for the layout and Booking & Seat for the availability together. A 4xx from either is
relayed, other failures are `502`. `composeSeatMap` gives each seat its state (a seat Booking does not know is `BOOKED`, never free), each coach its standing
places, and adds the journey (stop times), fares, summary, version and the caller's own `myHold`.

### Board detail (`board.js`)
Same pattern for admins: layout plus Booking's admin board view for the full journey. Seat states are `FREE`, `HELD`, `TAKEN`; a booking reference appears only
on `TAKEN`, the hold expiry only on `HELD`; an unknown seat is `TAKEN`. Answer is `private, no-store`.

### Ticket (`ticket.js`)
1. Check the id and that `INTERNAL_API_KEY` is set. 2. Ask Booking `GET /bookings/{id}` **with the caller's token**: `404` for someone else's booking.
3. If that was a 404 and the caller is ADMIN, ask `/admin/bookings/{id}`. 4. Only `CONFIRMED` bookings have a ticket (`409` otherwise).
5. Fetch `/internal/tickets/{id}/pdf` (or `/qr`) from Ticket with the **internal key** (the user token is not sent). A `404` there means "not made yet": `409 ticket_not_ready`.
6. Send the bytes with `attachment` (PDF) and `private, no-store`. The file name uses only letters, digits, `-` and `_`.

### WebSocket (`wsproxy.js`)
Browsers cannot set headers on a WebSocket and the URL must stay out of logs, so the token travels as a subprotocol:
`new WebSocket(url, ['v12.stomp', 'access_token.<jwt>'])`. On the HTTP `upgrade` event the Gateway: accepts only path `/ws` (`404`), method GET with `Upgrade: websocket`
(`400`), and the web app's origin (`403`, stops cross-site hijacking); reads the token (`401` if none); verifies it with `authenticate` (`401`, or `503` when
the keys are down); opens a TCP connection to Booking & Seat (`502` on failure); replays the upgrade request byte for byte; then pipes the two sockets together.
Booking & Seat verifies the same token itself. The log line is `WebSocket opened` with the user id only.

### Logging and traces
`log.js` writes JSON lines (`level`, `service: gateway`, `msg`, fields) and, when `OPENOBSERVE_URL` and a user are set, queues them (max 2000, oldest dropped)
and posts batches of up to 200 every 2 s to the stream `gateway`; on failure the batch is put back. `accesslog.js` adds `method`, `path`, `status`, `ms`,
`userId` (the token's `sub`), `bookingId`, `tripId` and `trace_id`; **never** the query string, headers or body. `telemetry.js` starts the OpenTelemetry SDK
(HTTP and fetch instrumentation) so one trace follows a request into the Spring services, and removes query strings from span attributes.

## Configuration (environment variables; defaults suit local development)
| Variable | Meaning |
|---|---|
| `PORT`, `HOST` | listen address (8080, 127.0.0.1; 0.0.0.0 in Docker) |
| `KEYCLOAK_ISSUER` | the expected `iss` of tokens (the browser-facing address) |
| `KEYCLOAK_JWKS_URI` | where the keys are fetched (in Docker the Keycloak service name) |
| `CORS_ORIGIN` | the only allowed browser origin, also the only WebSocket origin |
| `TRAIN_SCHEDULE_URL`, `BOOKING_SEAT_URL`, `PAYMENT_URL`, `TICKET_URL` | service addresses |
| `INTERNAL_API_KEY` | secret shared with the services; used only towards Ticket (set in `.env`, never in code) |
| `OPENOBSERVE_URL`, `OPENOBSERVE_USER`, `OPENOBSERVE_PASSWORD` | log shipping (off when empty) |
| `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_SERVICE_NAME` | trace export (off when empty) |
| `UPSTREAM_TIMEOUT_MS`, `AVAILABILITY_TIMEOUT_MS` | 10000 and 3000 |

## Tests
`npm test` runs the Node built-in runner on `test/*.test.js`. Services are replaced by small throwaway HTTP servers, so real `fetch` calls, headers and timeouts are exercised.
Token verification needs Keycloak, so route tests check the refusals that happen before keys are needed (no token, garbage token) and use a fake `authenticate` where
a role is needed. Test count: about 60 `test(...)` cases across 10 files.

## Known limits
- `getJson` is copied in `seatmap.js`, `board.js` and `ticket.js`, and the "relay a 4xx, else 502" block is repeated; a shared helper would remove it.
- A WebSocket token is checked only on the upgrade. A connection stays open after the token expires; Booking & Seat is the second line of defence.
- No rate limiting and no request size limit other than 1 MB on bodies read with `express.raw`.
- `/webhooks/payment` accepts any body size up to 1 MB and does not check the signature (Payment does).
- The access log uses `req.path` of the app level for `/health` only; other paths are logged in full (without the query string), including ids.
- Logs wait in memory; if the Gateway stops before a flush, up to 2 s of records are lost.
- `/admin/ping` and `/me` are development helpers kept in production.
