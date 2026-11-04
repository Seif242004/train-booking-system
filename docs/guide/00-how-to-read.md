# 0. How to read this guide, and the project on one page

## The goal
A train booking system built as microservices for the e& internship program. A passenger searches trains, picks seats (the seats are held for
5 minutes), pays by card, and gets a PDF ticket with a QR code by email. An admin manages the timetable, supports passengers, books at the
counter, and watches a live board of all trips.

## The stack
| Part | Technology | Why |
|---|---|---|
| Backend services | Java 21, Spring Boot, Gradle (one subproject per service), Spring MVC | required by the supervisor; controller, then service, then repository |
| Database | Oracle (one schema, i.e. one database user, per service) | required; a service never reads another service's tables |
| Login | Keycloak (realms, roles `USER` and `ADMIN`, JWT tokens) | no custom login code |
| Gateway | Node.js and Express, in `gateway/` | single entry point, checks the token and the role |
| Frontend | React, TypeScript, Vite, plain CSS, in `frontend/` | no UI library |
| Payments | Stripe, test mode only | card data never touches our servers |
| Logs and traces | OpenObserve (OpenTelemetry) | structured logs with user, booking and trip ids |
| Email (dev) | Mailpit | catches all emails |

## Ports (on this computer)
Web app 5173, Gateway 8080, Keycloak 8180, Train & Schedule 8081, Booking & Seat 8082, Payment 8083, Ticket 8084, Notification 8085, Oracle 1521,
OpenObserve 5080, Mailpit 8025 (web) and 1025 (SMTP).

## Folder map
- `services/train-schedule`, `booking-seat`, `payment`, `ticket`, `notification`: the Spring Boot services. Inside each, code is grouped by feature first
  (for example `station`), then by layer (`controller`, `service`, `repository`, `model`). Cross-cutting code is in `audit/`, `security/`, `common/`.
- `gateway/src`: the Express Gateway. `gateway/test`: its tests.
- `frontend/src`: pages (`pages/`), components (`components/`), styles (`styles/`), the live-update client (`live/`).
- `infra/`: Compose helper files (Oracle init, Keycloak image and theme, Docker files).
- `scripts/`: `smoke-test.mjs` (end-to-end check) and `demo-data.mjs` (demo data and cleaning).
- `docs/`: `ARCHITECTURE.md` and `SRS.md` (the authoritative design), `design/` (UI design, flows, the counter-booking rule), `guide/` (this guide).

## How to run it
1. Copy `.env.example` to `.env` and fill the values (never commit `.env`).
2. `docker compose --profile dev up -d --build` starts everything (Oracle, Keycloak, OpenObserve, the five services, Gateway, web app, Mailpit, Stripe CLI).
3. Open the web app on port 5173. The dev accounts and passwords are in your `.env` (names only: `KEYCLOAK_DEV_USER_PASSWORD`, `KEYCLOAK_DEV_ADMIN_PASSWORD`).
4. `node scripts/smoke-test.mjs` runs a whole journey against the stack. `node scripts/demo-data.mjs seed-demo` fills the demo data.
5. Tests: `./gradlew test` (Spring services; they use throwaway Oracle schemas ending in `_test`) and `cd gateway && npm test`.

## A sensible reading order
1. `docs/ARCHITECTURE.md` (the flows) and chapter 1 of this guide.
2. The Gateway (`gateway/src/app.js`): it shows every public route in one file.
3. Train & Schedule: the simplest service (plain admin CRUD) and a good place to learn the Spring layers.
4. Booking & Seat: the heart (availability, holds, the sweeper, confirmation, cancellation). Read `availability/service/SegmentAvailability.java` first, then
   `booking/service/HoldService.java`, `ExpiryService.java`, `ConfirmationService.java`, `CancellationService.java`, `BookingIssueService.java`.
5. Payment, then Ticket and Notification (small services reached from Booking through the outbox).
6. The frontend: `frontend/src/App.tsx`, then `pages/SeatMapPage.tsx`, `pages/PaymentPage.tsx`, `pages/TripsBoard.tsx`.

## The rules to keep in mind everywhere
- A journey from stop `i` to stop `j` uses segments `i` to `j-1`. A seat is available only if it is free on every segment.
- The database prevents double booking: `seat_reservation` has the primary key `(trip_id, seat_id, segment_index)`; Oracle error `ORA-00001` means "seat taken".
- The hold lasts 5 minutes from the first pick; later picks join and never extend it. A sweeper runs every second and releases expired holds.
- Payment wins: if Stripe says the money was taken, the booking is confirmed even if the hold has just run out. A timeout never charges and never refunds.
- Calls that must not be lost go through an outbox table with a retry job (ticket, email, refund requests, payment results).
- Admins can also confirm a booking without Stripe (paid at the counter, or complimentary); see `docs/design/COUNTER-BOOKING.md`.
