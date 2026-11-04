# train-booking-system

A microservices train booking system (e& SSP internship). Passengers search trips, pick seats on a live seat map, hold them for five minutes, pay with Stripe, and get a PDF ticket by email; admins manage the timetable and cancel bookings or whole trips. The architecture and the eight flows are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); the diagram is [docs/architecture.png](docs/architecture.png).

| Part | Technology | Port | Folder |
|---|---|---|---|
| Web app | React, Vite (served by nginx in Compose) | 5173 | [frontend/](frontend/) |
| API Gateway | Express (Node.js) | 8080 | [gateway/](gateway/) |
| Authentication | Keycloak (on Oracle) | 8180 | [infra/keycloak/](infra/keycloak/) |
| Train & Schedule | Spring Boot, Java 21 | 8081 | [services/train-schedule/](services/train-schedule/) |
| Booking & Seat | Spring Boot, Java 21 | 8082 | [services/booking-seat/](services/booking-seat/) |
| Payment | Spring Boot, Stripe (test mode) | 8083 | [services/payment/](services/payment/) |
| Ticket | Spring Boot, PDF and QR code | 8084 | [services/ticket/](services/ticket/) |
| Notification | Spring Boot, SMTP | 8085 | [services/notification/](services/notification/) |
| Database | Oracle, one schema per service | 1521 | [infra/oracle/](infra/oracle/) |
| Logs, traces, audit | OpenObserve | 5080 | [infra/docker/](infra/docker/) |
| Dev only (profile `dev`) | Mailpit (catches emails, UI on 8025), Stripe CLI (forwards webhooks) | 8025, 1025 | |

Only the web app (5173), the Gateway (8080), Keycloak (8180), OpenObserve (5080) and, in the dev profile, Mailpit (8025) are meant to be opened; all ports are bound to `127.0.0.1`. The services other than the Gateway are not reachable from outside the Compose network.

## Run the whole stack

Prerequisites: Docker. Everything else (Java, Node, Gradle) is inside the images.

1. Copy `.env.example` to `.env` and fill in every value (database passwords, Keycloak admin and dev-user passwords, `INTERNAL_API_KEY`, `TICKET_SIGNING_KEY`, the OpenObserve login, the Stripe **test** keys). `.env` is git-ignored. Generate the random values with any password generator.
2. `docker compose --profile dev up -d --build`. The first start creates the database schemas and imports the Keycloak realm; the images take a few minutes to build.
3. For the Stripe webhook secret: `docker compose logs stripe-cli` prints a `whsec_...`; put it in `.env` as `STRIPE_WEBHOOK_SECRET` and run `docker compose up -d payment`.
4. Open http://localhost:5173 and sign in as `dev.user@example.com` (passenger) or `dev.admin@example.com` (admin), with the passwords from `.env`. Emails land in Mailpit at http://localhost:8025, logs and traces in OpenObserve at http://localhost:5080 (the login from `.env`).

The web app has help and policy pages (Help and FAQ, Cancellations and refunds, Terms of use, Privacy notice, Contact). The Terms and the Privacy notice are drafts for supervisor review. The Contact page shows `VITE_SUPPORT_EMAIL` (a build argument of the web app image; the default is a marked placeholder, `support@example.invalid`): set the real address before real use. The ticket PDF can be downloaded in the browser through the Gateway (`GET /bookings/{id}/ticket`), which therefore also needs `INTERNAL_API_KEY` (Compose passes it).

Without `--profile dev` the stack runs without Mailpit and the Stripe CLI (point `MAIL_HOST` and the Stripe webhook at real ones).

`docker compose down` stops it; `docker compose down -v` also deletes the databases and the OpenObserve data.

## Develop one service on the host

Start only the infrastructure (`docker compose --profile dev up -d oracle keycloak mailpit stripe-cli openobserve`), stop the container of the service you work on, and run it with Gradle (`./gradlew :services:booking-seat:bootRun`) or `npm start` / `npm run dev` for the Gateway and the web app. Each service README lists its environment variables and its local `.env`. The Stripe CLI of the dev profile forwards to the Gateway container; when the Gateway runs on the host, forward with your own `stripe listen --forward-to localhost:8080/webhooks/payment`.

## Tests

- `./gradlew test` runs the Spring services' tests. The database tests need the `*_test` schemas (optional, see `.env.example`) and are reported as SKIPPED without them.
- `cd gateway && npm test` and `cd frontend && npm run build` (type-check and build).
- `node scripts/smoke-test.mjs` runs the whole journey against the running stack, through the Gateway, with real Keycloak logins, the Stripe sandbox's test cards, Mailpit and OpenObserve: it builds a trip, holds and pays seats (success card, declined card, 3D Secure), cancels as passenger and as admin, cancels a whole trip, and checks the audit trail and traces in OpenObserve.

## Demo data and cleaning up test data

`node scripts/demo-data.mjs <command>` (dev stack running, `docker compose --profile dev up -d`):

| Command | What it does |
|---|---|
| `seed-demo` | Makes the demo network through the Gateway's admin endpoints: 24 stations, 6 lines (Cairo to Alexandria, Aswan, Mansoura, Port Said, Suez and a Cairo to Banha shuttle), 13 trains with First and Second class coaches (2+2 and 2+1) and standing places, fares per segment in EGP, and trips for today and the next 14 days (16 a day). It also adds a few trips that have left or arrived today, a full trip, a nearly full trip, trips with a mix of confirmed seats, one trip cancelled with the real whole-trip cancellation, and the small train `M001` (8 seats, Cairo to Banha, empty) to fill by hand. Running it again creates nothing twice. |
| `reset-demo` | Removes exactly what `seed-demo` made: the demo bookings first, then the demo trips, trains, routes and stations through the API. Anything a real booking still uses stays. |
| `reset-test-data` | Removes what the smoke test and the check and stress scripts left behind: stations `S<tag>A..C`, trains `SM<tag>`, routes `Smoke <tag>`, every cancelled trip that is not a demo trip, and the abandoned, expired and cancelled bookings of the two dev accounts, with their payments, refunds, tickets, e-mails and outbox rows. It never touches Keycloak, the accounts, other users' bookings or the audit logs. |
| `verify` | Checks by API: no duplicate stations or trips, the line stops in order, search results for each main route today and in two days, and a trip in every state on the live board. |

`--dry-run` (on `reset-test-data` and `reset-demo`) only writes the audit file. Before anything is deleted the counts and ids go to `.local/` (git-ignored). The smoke test runs `reset-test-data` at its end (`--keep` skips that).

Demo bookings (reference `DEMO-<trip>-<n>`, passenger `demo-passenger`) are written straight into the Booking & Seat tables, because the API cannot book a train that has already left or fill a trip in one go. That path is dev-only: it refuses to run unless the containers `tbs-oracle` and `tbs-mailpit` are up and the Stripe keys in `.env` are test keys. It uses the service's own keys and checks (one reservation per seat and segment, standing places within capacity), never disables a constraint, and the demo bookings have no payment or ticket.

## Documentation

[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) is authoritative. Every service README states its responsibility, endpoints, environment variables and how to run it.
