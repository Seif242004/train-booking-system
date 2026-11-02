# Page map and flow

Who can open a page: **Anyone** (signed out too), **Passenger** (signed in), **Admin** (role ADMIN). A signed-out visitor who opens a Passenger or Admin page is sent to Keycloak and comes back to the same address (`returnTo`). A signed-in non-admin who opens an admin page goes to `/account` with a notice.

The logo links to `/` on every page. The header holds one nav (Search trains; My bookings when signed in; Admin for admins) and, on the right, "Sign in" / "Create account" or the user menu (Account, My bookings, Admin, Sign out). The footer holds help and policy links only.

| Route | Who | Back goes to | Main next action |
|---|---|---|---|
| `/` landing | Anyone | (none; it is home) | Search trains; recent searches as chips |
| `/search?from&to&date` | Passenger | `/` | Choose a fare class on a trip, then `/trips/:id/seats` |
| `/trips/:id/seats?from&to` | Passenger | `/search` with the last search | Pick seats (the hold starts), then Continue to payment |
| `/bookings/:id/pay` | Passenger (owner) | `/trips/:id/seats` (seats stay held) | Pay; on success go to the ticket |
| `/bookings/:id/ticket` | Passenger (owner) | `/bookings` | Download PDF, Email again, My bookings, Search another trip |
| `/bookings` | Passenger | `/account` | Open a ticket, continue a hold, cancel |
| `/account` | Passenger | `/` | My bookings, Search trains, Sign out |
| `/help`, `/refunds`, `/terms`, `/privacy`, `/contact` | Anyone | `/` | Links between them |
| `/admin` | Admin | `/` (Back to site) | Open a section |
| `/admin/stations`, `/trains`, `/trains/:id`, `/routes`, `/routes/:id/fares`, `/trips`, `/support` | Admin | The section above it (breadcrumb), Back to site | The page's own action |
| `*` | Anyone | `/` | Search trains, Go to the home page |
| (render error) | Anyone | `/` | Go to the home page |

## Booking flow

Four steps are shown on the seat, payment and ticket pages: Search, Seats, Payment, Ticket. Finished steps are links while the hold is still running.

1. **Search** (`/search`): pick stations and a date; results show stops and fare chips. Choosing a fare opens the seat map.
2. **Seats**: the first seat or standing pick creates the hold (5:00, never extended). Further picks join it, deselecting releases at once, releasing the last item ends the hold. "Continue to payment" opens the payment page.
3. **Payment**: the hold timer and the order summary stay visible. "Back to seats" keeps the seats held. On success the page waits for the server's CONFIRMED, then replaces itself with the ticket page (no history trap).
4. **Ticket**: shows the boarding pass and the next actions.

A hold that is running shows a slim banner on every other page ("Your seats are held for 3:42. Continue to payment"), taken from `GET /bookings`.

## What happens when

- **Hold expires** (on the seat map or the payment page): the seats are released by the server, the user goes (replace) to the seat map of the same trip with a clear message and "Search again" / pick again. Never a blank page. No charge, no refund.
- **Payment fails** (card declined): the Payment page stays, shows a calm message with "Try again" and "Back to seats"; the hold keeps running.
- **Cancellation** (My bookings): inline confirmation, then a toast "Booking cancelled"; refund status is shown separately and a "Refund started" toast follows when the refund is pending.
- **Trip cancelled by an admin**: the user's hold or booking shows "This trip was cancelled"; refunds follow.
- **Session ends** (token cannot be refreshed): a "Your session ended" panel with a Sign in button that returns to the same page. Nothing is lost silently.
- **Sign-out**: goes to `/`. The browser Back button after sign-out cannot show private data because every private page asks for a token again.
- **Offline**: a slim banner at the top; actions that need the network fail with a recoverable message.

## Browser history rules

- Redirects after expiry, confirmation and guards use `replace`, so Back never returns to a dead page.
- Scroll position is restored on Back/Forward and reset on a new page; the page's h1 receives focus after each route change and the new page title is announced.
- Every route has its own document title.
