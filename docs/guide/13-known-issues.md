# 13. Known issues and improvements

This is a list only. Nothing here was fixed while writing the guide (no behaviour was changed). Items are grouped by where they live. "Observed" items are
things noticed while reading the code for chapters 1, 2 and 12; they were not tested.

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
