# 13. Known issues and improvements

This is a list only. Nothing here was fixed while writing the guide (no behaviour was changed). Items are grouped by where they live. "Observed" items are
things noticed while reading the code for chapters 1, 2 and 12; they were not tested.

## Fixed after the guide was written

- Stale comment above `stripe-cli` in `docker-compose.yml` (the Gateway is a container now).
- `Stations.tsx` now imports the shared `Station` type from `adminTypes.ts`.
- Unused `nowMin` removed from `scripts/demo-data.mjs`.
- Redundant `!issueCalls.isEmpty()` removed from the test helper `FakeTicketPort.issue`.

The same items still appear in the chapter lists below as written; they are resolved.

## Train & Schedule (from chapter 3)

- Duplicate and "in use" errors are recognised by **searching the database error text for a constraint name** (`UK_COACH_POSITION`, `FK_ROUTE_STOP_STATION` ...). Renaming a constraint in a migration would silently turn these 409 answers into 500.
- `TripRepository.findStops` uses an `IN (:ids)` list; Oracle allows at most 1000 values (`ORA-01795`). A search or admin list returning more than 1000 trips would fail.
- `GET /admin/trips` and the admin lists have no paging.
- `TripLayoutService` runs one seat query per coach.
- The push to Booking happens inside the database transaction, so an admin request holds a connection while Booking answers (up to the 5 s read timeout). The rare gap (Booking accepts, then the local commit fails) is documented in `TripSnapshotPublisher`.
- A trip's `status` can be `CANCELLED` and `TripService.update` does not check it; Booking refuses the push (`trip_not_open`), which protects the data.
- `JsonErrorHandlers.handle` always says "The ADMIN role is required", also for the internal chain.
- `audit_log.admin_id` is 64 characters; the `X-Acting-User` header is stored as given, so a longer value would make the cancel fail with a 500.

## Booking & Seat (from chapter 4)

- `AdminBookingQueryService.toAdminView` and `BookingQueryService.toView` read the trip info and items with separate queries per booking (and two queries per stop): up to 100 bookings in a search and 50 in "My bookings" cost hundreds of small queries.
- The support search uses `UPPER(reference) = :ref`, which cannot use the unique index on `reference`.
- `BookingRepository.findPassengersByEmail` knows only users who already have a booking, and excludes the literal user id `demo-passenger`.
- The STOMP broker is in memory: with two instances of this service, a client would only get the events of the instance it is connected to.
- `OutboxDispatcher` runs jobs one after another in one thread (20 per tick). A slow Ticket or Notification (10 s read timeout) delays every other job.
- A late-paid hold on a `CANCELLING` trip is confirmed and then refunded (by the next pass) instead of being refused when the user clicks "Continue to payment".
- `HoldService.pick` maps a lock timeout (`PessimisticLockingFailureException`) to "seats unavailable", which can be a slightly misleading message for a busy database.
- `ExpiryService.retryLater` only logs an error from the 5th attempt; a hold whose release keeps failing stays held until someone looks at the log.
- `JsonErrorHandlers.handle` always says "The ADMIN role is required.", also if the 403 comes from another rule.
- `V1__create_snapshot_and_reservation_tables.sql` still contains a `TODO(slice 7)` comment that is done (migrations are never edited, because Flyway checks their checksum).
- Test smell: `FakeTicketPort.issue` has a redundant `!issueCalls.isEmpty()` test (it is always true after the add).

## Observed while writing chapters 1, 2 and 12

- **Copied code across services.** `ApiError`, `GlobalExceptionHandler`, `RequestLogContext`, `RequestValidationException`, `AuditTrail`, `AuditLogRepository`, `InternalApiKeyFilter` and `JsonErrorHandlers` exist as near-identical copies in each Spring service (there is no shared library on purpose). A fix in one (for example the fixed 403 text above) must be repeated in all.
- **Polling everywhere.** The sweeper, the outboxes, the email dispatcher, the trip-cancellation worker and the board all poll on a timer. This is simple and safe, but adds constant database load and up to one interval of delay; the board in the browser polls every 5 s per open tab.
- **Failure of a one-way chain is only logged.** If Booking permanently refuses a Payment message, or an outbox row keeps failing, the main sign is a log line (the booking itself is never changed by a ticket or email failure, by design). There is no admin screen for stuck outbox rows or `FAILED` emails.
- **Stripe is test mode only** by design (`StripeKeyGuard` refuses live keys); the minimum charge of about US$0.50 makes cheap fares answer 409 `amount_too_small`.
- **Gateway composes and calls without a circuit breaker.** The seat map and the board detail call two services in parallel and fail as a whole if one does not answer; search degrades gracefully (`availability: null`), the seat map does not.
- **Single-instance assumptions.** The in-memory STOMP broker, the Gateway's cached keys and the schedulers (no leader election, jobs rely on row locks and conditional updates) assume one instance of each service.
- **Counter bookings and refunds.** A cancelled `COUNTER` booking only reaches `MANUAL_REFUND_DUE`; the money is handed back by a person and an admin records it (`REFUNDED_AT_COUNTER`). There is no reminder for refunds that stay due.
- **A hold that passed its time but was paid at the counter is refused** (no "payment wins" for admins), which is by design but can surprise an admin who is slow.
- **Timezone.** Booking & Seat and Train & Schedule show stop times in `trainbooking.timezone` (default Africa/Cairo); a deployment in another zone must set it in every service and the web app shows the clock part as given.
- **Docs.** Chapters 5 to 11 and the per-service READMEs were not available when this chapter was written; their known limits must be added below.

---

**Note for the merge:** when chapters 05 (Payment), 06 (Ticket), 07 (Notification), 08 (Gateway), 09 (Frontend), 10 (Infrastructure) and 11 (Scripts) are merged,
copy the "Known limits" list of each into this file as a new section named after the chapter, and remove duplicates of the items under "Observed" that they confirm.


## Known limits of chapters 5 to 11 (copied from each chapter)

### Chapter 10: Infrastructure (10-infrastructure.md)
- `start-dev` and `KC_HOSTNAME=http://localhost:8180` are for local use only; production would need HTTPS, a real hostname and a different realm file.
- The comment above `stripe-cli` in `docker-compose.yml` still says the Gateway runs on the host; in the current stack it is a container and the
  CLI forwards to `http://gateway:8080/webhooks/payment`.
- The Spring image copies `...-0.0.1-SNAPSHOT.jar`, so changing the version in `build.gradle` breaks the Docker build until the Dockerfile is updated.
- `stripe/stripe-cli:latest` and `axllent/mailpit:latest` are not pinned; the other images are.
- The OpenTelemetry agent is downloaded at build time without a checksum (the Keycloak drivers have one).
- The `train-schedule` timezone variable is `TRAINBOOKING_TIMEZONE`, the other services use `TIMEZONE`.
- The `_test` schema creation relies on a fresh volume; for an existing volume the schema must be created by hand.
- `entrypoint-java.sh` is the only LF file of this group (forced by `.gitattributes`, because a CRLF shell script fails inside Linux).
- The root `build.gradle` comments and `nginx.conf` comments are not covered by the comments-only checker (it does not know those file types);
  they were verified by stripping comment lines and comparing.

### Chapter 11: Scripts and demo data (11-scripts.md)
- `login` is copied in both files (no shared module). The HTML scraping depends on Keycloak's form id `kc-form-login`.
- SQL in `demo-data.mjs` is built from constants and database ids (no user input), not bound parameters.
- The clean-up called by the smoke test needs Docker and the dev stack; if it fails the smoke test only prints a hint.
- Observability checks depend on log ingestion delay (up to 45 s of waiting).
- `fillTrip` writes booking rows without Payment rows, so these demo bookings have no payment record.
- `seedDemo` has a leftover unused `nowMin` (`void nowMin`).

### Chapter 5: Payment (05-payment.md)
- `RequestValidationException` is not thrown anywhere in Payment and `GlobalExceptionHandler` has no handler for it (copied from the other services).
- `AuditLogRepository` Javadoc still says "admin" and `CREATE/UPDATE/DELETE`; Payment writes `CREATE`, `INTENT`, `CANCEL`, `INVALIDATE`, `RESTART`, `TOMBSTONE`, `SUCCEEDED`, `REQUEST`, `FAILED`.
- `RequestLogContext` removes the `userId` key but Payment never sets it (no user token reaches this service).
- `RefundService.attempt` reads the payment with an unlocked `findByBooking`; safe because a succeeded payment never changes, but it relies on that.
- A refund stuck in `PENDING` because Stripe stays in `requires_action` is only finished by a webhook; there is no polling of Stripe.
- `OutboxRepository.markFailed` leaves a refused `REFUND_STATUS` message without any alert beyond an error log line.

### Chapter 6: Ticket (06-ticket.md)
- `QrTokens.verify` has no caller yet: there is no gate-scanner endpoint (not in the SRS list of endpoints).
- The QR token is a bearer secret; the answer of `/qr` and the PDF contain it, so only trusted services may call these routes (they are internal-only).
- The PDF is drawn before the `INSERT`, inside the transaction; a race loser draws a PDF that is thrown away.
- `RequestValidationException` and `AuditTrail.change` are shared code that this service does not use.
- The audit actor is always `booking-service` (the real user is not known here), and the column is named `admin_id`.
- Only English text; characters outside the Windows-1252 set (Arabic, emoji) are replaced by `?` on the PDF.
- The logo needs a PNG file (OpenPDF cannot draw SVG); without it the PDF has a plain header.

### Chapter 7: Notification (07-notification.md)
- At-least-once delivery: a crash between the SMTP send and `markSent` can send an email twice.
- An invalid base64 attachment is not validated as base64 by Bean Validation; it throws `IllegalArgumentException` and is answered as 500 `internal_error`.
- `RequestLogContext` removes a `userId` field and matches `/bookings|payments|tickets|refunds/<id>` paths that this service does not have; it is
  the same filter as in the other services. `RequestValidationException` and `AuditTrail.change` are not used here.
- The audit actor is the fixed text `booking-service`, whoever really called with the key.
- The backoff exponent is capped at 2^9 = 512 before the 300 s cap, so the wait is 2, 4, 8, 16, 32, 64, 128, 256, then 300 seconds.
- The email body stored in the database contains the passenger name, address and journey (needed to resend); there is no clean-up of old `SENT` rows.

### Chapter 8: Gateway (08-gateway.md)
- `getJson` is copied in `seatmap.js`, `board.js` and `ticket.js`, and the "relay a 4xx, else 502" block is repeated; a shared helper would remove it.
- A WebSocket token is checked only on the upgrade. A connection stays open after the token expires; Booking & Seat is the second line of defence.
- No rate limiting and no request size limit other than 1 MB on bodies read with `express.raw`.
- `/webhooks/payment` accepts any body size up to 1 MB and does not check the signature (Payment does).
- The access log uses `req.path` of the app level for `/health` only; other paths are logged in full (without the query string), including ids.
- Logs wait in memory; if the Gateway stops before a flush, up to 2 s of records are lost.
- `/admin/ping` and `/me` are development helpers kept in production.

### Chapter 9: Frontend (09-frontend.md)
- No automated frontend tests; only the smoke test exercises the screens' backend paths.
- The seat-plan helper `planOf` is copied in three files (`SeatMapPage.tsx`, `AdminSeatPicker.tsx`, `BoardDetailPanel.tsx`), with slightly different types.
- `Stations.tsx` declares its own `Station` interface instead of using the one in `adminTypes.ts`.
- `TripsBoard.tsx` fixes the railway's time zone to `Africa/Cairo` in code, while the backend setting is `trainbooking.timezone`.
- `SearchTrips.tsx` disables an ESLint rule (`exhaustive-deps`) on the hand-over effect on purpose; it must run once when the stations arrive.
- In `MyBookingsPage.tsx` the `message` object has an `ok` flag that is always `false` (successes use a toast).
- `EXPIRED_MESSAGE` is exported from `PaymentPage.tsx` and imported by the seat map page, a small cross-page dependency.
- The hold banner polls `GET /bookings` every 30 s and on every page change; the payment page polls every 2 s. Both are safety nets next to the WebSocket and cost one request each.
- A signed-out visitor on the landing page types station names; the names are matched to codes only after sign-in on the search page.
- The comments in JSX markup were not added inline (a `{/* */}` adds an empty expression that the comments-only checker cannot tell from code); each component is described by the comment above it instead.
