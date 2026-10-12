# Train Booking System

## 1. Microservices Architecture

![Train Booking System - Microservices Architecture](docs/architecture.png)

## 2. Flows

### Flow 1 — Register and login

**Register**
1. Web App sends the registration data (name, email, password) to the Gateway.
2. Gateway forwards it to the Auth Service.
3. Auth saves the user in PostgreSQL. The password is stored as a hash.
4. Auth confirms the account was created.

**Login**
1. Web App sends email and password to the Gateway.
2. Gateway forwards the request to Auth.
3. Auth looks up the user and verifies the password hash.
4. Auth signs a **JWT** containing the user id and role (`USER` / `ADMIN`) and returns it.
5. Web App sends the JWT with every later request. The Gateway verifies it before routing.

### Flow 2 — Search trips and view the seat map

**Search**
1. Web App sends the search (from, to, date) to the Gateway.
2. Gateway verifies the JWT and forwards the request to Train & Schedule.
3. Train & Schedule returns the trips that run from the origin to the destination on that date, with times.

**Seat map**
1. User opens a trip. Web App sends the trip, boarding stop and destination stop to the Gateway.
2. Gateway gets the coaches, seat layout and standing capacity from Train & Schedule.
3. Gateway gets the availability for this journey from Booking & Seat.
   - A seat is available only if it is free on **every segment** between the boarding stop and the destination.
   - Standing places left = the lowest remaining count across those segments.
4. Web App draws the seat map: each seat is **available**, **held**, **booked** or **selected**, with the standing places left.

**Live updates**
1. Web App opens a WebSocket for the trip, through the Gateway.
2. When any seat is held, released or booked, Booking & Seat pushes the change.
3. Web App updates the seat map instantly, without reloading. If a seat the user selected becomes unavailable, it is deselected and the user is told.
4. After a reconnect, the Web App reloads the full availability to avoid missing changes.

### Flow 3 — Hold seats

1. User confirms the selected seats and/or standing places. Web App sends the trip, journey and selection to the Gateway.
2. Gateway forwards it to Booking & Seat.
3. Booking & Seat tries to hold the seats on **every segment** of the journey in one database transaction. The database rejects a seat that is already held or booked on any segment. The hold is all-or-nothing.
4. **If any seat was just taken:** nothing is held. Booking returns "no longer available" with the lost seats, and the Web App refreshes the seat map.
5. **If all seats are free:**
   - The booking is created as `PENDING_PAYMENT` with `expires_at` = now + **5 minutes**.
   - Booking returns `expires_at` and the Web App opens the payment page with a 5:00 countdown.
   - Booking pushes "seats held" over WebSocket, so other users see them as held immediately.

### Flow 4 — Pay and receive the ticket

**Start payment**
1. User clicks Pay. Web App sends the request to the Gateway, which forwards it to Booking & Seat.
2. Booking checks that the hold is still active.
3. Booking asks Payment to create a payment. The amount is calculated on the server.
4. Payment creates the payment with Stripe and gets a payment link.
5. The link goes back to the Web App, which opens Stripe's hosted payment page.

**Pay**
1. User enters card details on Stripe's page. Card data never reaches our system.
2. Stripe sends a **webhook** with the result to the Gateway, which forwards it to Payment.
3. Payment verifies the Stripe webhook signature and ignores duplicate events.
4. Payment tells Booking that the payment succeeded.
5. Booking checks the hold is still valid and sets the booking to `CONFIRMED`. If the hold already expired, the payment is refunded instead (Flow 5).

**Ticket and email**
1. Booking asks the Ticket Service to generate the ticket (PDF with QR code).
2. Booking asks the Notification Service to email the ticket to the user.
3. Notification sends the email through the email provider. This runs asynchronously and retries on failure. **An email failure never cancels a booking.** The user can re-send the ticket from "My bookings".
4. Booking pushes "seats booked" over WebSocket and the Web App shows the confirmation.

### Flow 5 — Hold timeout and late payment

**Hold expires with no payment**
1. A job inside Booking & Seat runs every few seconds and finds holds past `expires_at`.
2. Booking releases the seats on every segment and sets the booking to `EXPIRED`.
3. Booking asks Payment to cancel the pending Stripe payment.
4. Booking pushes "seats released" over WebSocket.
5. The user's payment page shows **"Session timed out"** and the Pay button is disabled. Other users see the seats available again immediately.

**Late payment (user paid just after the timeout)**
1. Stripe sends a payment-success webhook through the Gateway to Payment.
2. Payment asks Booking to confirm. Booking refuses because the hold expired and the seats may be taken.
3. Payment refunds the full amount through Stripe.
4. Booking asks Notification to email the user that the payment was refunded.

### Flow 6 — Cancel and refund

1. User cancels a booking. Web App sends the request to the Gateway, which forwards it to Booking & Seat.
2. Booking checks that the booking belongs to the user, is `CONFIRMED`, and the train has not departed.
3. Booking releases the seats on every segment and sets the booking to `CANCELLED`.
4. Booking asks the Ticket Service to invalidate the ticket's QR code.
5. Booking asks Payment to refund. Payment sends the refund request to Stripe. The refund status becomes `REFUND_PENDING`.
6. Stripe later confirms the refund by webhook. The status becomes `REFUNDED`, or `REFUND_FAILED` for admin follow-up.
7. Booking asks Notification to email the cancellation and refund details.
8. Booking pushes "seats released" over WebSocket, so other users' seat maps update immediately.

### Flow 7 — Admin: manage trains

All admin requests carry a JWT with the `ADMIN` role. The Gateway rejects other roles with `403`.

**Create a train**
1. Admin submits the train (number, name, type, status).
2. Gateway checks the role and forwards the request to Train & Schedule.
3. Train & Schedule saves the train in PostgreSQL.

**Define coaches, seats and standing capacity**
1. Admin adds a coach (code, class), its seats (number, row, column) and its **standing capacity**.
2. Train & Schedule saves the coaches, seats and capacity. These define the seat map shown to passengers.

**Update**
1. Admin edits a train, coach, seat or capacity.
2. Removing seats or lowering standing capacity is **rejected** if future trips already have holds or confirmed bookings that use them.

**Delete**
1. Admin deletes a train.
2. Train & Schedule checks for future trips with confirmed bookings.
3. If there are any, the delete is refused (deactivate the train instead). Otherwise the train is deleted.

**Read**
- Admin lists and filters trains by number, name and status.
