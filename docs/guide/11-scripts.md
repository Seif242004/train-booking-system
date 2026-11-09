# 11. Scripts

Two Node.js scripts in `scripts/` run against the whole stack from outside. They use only Node built-ins (Node 22, ES modules, no `npm install`).
They read the repository-root `.env` for passwords and the Stripe test key, and print no secret.

| File | One line |
|---|---|
| `scripts/smoke-test.mjs` | End-to-end test of the running stack: real logins, real Stripe test payments, cancellations, emails, logs. 45 checks. |
| `scripts/demo-data.mjs` | Four commands: `seed-demo`, `reset-demo`, `reset-test-data`, `verify`. |

The shell scripts `infra/docker/entrypoint-java.sh` and `infra/oracle/init/01-create-schemas.sh` belong to the infrastructure chapter (10).

## Shared building blocks

- **`login(username, password)`** (copied in both files): the browser's authorization code flow with PKCE, done by hand. It makes a random
  verifier and its SHA-256 challenge, loads Keycloak's auth page for the public client `web-app`, posts the form with the cookies it got, reads
  the `code` from the redirect and exchanges it (with the verifier) at the token endpoint. No direct-access grant is needed.
- **`call(method, path, ...)`**: one request through the **Gateway** (never directly to a Spring service). It returns `{ status, body }` and does
  not throw on 4xx/5xx, so checks can assert on 401, 403, 404, 409.
- **`.env` parser**: lines `KEY=VALUE` only, split at the first `=`.
- **Addresses** can be overridden by `GATEWAY_URL`, `KEYCLOAK_URL`, `MAILPIT_URL`, `OPENOBSERVE_URL`, `ORACLE_CONTAINER`, `TIMEZONE`.

## smoke-test.mjs

Run: `docker compose --profile dev up -d --build`, then `node scripts/smoke-test.mjs [--keep]`. Exit code 0 when every check passes, else 1.

### Guards
1. `STRIPE_SECRET_KEY` must start with `sk_test_`, else the script throws before doing anything (it pays with Stripe test cards).
2. `GET /health` on the Gateway must be 200, else it prints FAIL and exits 1 (is the stack running?).
3. Everything it creates carries a random 6-hex `tag` (stations `S<tag>A..C`, train `SM<tag>`, route `Smoke <tag>`), so it never touches demo data.

### Flow (each section starts with a `--- ...` line)
1. **Doors**: tokens for the passenger and the admin; no token and a bad token give 401; a passenger on `/admin/stations` gives 403; the admin 200.
2. **Admin builds a trip**: three stations, a train with one SECOND coach, 6 seats, 10 standing places, a route with fares 100 + 130 EGP, a trip 4 days ahead (created means pushed to Booking & Seat).
3. **Search and seat map**: the search result (composed by the Gateway) shows 6 seats and 10 standing places left; the seat map is composed.
4. **Journey 1, pay**: the first pick starts the hold (201, `PENDING_PAYMENT`); another user gets 409 on that seat and 404 on the booking; payment creates a
   PaymentIntent; the script confirms it with `pm_card_visa`; the signed webhook reaches Payment through the Gateway; the booking becomes `CONFIRMED` with
   230 EGP; the ticket email with the PDF reaches Mailpit; My bookings lists it.
5. **Journey 2, decline**: `pm_card_chargeDeclined` is declined, the booking stays held (FR-P5), a retry on the same PaymentIntent confirms it.
6. **Journey 3, 3D Secure**: the intent needs action, nothing is confirmed; deselecting the last seat ends the hold (`ABANDONED`) and the
   PaymentIntent is cancelled at Stripe first.
7. **Cancellation**: another user's cancel is 404; the owner's is instant, ends `REFUNDED` with one refund of 23000 piastres and an email; a passenger
   on the admin cancel route gets 403; the admin finds the booking by reference and cancels with a reason (actor role `ADMIN` recorded).
8. **Counter and complimentary bookings** (no Stripe): exact-email passenger lookup (partial email: 404); issue as `COUNTER` (a second submit returns
   the same booking); the board shows the seat `TAKEN`; the passenger cannot cancel it (`cancel_at_desk`); the PDF is one page with reference, "Paid at
   counter", logo and QR; the admin cancel gives `MANUAL_REFUND_DUE`, then `REFUNDED_AT_COUNTER` (idempotent); a complimentary booking needs a
   reason (400 without) and cancels to `NOT_APPLICABLE`.
9. **Whole-trip cancel**: a confirmed booking and an open hold exist; a passenger gets 403; the admin cancels; the trip ends `CANCELLED` with nothing
   pending, the booking is refunded, the hold is `EXPIRED`, the trip leaves the search.
10. **Observability**: OpenObserve search shows Gateway logs with booking and user id, the audit line of the cancellation, traces, and no `access_token` or `Bearer` text.
11. **Clean-up**: unless `--keep`, it runs `demo-data.mjs reset-test-data` as a child process.

Helpers: `check` (PASS/FAIL line and counters), `waitFor` (poll until truthy, because the stack is asynchronous), `stripe` (Stripe REST with the test key),
`mails` (Mailpit list), `inspectPdf` (inflate the PDF streams to count pages and images and read text), `ooSearch` (OpenObserve SQL search of the last 20 minutes).
Not covered: the 5-minute hold timeout (covered by the Booking tests).

## demo-data.mjs

| Command | What it does |
|---|---|
| `seed-demo` | Builds the demo network and timetable; safe to run again (idempotent). |
| `reset-demo [--dry-run]` | Removes exactly what `seed-demo` made. |
| `reset-test-data [--dry-run]` | Removes what smoke, check and stress runs left. Never users, never Keycloak. |
| `verify` | Read-only API checks of the demo data. Exit 1 on a failure. |

An unknown command prints the usage and exits 2. Any error prints its message (passwords masked) and exits 1.

### Two ways to write data
- **Through the Gateway as the admin** (stations, trains, coaches, seats, routes, fares, trips, trip cancel, demo bookings of trips that have not left).
  This keeps Train & Schedule and Booking & Seat in step and runs all their validation. The admin token is renewed every 200 s (tokens last 5 min).
- **Directly in Oracle** (dev-only), used only where the API cannot: confirmed demo bookings on trips that already left, and deleting test rows
  that the API refuses to delete because they carry bookings. `sql()` runs SQL*Plus inside the Oracle container as the service's own schema user
  (password passed on stdin, never on a command line), aborts and rolls back on the first error, and masks the password in error text.

### Guards
- `requireDevStack()` runs before every database access: the containers `tbs-oracle` and `tbs-mailpit` must be running (the dev profile) and the
  Stripe keys must be test keys. Otherwise the script refuses.
- Markers: direct rows use user id `demo-passenger` and references `DEMO-...`; API-made demo bookings are COMPLIMENTARY with note "DEMO data" and
  walk-in name "Demo Passenger". `reset-demo` deletes only rows matching these.
- No constraint is ever disabled. Writes keep the service's keys: one `seat_reservation` row per seat and segment (primary key trip, seat, segment)
  and a conditional rise of `standing_usage.used` (CHECK `used <= capacity`).
- `reset-test-data` and `reset-demo` write an audit JSON (counts and ids) to `.local/` before deleting. `--dry-run` writes it and deletes nothing.
- A test trip carrying a booking of another user is "protected": it is listed and the whole deletion stops with nothing deleted.

### seed-demo flow
1. `ensureStations` (24 stations; renames CAI and ALX to the common naming style), `ensureTrains` (16 trains; coaches and seats only if the train has none),
   `ensureRoutes` (6 lines; fares set as a whole if fewer than expected; `fareOf`: SECOND 0.42 EGP per km rounded to 5, minimum 30 because of Stripe's minimum charge; FIRST 1.7 times).
2. Trips: three showcase trips of today timed from the clock (one running, two arrived), then the daily departures for today and 14 days ahead;
   existing ones (`train|date|time`) are skipped; refusals for a time already past are counted.
3. One trip (Canal 633, in 2 days) is cancelled with the real whole-trip cancel.
4. Demo bookings per `fillFraction` (some trips pinned FULL, NEARLY, or a fixed fraction; M001 stays empty). A trip not yet left is filled through
   `POST /bookings/pick` and `POST /admin/bookings/{id}/issue` (`fillTripApi`); a left trip through SQL (`fillTrip`, anonymous PL/SQL blocks of 20 bookings).
   A seeded generator `rng(tripId)` makes a re-run choose the same seats.

### reset-test-data flow
`collectTestData` finds trains `SM<6hex>`, routes `Smoke <6hex>`, stations `S<6hex>[ABC]`, their trips, cancelled trips of non-demo trains, and
the bookings on them plus the ended bookings of the two dev accounts. `deleteBookings` deletes in dependency order: Payment (refund, payment, outbox),
Ticket, Notification (by reference), then Booking & Seat in one rollback unit (standing counters given back for live bookings, then reservations,
standing holds, items, outbox, bookings). Then the trip copies (children cascade), then trips, trains, routes, stations in Train & Schedule.
If a step fails, bookings remain and a second run finishes the job.

### reset-demo flow
Deletes demo bookings as above, then deletes demo trips, trains, routes and stations through the API. Anything that still has a real booking is
refused by the service and kept; the final message lists it.

## Known limits
- `login` is copied in both files (no shared module). The HTML scraping depends on Keycloak's form id `kc-form-login`.
- SQL in `demo-data.mjs` is built from constants and database ids (no user input), not bound parameters.
- The clean-up called by the smoke test needs Docker and the dev stack; if it fails the smoke test only prints a hint.
- Observability checks depend on log ingestion delay (up to 45 s of waiting).
- `fillTrip` writes booking rows without Payment rows, so these demo bookings have no payment record.
- `seedDemo` has a leftover unused `nowMin` (`void nowMin`).
