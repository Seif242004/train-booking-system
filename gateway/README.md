# API Gateway

Single entry point of the system (Express, Node.js). It verifies the Keycloak access token on every request and
enforces roles. It does not handle logins or passwords: the browser talks to Keycloak directly (authorization code
flow with PKCE). The Gateway only fetches Keycloak's public keys (JWKS) to check tokens, and it has no Keycloak proxy.

Routes to the other services are added as each service is built. The WebSocket proxy, the seat-map composition and
the Stripe webhook forwarding come with their steps.

## Endpoints

| Method | Path | Auth | Response |
|---|---|---|---|
| GET | `/health` | none | `{ "status": "UP" }` |
| GET | `/me` | any valid token | `{ sub, name, email, roles }` |
| GET | `/admin/ping` | role `ADMIN` | `{ "message": "pong", "admin": "<sub>" }` (example admin-only route) |
| any | `/admin/stations/**`, `/admin/trains/**` (including `/admin/trains/{id}/coaches`), `/admin/coaches/**` (including `/admin/coaches/{id}/seats`), `/admin/seats/**`, `/admin/routes/**` (including `/admin/routes/{id}/fares`), `/admin/trips/**` | role `ADMIN` | forwarded to Train & Schedule (`TRAIN_SCHEDULE_URL`), answer relayed unchanged |
| GET | `/trips?from=&to=&date=` | any valid token | **composed** (see "Trip search"): the trips of Train & Schedule, each with the seats and standing places left for the journey from Booking & Seat |
| GET | `/stations` | any valid token | forwarded to Train & Schedule: the station list for the search page (exact path only) |
| WebSocket | `/ws` | any valid token, offered as a subprotocol | **live seat updates** (`src/wsproxy.js`): the token is checked on the HTTP upgrade, then the connection is piped to Booking & Seat (`/ws`, STOMP). See "The WebSocket" below |
| POST, GET | `/admin/trips/{id}/cancel`, `/admin/trips/{id}/cancellation` | role `ADMIN` | forwarded to **Booking & Seat** (Flow 8: cancel a whole trip with a reason, and see how far it is); these two exact paths are matched before the rest of `/admin/trips/**`, which goes to Train & Schedule |
| any | `/admin/bookings/**` | role `ADMIN` | forwarded to Booking & Seat: support cancels a booking on the passenger's request (`POST /admin/bookings/{id}/cancel`, with a reason), the search (`GET /admin/bookings?ref=&email=&tripId=&status=&refundStatus=`) and the details (`GET /admin/bookings/{id}`) |
| any | `/bookings/**` | any valid token | forwarded to Booking & Seat (`BOOKING_SEAT_URL`): pick and release (`POST /bookings/pick`, `POST /bookings/{id}/release`, `DELETE /bookings/{id}/hold`), a booking (`GET /bookings/{id}`), `POST /bookings/{id}/payment` (Continue to payment: the client secret for the embedded Stripe form) , `POST /bookings/{id}/resend-ticket`, cancellation (`POST /bookings/{id}/cancel`) and "My bookings" (`GET /bookings`). The token is forwarded; Booking & Seat answers 404 for another user's booking |
| POST | `/webhooks/payment` | none (the Stripe signature authenticates it) | **Stripe's webhook**, forwarded to Payment (`PAYMENT_URL`) with the body untouched (`express.raw`) and only `Content-Type` and `Stripe-Signature` passed on. Payment verifies the signature and handles each event once; the Gateway does not look inside |
| GET | `/bookings/{id}/ticket` | any valid token | **the ticket PDF** (`src/ticket.js`). The Ticket service stays internal-only; the Gateway decides. It asks Booking & Seat for the booking with the caller's token (`/bookings/{id}`, `404` for anyone else's booking; an admin falls back to `/admin/bookings/{id}`), answers `409 ticket_not_available` unless the booking is `CONFIRMED`, then fetches `/internal/tickets/{id}/pdf` from the Ticket service (`TICKET_URL`) with the shared `INTERNAL_API_KEY`, which the user never sees. `409 ticket_not_ready` while the PDF is still being made, `401` without a token. The response is `application/pdf`, `attachment`, `private, no-store` |
| GET | `/trips/{id}/seat-map?from=&to=` | any valid token | **composed** (`src/seatmap.js`): the layout of Train & Schedule (`/trips/{id}/layout`: coaches, seats, standing capacity, stops) with the availability of Booking & Seat (`/trips/{id}/availability`: the state of every seat, the standing places left, the journey fares). `from` and `to` are stop indexes. Both lookups run at the same time; an unknown trip (404) or a journey that does not fit (400) is relayed as it is, a failing service is a 502 and a slow one a 504. A seat that Booking does not know is shown as booked, never as free. `/trips/{id}/layout` and `/trips/{id}/availability` have no public route |
| GET | `/admin/board/trips?from=&to=&state=` | `ADMIN` | **the live trips board** (read-only), forwarded to Booking & Seat (`/admin/board/trips`): per trip the train, route, times, lifecycle state (`UPCOMING`, `DEPARTED`, `ARRIVED`, `CANCELLED`, plus the flag `full`) and the counts confirmed / held / free per class and in total (full-journey rule), and a summary. `from` and `to` are local dates (inclusive); default: the last 24 hours and the next 7 days; `state` is a comma list (`FULL` keeps the full upcoming trips). The summary always covers the whole range. `401` without a token, `403` for a non-admin |
| GET | `/admin/board/trips/{id}` | `ADMIN` | **composed** (`src/board.js`): the layout of Train & Schedule with the state of every seat for the full journey from Booking & Seat (`FREE`, `HELD`, `TAKEN`; a booking reference only on `TAKEN` seats, no passenger data), plus the standing counts per coach. A seat Booking does not know is shown as taken. `404` for an unknown trip |

## Trip search

`GET /trips` is answered by `src/search.js`, so the browser makes one call (Flow 2). The Gateway sends the search to Train &
Schedule; if that answers 200, it asks Booking & Seat for the totals of each trip (`GET /trips/{id}/availability?from=&to=&view=summary`,
with the trip's own stop indexes and the caller's token) and adds them to the trip as `availability: { seatsLeft, standingLeft }`
(counts per class). The lookups run at the same time and give up after `AVAILABILITY_TIMEOUT_MS`. Booking & Seat is optional
for the search: if it is down, slow, or does not know a trip, that trip gets `availability: null` and the search still
returns the timetable. Any answer of Train & Schedule that is not a 200 (a 400 for a bad station code, for example) is relayed
unchanged. There is no public route for `/trips/{id}/availability` and `/internal/**` is never forwarded.

Tests: `npm test` (Node's built-in test runner, with small fake services).

## The WebSocket

The browser opens **one** WebSocket to the Gateway, separate from the HTTP calls, and subscribes with STOMP to `/topic/trips/{id}` (the seat events of a trip) and `/user/queue/holds` (events for the user only, for example `HOLD_EXPIRED`). The Gateway verifies the Keycloak token **on the upgrade request**, with the same verification as the HTTP routes, and only then connects the socket to Booking & Seat. A browser cannot set an Authorization header on a WebSocket, and the token must never be in the URL (URLs end up in logs), so the browser offers it as a second **subprotocol**: `new WebSocket(url, ['v12.stomp', 'access_token.<jwt>'])`. The `Sec-WebSocket-Protocol` header is passed on unchanged, so Booking & Seat checks the token again itself; it answers with `v12.stomp`, never the token. Refusals: 401 (no or invalid token), 503 (the Keycloak keys cannot be fetched), 403 (an `Origin` other than `CORS_ORIGIN`, which stops cross-site WebSocket hijacking), 404 (any path other than `/ws`), 502 (Booking & Seat is down). Neither the token nor the query string is ever logged. No library is used: after the upgrade is accepted the two sockets are piped into each other.

## Forwarding to services

Forwarded routes check the token and the role first, so a non-admin gets `403` from the Gateway and never reaches the
service. The Gateway then sends the request on with the caller's `Authorization` header (the service validates the
token again as an OAuth2 resource server). Only `Authorization`, `Content-Type` and `Accept` are passed on; the
service's status, body and `Content-Type`/`Location` are relayed back. `src/proxy.js` has the commented code.

| Status | `error.code` | When |
|---|---|---|
| 502 | `upstream_unavailable` | the service cannot be reached |
| 504 | `upstream_timeout` | the service did not answer within `UPSTREAM_TIMEOUT_MS` |

Errors always use one JSON shape: `{ "error": { "code": "...", "message": "..." } }`.

| Status | `error.code` | When |
|---|---|---|
| 401 | `unauthenticated` | no `Authorization: Bearer <token>` header |
| 401 | `invalid_token` | bad signature, expired, wrong issuer, malformed |
| 403 | `forbidden` | valid token without the required role |
| 404 | `not_found` | unknown route |
| 503 | `auth_unavailable` | Keycloak's keys cannot be fetched (an outage is not a bad token) |
| 500 | `internal_error` | unexpected error |

## How a token is verified

See `src/auth.js` for the commented code.

1. The token must be an `RS256` JWT; its `kid` selects the key from Keycloak's JWKS
   (`<issuer>/protocol/openid-connect/certs`), which is fetched on first use and cached for 10 minutes. An unknown
   `kid` triggers one refresh, at most every 30 s.
2. The signature, `exp` and `iss` are checked (`iss` must equal `KEYCLOAK_ISSUER` exactly).
3. There is no `aud` check: Keycloak sets `aud: "account"`, which is not an audience for the Gateway.
4. Roles are read only from `realm_access.roles`.

CORS allows only `CORS_ORIGIN` (default `http://localhost:5173`).

## Environment variables

All are optional; the defaults are for local development. See `.env.example`.

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | Listen port |
| `HOST` | `127.0.0.1` | Listen address |
| `KEYCLOAK_ISSUER` | `http://localhost:8180/realms/train-booking` | Expected token issuer; the JWKS URL is derived from it |
| `CORS_ORIGIN` | `http://localhost:5173` | The one allowed browser origin |
| `TRAIN_SCHEDULE_URL` | `http://localhost:8081` | Where the stations, trains, coaches, seats, routes, fares and trips routes are forwarded |
| `BOOKING_SEAT_URL` | `http://localhost:8082` | Where Booking & Seat is asked for the availability in the search results |
| `KEYCLOAK_JWKS_URI` | the issuer + `/protocol/openid-connect/certs` | Where the signing keys are fetched. In Docker this is `http://keycloak:8080/...` while the issuer stays the address the browser used |
| `OPENOBSERVE_URL`, `OPENOBSERVE_USER`, `OPENOBSERVE_PASSWORD` | none | When set, the structured logs are also shipped to OpenObserve (`<url>/gateway/_json`) |
| `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_SERVICE_NAME` | none, `gateway` | When the endpoint is set, OpenTelemetry traces go to OpenObserve (with the `OPENOBSERVE_*` login) |
| `UPSTREAM_TIMEOUT_MS` | `10000` | How long to wait for a service before answering 504 |
| `AVAILABILITY_TIMEOUT_MS` | `3000` | How long to wait for Booking & Seat per trip before showing the trip without its availability |

## Logs and traces

Every log line is one JSON object on the console (`level`, `service`, `msg` and fields). Each request writes a `request` line with the method, the **path**, the status, the duration, the user id (the token's `sub`), the booking id and the trip id when the path has them, and the trace id. The query string, headers and bodies are never logged, and tokens cannot reach the log. With `OPENOBSERVE_URL` set, the records are shipped in batches to the `gateway` stream of OpenObserve (a failure is retried and never slows a request; the oldest records are dropped when OpenObserve stays down). With `OTEL_EXPORTER_OTLP_ENDPOINT` set, `src/telemetry.js` sends OpenTelemetry traces: the incoming request is a span, every call to a service is a child span carrying the W3C `traceparent` header, so one trace runs from the Gateway into the Spring service. Span attributes that hold a URL are cut at the `?` before export.

## Run

Keycloak must be running first (`docker compose up -d` from the repository root). Needs Node.js 22.12 or newer.

```
cd gateway
npm install
npm start
```

Try it: `curl http://localhost:8080/health`. For the Gateway in Docker Compose, see step 12.
