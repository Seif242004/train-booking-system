# 2. End-to-end walkthroughs

Nine journeys through the code. Each lists the steps in order, with the file and method that does each step. Paths are relative to the repository root.
Abbreviations: `B` = `services/booking-seat/src/main/java/com/trainbooking/bookingseat`, `P` = `services/payment/src/main/java/com/trainbooking/payment`,
`T` = `services/ticket/src/main/java/com/trainbooking/ticket`, `N` = `services/notification/src/main/java/com/trainbooking/notification`,
`TS` = `services/train-schedule/src/main/java/com/trainbooking/trainschedule`, `FE` = `frontend/src`, `GW` = `gateway/src`.

## 1. Search

1. `FE/pages/SearchTrips.tsx` (with `FE/components/SearchForm.tsx`) loads stations with `GET /stations` and sends `GET /trips?from=<code>&to=<code>&date=`.
2. `GW/app.js` routes `GET /trips` through `requireAuth` (`GW/auth.js`) to `tripSearch()` in `GW/search.js`.
3. `tripSearch` forwards the call to Train & Schedule: `TS/trip/controller/TripSearchController.search` then `TS/trip/service/TripSearchService`, which reads
   `TS/trip/repository/TripRepository` (active trips, origin before destination, departure date at the origin, journey fare per class = sum of the segment fares, all stops with times in one query).
4. Back in the Gateway, `fetchAvailabilitySummary` (same file) asks Booking & Seat for each trip in parallel: `GET /trips/{id}/availability?from=&to=&view=summary`
   (`B/availability/controller/AvailabilityController.availability`, `AvailabilityService.availability`, the pure rule `SegmentAvailability.compute`). It adds
   `availability: { seatsLeft, standingLeft }` per class, or `null` if Booking does not answer within `AVAILABILITY_TIMEOUT_MS` (the timetable still shows).
5. The page shows the trips; "Select seats" opens `/trips/:id/seats`.

The seat map (`FE/pages/SeatMapPage.tsx`) calls `GET /trips/{id}/seat-map?from=&to=`. `GW/seatmap.js` (`seatMap`, `composeSeatMap`) fetches the layout from Train & Schedule
(`TripLayoutController.layout` / `TripLayoutService`) and the availability from Booking in parallel, and merges them. A seat unknown to Booking is shown as booked.

## 2. Seat hold and live updates

1. The user clicks a seat in `FE/pages/SeatMapPage.tsx` (drawn by `FE/components/CoachSeats.tsx`, `SeatShape.tsx`). The page sends `POST /bookings/pick`
   `{tripId, fromStop, toStop, seatId}` or `{..., coachId, qty}` for standing places.
2. `GW/app.js`: `app.use('/bookings', requireAuth, ...)` forwards to Booking. `B/booking/controller/BookingController.pick` builds the caller from the JWT
   (`sub`, email, name) and calls `B/booking/service/HoldService.pick`.
3. `HoldService.pick`: `checked` (the token must have an e-mail), `prepare` (validates the trip is OPEN and not departed, the stops and the seat or coach; prices the
   journey with `BookingPricing`), then `pickInTransaction` in ONE transaction. The lock order is fixed by `BookingLocks`: the trip row first (`inventory.lockTrip`), then the user's
   active booking row. The first pick creates the booking (`insertBooking`: `PENDING_PAYMENT`, `expires_at = now + 5 min`; the unique index `uk_booking_active_hold` allows one active hold
   per user and trip). Later picks join it and never change `expires_at`. `pickSeat` inserts one `seat_reservation` row per segment in sorted order; an `ORA-00001` means the seat is taken
   (409 `seats_unavailable` for that pick alone). `pickStanding` does a conditional `UPDATE standing_usage` per segment and checks the updated row count.
4. Still inside the transaction, `AvailabilityEvents.seats` / `standing` (`B/live/AvailabilityEvents.java`) take the next per-trip version number and publish a `SeatEvent`
   (`HELD`); `AvailabilityEvents.toUser` sends the holder a `UserEvent`. Pricing is recomputed from the booking's items after each pick.
5. Only after the commit, `B/live/LiveUpdates.seatEvent` and `userEvent` (`@TransactionalEventListener(AFTER_COMMIT)`) send the messages through `SimpMessagingTemplate`
   to `/topic/trips/{tripId}` and `/user/queue/holds`.
6. The browser listens through `FE/live/connectLive.ts` (STOMP over WebSocket, token in the subprotocol). The connection goes to the Gateway `GW/wsproxy.js`
   (`attachWebSocketProxy`: verifies the token on the HTTP upgrade, checks the origin, pipes to Booking). In Booking, `B/live/WebSocketConfig`, `HandshakeAuthentication` (reads the JWT from the subprotocol)
   and `SubscriptionGuard` (only SUBSCRIBE to the trip topic and the user queue) accept it.
7. `FE/live/applySeatEvent.ts` applies the event to the seat map: it drops stale versions and reloads the full availability on a version gap or after a reconnect.
8. Release: `POST /bookings/{id}/release` (`BookingController.release` then `HoldService.release`, which ends the hold as `ABANDONED` when the last item goes, through
   `HoldReleaseService.release`) or `DELETE /bookings/{id}/hold` (`releaseAll`). Before any change, `HoldService.settlePaymentBefore` asks Payment to invalidate or cancel a payment already started.

## 3. Card payment and the Stripe webhook

1. "Continue to payment" in `SeatMapPage` calls `POST /bookings/{id}/payment`: `BookingController.startPayment` then `B/booking/service/PaymentStartService.start`. Under the booking lock it
   re-checks the hold and sets `payment_started` (never cleared); then, outside any transaction, it calls Payment through `B/payment/PaymentClient` (`PaymentPort`).
2. Payment: `P/payment/controller/PaymentController` (`POST /internal/payments`) then `P/payment/service/PaymentService.create`. It locks the payment row of the booking, decides in a short
   transaction, calls Stripe outside any transaction through `P/stripe/StripeGateway` (real implementation `StripeApiGateway`; test keys only, guarded by `StripeKeyGuard`), then records the
   PaymentIntent in a second transaction. The Stripe idempotency key is `payment-<booking>-r<revision>`. The answer carries the client secret.
3. `FE/pages/PaymentPage.tsx` shows the countdown (`FE/countdown.ts`) and `FE/components/StripeCheckout.tsx`, which loads Stripe.js and calls `confirmPayment` (Payment Element). Card data goes to Stripe only.
4. Stripe sends `payment_intent.succeeded` to `POST /webhooks/payment`. `GW/app.js` takes the raw body (`express.raw`, the signature covers exact bytes) and forwards only
   `Content-Type` and `Stripe-Signature` to `P/webhook/controller/WebhookController`, then `P/webhook/service/WebhookService.handle`: verify the signature (`Webhook.constructEvent`, 5 min tolerance),
   then ONE transaction `process`: insert the event id into `webhook_event` (a duplicate gives `ORA-00001`, answered as DUPLICATE) and apply the effect: `paymentEvent` locks the payment row
   (`findByIntentForUpdate`) and calls `PaymentService.recordSuccess`, which queues a `PAYMENT_RESULT` row in Payment's outbox.
5. `P/outbox/service/OutboxScheduler` runs `OutboxDispatcher.dispatchDue`, which calls `HttpBookingNotifier` to send `POST /internal/bookings/{id}/payment-result` to Booking
   (retries 2..60 s, in order per booking).
6. Booking: `B/booking/controller/InternalPaymentController.paymentResult` then `PaymentResultService.paymentSucceeded`. The paid amount must equal the booking's amount, even if the five minutes have
   just run out (payment wins). Then `ConfirmationService.confirm` / `confirmLocked` (one transaction under the locks): the reservations lose their expiry, the booking becomes `CONFIRMED`,
   a `SEAT_BOOKED` event is published (delivered after commit), and a `TICKET_EMAIL` job is queued in the same transaction. A different amount or a gone hold ends the hold and answers
   `REFUND_REQUIRED`; Payment then refunds (`RefundService.request`).
7. The payment page waits for the server's `CONFIRMED` (it never trusts the browser result) and moves to `/bookings/:id/ticket`.

## 4. Ticket and email

1. `ConfirmationService` queued `TICKET_EMAIL` in `B/outbox` (written by `OutboxWriter.queue`, which requires the confirming transaction).
2. `B/outbox/service/OutboxScheduler` (every second) calls `OutboxDispatcher.dispatchDue`, which runs the due jobs, jobs of one booking in order. `TicketEmailJob.run` does the work.
3. `TicketEmailJob.run` calls Ticket (`B/ticket/TicketClient`): `PUT /internal/tickets/{bookingId}` handled by `T/ticket/controller/TicketController` and `T/ticket/service/TicketService.issue`
   (201 for a new ticket, 200 for an existing one; a repeat never revives an invalidated ticket). `QrTokens` signs the QR payload (`reference.nonce.HMAC-SHA256`), `QrImages` draws the QR with ZXing,
   `TicketPdfRenderer` builds the one-page PDF with OpenPDF; the PDF is stored as a BLOB in table `ticket`.
4. The job reads the PDF (`GET /internal/tickets/{id}/pdf`) and calls Notification (`B/notification/NotificationClient`): `POST /internal/notifications` handled by
   `N/notification/controller/NotificationController` and `NotificationService.accept`, which stores the email (text and HTML from `EmailTemplates`, the PDF as attachment, a `dedupeKey`) and answers 202.
5. `N/notification/service/EmailScheduler` runs `EmailDispatcher.dispatchDue` every second; `N/mail/SmtpEmailSender` sends by Spring Mail (Mailpit in dev, port 1025). A failure backs off 2..300 s and
   is FAILED after 300 attempts. An email or ticket failure never changes the booking.
6. The user can open the ticket in the browser: `GET /bookings/{id}/ticket` and `/ticket/qr` (`GW/ticket.js`: `ticketPdf`, `ticketQr`, after an owner check in Booking). "Resend ticket" in
   `FE/pages/MyBookingsPage.tsx` calls `POST /bookings/{id}/resend-ticket` (`TicketResendService.resend`, queues `TICKET_EMAIL` unless one is waiting).

## 5. Cancellation and refund

1. The passenger clicks Cancel in `FE/pages/MyBookingsPage.tsx` (with a confirmation step): `POST /bookings/{id}/cancel`, `BookingController.cancel`, owner check by `sub` (another user's booking gives 404).
   An admin does it as support from `FE/components/BookingAdminPanel.tsx` (page `SupportPage`): `POST /admin/bookings/{id}/cancel` with a reason, `AdminBookingController.cancel` (Gateway: ADMIN only).
2. Both call `B/booking/service/CancellationService.cancel(Actor, id, reason)`; `Actor.user` / `Actor.admin` carries the id and role. `cancelInTransaction` runs ONE transaction under
   `BookingLocks`: re-checks `CONFIRMED` and the cutoff (the booking's own boarding stop has not departed; else 409 `trip_departed`; not confirmed: 409 `not_cancellable`), gives the seats back
   (`BookingInventory.giveBack`, which publishes `RELEASED` events), sets `CANCELLED` with `cancelled_by`, `cancelled_by_role`, `cancelled_at` (and `cancel_reason` for an admin) and `refund_status` by payment method (`CARD`: `REFUND_PENDING`; `COUNTER`: `MANUAL_REFUND_DUE`; `COMPLIMENTARY`: `NOT_APPLICABLE`),
   and queues `TICKET_INVALIDATE`, `CANCELLATION_EMAIL` and, for `CARD` only, `REFUND_REQUEST` in the outbox. An already cancelled booking is returned as it is. Stripe is only ever asked for a card booking (full refund).
3. The outbox jobs (`B/outbox/service/CancellationJobs`, run by `OutboxDispatcher`): invalidate the ticket (`T/.../TicketService.invalidate`), ask Payment for the refund
   (`B/payment/RefundPort` to `POST /internal/refunds`, `P/refund/service/RefundService.request` then `attempt`, which calls Stripe outside a transaction), send the cancellation email.
4. Stripe confirms with the `refund.updated` webhook (same path as walkthrough 3): `WebhookService.process` then `RefundService.applyStripeEvent`, which queues a `REFUND_STATUS` message.
   Payment's outbox delivers it to `POST /internal/bookings/{id}/refund-status` (`InternalPaymentController.refundStatus`, `PaymentResultService.refundStatus`), which sets `REFUNDED` and queues the `REFUND_EMAIL` once
   (`REFUND_FAILED` is only flagged for follow-up). `RefundRetrier` retries refunds that could not be sent.

## 6. Counter and complimentary booking

Rule: `docs/design/COUNTER-BOOKING.md`. An admin confirms a hold without Stripe.

1. The admin opens the board (`FE/pages/TripsBoard.tsx`, shown in `SupportPage`), opens a trip (`FE/components/BoardDetailPanel.tsx`) and picks seats with `FE/components/AdminSeatPicker.tsx`, which uses the
   normal `POST /bookings/pick` with the admin's own token (the hold belongs to the admin) and the same live updates.
2. `FE/components/IssueBookingForm.tsx` collects the method (`COUNTER` or `COMPLIMENTARY`), the note or the reason (5 to 500 characters for complimentary), and the passenger: an existing account
   (looked up by exact email with `GET /admin/passengers/lookup`, `AdminBookingController.lookup`, `BookingIssueService.lookup`) or a walk-in (name, optional phone and email).
3. It sends `POST /admin/bookings/{id}/issue` (Gateway `/admin/bookings`, ADMIN only; `AdminBookingController.issue`, then `B/booking/service/BookingIssueService.issue`). `validate` checks the input (400 `validation_failed`);
   `issueInTransaction` locks the booking (`BookingLocks.lock`), returns the booking untouched on an identical repeat (idempotent), and refuses with 404 (not the admin's hold), 409 `hold_expired`,
   409 `payment_started` or 409 `not_issuable`. It computes the charged amount on the server (full fare for `COUNTER`, zero for `COMPLIMENTARY`), writes the audit columns with `markIssued`, then calls the SAME
   `ConfirmationService.confirmLocked` as the card path (payment wins does not apply here: an expired hold is refused), and records an `ISSUE` audit entry. Stripe is never called.
4. The ticket and email follow as in walkthrough 4. A walk-in without an email gets a ticket but no email.
5. Counter refunds: `POST /admin/bookings/{id}/refunded-at-counter` (`BookingIssueService.markRefundedAtCounter`) records that the admin handed the money back: only a cancelled counter booking with `MANUAL_REFUND_DUE` qualifies (409 `not_refund_due`), the result is `REFUNDED_AT_COUNTER`, with a `COUNTER_REFUND` audit entry.

## 7. The live trips board

1. `FE/pages/TripsBoard.tsx` uses the hook `FE/useBoardData.ts`: it polls the list every 5 seconds, stops while the tab is hidden, refreshes at once when the tab is shown, and highlights rows whose numbers changed.
2. List: `GET /admin/board/trips?from=&to=&state=` goes through the Gateway (ADMIN only) to `B/board/controller/BoardController.trips`, `B/board/service/BoardService.board`, which builds rows in `build`:
   per trip `BoardRepository.seatCounts` (per coach class), `standingUsage` and `standingBookings`; `StandingBoardCounts.of` applies the board's standing counting rule; `TripLifecycle.of`
   gives `UPCOMING`, `DEPARTED`, `ARRIVED` or `CANCELLED` from the clock and the stop times; `TripLifecycle.isFull` marks full trips. Times are shown in the railway's time zone (`trainbooking.timezone`).
   `summarise` makes the totals.
3. Detail: `GET /admin/board/trips/{id}` is composed by the Gateway (`GW/board.js`: `boardTripDetail`, `composeBoardDetail`) from Train & Schedule's layout and Booking's `BoardController.detail` / `BoardService.detail`
   (every seat with its status and holder). `BoardDetailPanel.tsx` draws it, with cancel-booking and issue actions.
4. Counting rule: confirmed bookings of every payment method count as confirmed, exactly as the counter-booking rule states (booking status is unchanged by issuing).

## 8. Whole-trip cancellation

1. The admin uses `FE/components/TripCancellation.tsx` on the admin Trips page (`FE/pages/Trips.tsx`): `POST /admin/trips/{id}/cancel` `{ reason }`, then `GET /admin/trips/{id}/cancellation` to show progress.
   The Gateway routes these two exact paths to Booking before the generic `/admin/trips` rules (`GW/app.js`).
2. `B/tripcancel/controller/AdminTripController.cancel` calls `B/tripcancel/service/TripCancellationService.request`: one transaction locks the trip copy; if it is `OPEN` and the first stop has not departed
   (else 409 `trip_departed`) it marks `CANCELLING` (closed to new holds at once) and writes an audit entry. Then `process` runs.
3. `process(tripId)` is safe to call many times: (a) tell Train & Schedule (`B/schedule/TrainScheduleClient.markTripCancelled` to `TS/trip/controller/InternalTripController.cancel`, idempotent, no push back);
   (b) for each active hold `releaseHold` cancels its payment first and releases it as `EXPIRED` with the reason TRIP_CANCELLED ("This trip was cancelled"); (c) for each confirmed booking
   `CancellationService.cancelForTrip` as the admin, without the boarding-stop cutoff, so refunds, ticket invalidation and emails follow as in walkthrough 5; (d) when nothing is `PENDING_PAYMENT` or `CONFIRMED` any more
   and Train & Schedule was told, `markCancelled` sets `CANCELLED` and `AvailabilityEvents.tripCancelled` pushes `TRIP_CANCELLED` after commit.
4. `TripCancellationWorker` (every 5 s) calls `process` again for trips still `CANCELLING`, so a failed step is retried. In the UI `CANCELLING` reads "CANCELLED - bookings pending".

## 9. Hold expiry and the sweeper

1. `B/booking/service/HoldSweeper.sweep` (`@Scheduled(fixedDelayString = "${trainbooking.sweeper.fixed-delay-ms:1000}")`) calls `ExpiryService.sweep` outside any transaction.
2. `ExpiryService.sweep` reads the due holds (`BookingRepository.findDueHoldIds`: `PENDING_PAYMENT`, `expires_at` past, `next_attempt_at` due) and calls `expire(id)` for each, catching errors one by one so one bad booking
   never stops the others (failure leads to `retryLater`, a backoff 2, 4 ... 60 s in `next_attempt_at`).
3. `ExpiryService.expire` refuses to run inside a transaction. If the booking never opened the payment page (`payment_started` false) there is nothing to cancel. Otherwise it asks Payment first:
   `PaymentPort.cancelPending` (`PaymentClient` to `P/payment/service/PaymentService.cancelPending`, which cancels the Stripe PaymentIntent or reports it already `SUCCEEDED` or `PROCESSING`).
   - `NONE` or `CANCELLED`: `HoldReleaseService.release(..., EXPIRED, onlyIfPastExpiry = true, "system", ...)` in one transaction under the locks gives the seats back (`BookingInventory.giveBack`),
     sets `EXPIRED` and publishes `RELEASED` plus a `HOLD_EXPIRED` user event after commit. If the payment page was opened in the meantime, `PaymentStartedMeanwhileException` rolls back and the next sweep asks Payment.
   - `SUCCEEDED` or `PROCESSING`: nothing is released; the sweeper asks again in 1 s. A succeeded payment confirms the booking only through Payment's own report (walkthrough 3, step 6). Booking never
     confirms by itself in the sweeper.
   - Payment unreachable (`PaymentUnavailableException`): `retryLater` with backoff; the seats stay held.
4. The browser: `FE/pages/PaymentPage.tsx` and `SeatMapPage.tsx` react at once to the `HOLD_EXPIRED` user event (or to the booking being `EXPIRED` when polled) and redirect to the seat map with the message
   "Your 5 minutes ran out. The seats were released, please select again." An expired hold that is not yet released still shows as held to everyone else (the availability query never reads the clock).
