# Counter and complimentary bookings (spec delta)

Status: approved by Seif. This text is meant to be merged into `docs/SRS.md` and `docs/ARCHITECTURE.md`. It adds ONE business rule: an admin can confirm a booking from an active hold **without a Stripe payment**. Everything else stays as it is (hold from the first pick for 5 minutes, joins never extend it, 1 s sweeper, database-enforced no double booking, full-journey availability, maximum seats per booking, passenger card payments with "payment wins", the full-refund rule for Stripe payments). Stripe is never called for an admin-issued booking. Payment keeps refusing non-test keys and is not changed.

## 1. Payment methods

A booking has a payment method: `CARD` (the existing one, Stripe, the default for every existing booking), `COUNTER` or `COMPLIMENTARY`.

| Method | Meaning | Amount recorded (`amount`) | Amount charged (`charged_amount`) | Required text |
|---|---|---|---|---|
| `CARD` | The passenger paid by card through Stripe. | the fare | (empty: equals `amount`) | none |
| `COUNTER` | The passenger paid the fare in person at the station or support desk. | the full fare | the full fare | optional receipt or note, at most 200 characters |
| `COMPLIMENTARY` | No charge. | the fare value (for reporting) | 0 | **reason required**, 5 to 500 characters |

`amount` is always worked out on the server from the held seats (never taken from the client).

## 2. Who may issue

Only the role `ADMIN`: the Gateway checks it (401 without a token, 403 for a signed-in non-admin) and Booking & Seat checks it again.

## 3. The issue endpoint

`POST /admin/bookings/{id}/issue` (Booking & Seat, through the Gateway `/admin/bookings/**`). Body:

```json
{ "method": "COUNTER", "note": "receipt 4411",
  "passenger": { "mode": "ACCOUNT", "email": "name@example.com" } }
```
```json
{ "method": "COMPLIMENTARY", "reason": "Press pass",
  "passenger": { "mode": "WALK_IN", "name": "Full Name", "phone": "optional", "email": "optional" } }
```

* `method` is `COUNTER` or `COMPLIMENTARY` (`CARD` is refused: 400). `reason` is required for `COMPLIMENTARY` (400 `validation_failed` if missing or shorter than 5). `note` is optional for `COUNTER`.
* The booking is the **active hold of the issuing admin** (holds are made with the normal pick endpoints with the admin's own token, so the hold belongs to the admin).
* It uses the **same confirmation logic as the Stripe path** (`ConfirmationService`): under the one lock order (trip, booking), the reservation rows lose their expiry, the booking becomes `CONFIRMED`, the `SEAT_BOOKED` event is published after commit, and the `TICKET_EMAIL` job is queued in the same transaction. The database keys (`seat_reservation` primary key) are the ones that already protect the seats; nothing new can double-book.
* Errors, all with nothing created or changed:
  * `404` unknown booking, or a booking that is not the admin's own hold;
  * `409 hold_expired`: the hold ran out (`expires_at` not in the future) or was released/expired/abandoned;
  * `409 payment_started`: a card payment page was already opened for this hold (a Stripe intent may exist; the hold must be released or paid by card);
  * `409 not_issuable`: the booking is `CANCELLED` or already confirmed by another method or by another admin;
  * `404 passenger_not_found`: ACCOUNT mode with no exact match;
  * `400 validation_failed`: missing reason, missing walk-in name, field too long, bad email.
* **Idempotent:** repeating the same request for a booking that this admin already issued with the same method returns that booking (200) and creates nothing.
* `200` answer: the booking view with `paymentMethod`, `chargedAmount` and the issue audit.

## 4. Passenger identity (two modes)

* **Existing account** (`mode: ACCOUNT`): `GET /admin/passengers/lookup?q=<exact email>` (ADMIN only) returns **at most one** `{ id, name, email }` or `404`. The match is the exact email (case-insensitive, no partial match, no listing). The source is the user data Booking & Seat already stores (the email and name taken from the token of each booking), so it finds passengers who have booked before; there is no call to Keycloak (that would need a new Keycloak client and role). The login of this realm is the email, so the email is also the username. Issuing repeats the lookup on the server; the booking then belongs to that user (`user_id`, `user_email`, `user_name` are set to the passenger's), so it appears in their My bookings, their ticket and PDF work, and the email goes to them.
* **Walk-in** (`mode: WALK_IN`): `name` required (at most 200), `phone` and `email` optional (at most 40 and 254). The booking stays owned by the issuing admin (so the admin can download the ticket) and stores `walk_in_name`, `walk_in_phone`, `walk_in_email`. The ticket carries the walk-in name. If an email was given the ticket email goes there; otherwise **no email is sent** (the ticket is still made).

## 5. Audit fields (table `booking`, migration V8)

`payment_method`, `charged_amount`, `issued_by` (admin user id), `issued_at`, `issue_note` (the receipt note or the reason), `walk_in_name`, `walk_in_phone`, `walk_in_email`, `counter_refunded_by`, `counter_refunded_at`. The audit log gets an `ISSUE` entry (actor = admin, method, amount, charged amount, note/reason, mode) and a `COUNTER_REFUND` entry; both are also logged to OpenObserve like every audit record. Admins see these fields in the support search and details and on the board; passengers see only the payment method.

## 6. State transitions

* Booking status is unchanged: `PENDING_PAYMENT` (the hold) → `CONFIRMED` (issue) → `CANCELLED`. Counter and complimentary bookings are `CONFIRMED` like any other, so **the board's counting rule does not change**.
* Refund status gains three values: `NOT_APPLICABLE`, `MANUAL_REFUND_DUE`, `REFUNDED_AT_COUNTER` (next to `NONE`, `REFUND_PENDING`, `REFUNDED`, `REFUND_FAILED`).

## 7. Cancelling an admin-issued booking

The existing admin cancellation (`POST /admin/bookings/{id}/cancel` with a reason, and the whole-trip cancellation) is used. Seats are freed, the ticket QR is invalidated, the cancellation email is queued, `cancelled_by` and the reason are recorded, **no Stripe or Payment call is made**:

| Method | Refund status after cancelling | Notes |
|---|---|---|
| `CARD` | `REFUND_PENDING` (unchanged path, full Stripe refund) | unchanged |
| `COMPLIMENTARY` | `NOT_APPLICABLE` | nothing was charged |
| `COUNTER` | `MANUAL_REFUND_DUE` | the money is returned at the counter |

`POST /admin/bookings/{id}/refunded-at-counter` (ADMIN) moves a cancelled `COUNTER` booking from `MANUAL_REFUND_DUE` to `REFUNDED_AT_COUNTER` and records the admin and the time. Repeating it returns the booking as it is (idempotent); any other state answers `409 not_refund_due`.

**Passenger self-cancel (decision):** a passenger cannot cancel a `COUNTER` or `COMPLIMENTARY` booking themselves: `409 cancel_at_desk` ("This booking was issued at the support desk. Ask the support desk to cancel it."), and My bookings shows `cancellable: false` with the same hint. Reason: the money for a counter booking is returned at the counter, and a complimentary booking was given by staff. The departure cutoff of the existing rule applies to the admin cancel as before.

## 8. Ticket, PDF and emails

* Ticket request gets `paymentMethod` (default `CARD`). The PDF payment line is `Total paid  <amount> EGP` for CARD, `Paid at counter  <amount> EGP` for COUNTER and `Complimentary  (no charge, fare value <amount> EGP)` for COMPLIMENTARY. The red logo and the QR apply as usual.
* Ticket email: the same wording for the total line.
* Cancellation email: CARD unchanged ("is being refunded to your card"); COUNTER: "The refund of <amount> is handled at the support desk."; COMPLIMENTARY: "Nothing was charged for this booking, so there is no refund."

## 9. Board additions (read-only data)

Board seat states carry `holdExpiresAt` for `HELD` seats. The board detail of a TAKEN seat links to the booking through its reference; the admin booking view shows the method and the audit line.

## 10. Places in the existing documents that this affects

`docs/SRS.md`: the booking data model (new columns, section 8); the booking states and refund states (section 4/5, new refund values); FR-P (payment: add the two non-Stripe methods and say Stripe is not called for them); FR-C (cancellation by admin: refund behaviour per method, passenger cannot cancel counter/complimentary bookings); FR-A10 and FR-C7 (audit: issuer, method, reason); FR-T (ticket payment line) and FR-T3 (email wording); API endpoints (`POST /admin/bookings/{id}/issue`, `POST /admin/bookings/{id}/refunded-at-counter`, `GET /admin/passengers/lookup`, `GET /admin/board/trips[/{id}]`, `GET /bookings/{id}/ticket/qr`); error codes (`hold_expired`, `payment_started`, `not_issuable`, `cancel_at_desk`, `not_refund_due`, `passenger_not_found`); acceptance criteria.

`docs/ARCHITECTURE.md`: Flow 4 (add the admin path that confirms without Payment/Stripe, via the same confirmation); Flow 6 (refund per method; no Payment call for counter and complimentary); Flow 7 (support: lookup, issue, mark refunded at counter, the live trips board); the Gateway route list (admin routes above); the call directions (Booking never calls Payment for these bookings); the statement that every booking is paid through Stripe.

`CLAUDE.md` ("Rules that must not be broken" and "Stripe"): add that admin-issued bookings are the one exception to "confirmed only by Payment's report".
