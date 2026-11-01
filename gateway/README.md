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
| any | `/bookings/**` | any valid token | forwarded to Booking & Seat (`BOOKING_SEAT_URL`): hold (`POST /bookings`), a booking (`GET /bookings/{id}`) and, in later steps, payment, cancellation and "My bookings". The token is forwarded; Booking & Seat answers 404 for another user's booking |
| GET | `/trips/{id}/seat-map?from=&to=` | any valid token | **composed** (`src/seatmap.js`): the layout of Train & Schedule (`/trips/{id}/layout`: coaches, seats, standing capacity, stops) with the availability of Booking & Seat (`/trips/{id}/availability`: the state of every seat, the standing places left, the journey fares). `from` and `to` are stop indexes. Both lookups run at the same time; an unknown trip (404) or a journey that does not fit (400) is relayed as it is, a failing service is a 502 and a slow one a 504. A seat that Booking does not know is shown as booked, never as free. `/trips/{id}/layout` and `/trips/{id}/availability` have no public route |

## Trip search

`GET /trips` is answered by `src/search.js`, so the browser makes one call (Flow 2). The Gateway sends the search to Train &
Schedule; if that answers 200, it asks Booking & Seat for the totals of each trip (`GET /trips/{id}/availability?from=&to=&view=summary`,
with the trip's own stop indexes and the caller's token) and adds them to the trip as `availability: { seatsLeft, standingLeft }`
(counts per class). The lookups run at the same time and give up after `AVAILABILITY_TIMEOUT_MS`. Booking & Seat is optional
for the search: if it is down, slow, or does not know a trip, that trip gets `availability: null` and the search still
returns the timetable. Any answer of Train & Schedule that is not a 200 (a 400 for a bad station code, for example) is relayed
unchanged. There is no public route for `/trips/{id}/availability` and `/internal/**` is never forwarded.

Tests: `npm test` (Node's built-in test runner, with small fake services).

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
| `UPSTREAM_TIMEOUT_MS` | `10000` | How long to wait for a service before answering 504 |
| `AVAILABILITY_TIMEOUT_MS` | `3000` | How long to wait for Booking & Seat per trip before showing the trip without its availability |

## Run

Keycloak must be running first (`docker compose up -d` from the repository root). Needs Node.js 22.12 or newer.

```
cd gateway
npm install
npm start
```

Try it: `curl http://localhost:8080/health`. For the Gateway in Docker Compose, see step 12.
