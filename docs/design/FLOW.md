# Page map and flow

Who can open a page: **Anyone** (signed out too), **Passenger** (signed in), **Admin** (role ADMIN). A signed-out visitor who opens a Passenger or Admin page is sent to Keycloak and comes back to the same address (`returnTo`). A signed-in non-admin who opens an admin page goes to `/account` with a notice.

The logo links to `/` on every page. The header holds one nav (Search trains; My bookings when signed in; Admin for admins) and, on the right, "Sign in" / "Create account" or the user menu (Account, My bookings, Admin, Sign out). The footer holds help and policy links only.

| Route | Who | Back goes to | Main next action |
|---|---|---|---|
| `/` landing | Anyone | (none; it is home) | Search trains; recent searches as chips |
| `/search?from&to&date` | Passenger | `/` | Choose a fare class on a trip, then `/trips/:id/seats` |
| `/trips/:id/seats?from&to` | Passenger | `/search` with the last search | Pick seats (the hold starts), then Continue to payment |
| `/bookings/:id/pay` | Passenger (owner) | `/trips/:id/seats` (seats stay held) | Pay; on success go to the ticket |
| `/bookings/:id/ticket` | Passenger (owner) | `/bookings` | Download PDF, Email again, My bookings, Search another trip. The boarding pass has a white header with the red logo and shows the QR code (the same code as in the PDF) |
| `/bookings` | Passenger | `/account` | Open a ticket, continue a hold, cancel |
| `/account` | Passenger | `/` | My bookings, Search trains, Sign out |
| `/help`, `/refunds`, `/terms`, `/privacy`, `/contact` | Anyone | `/` | Links between them |
| `/admin` | Admin | `/` (Back to site) | Open a section |
| `/admin/stations`, `/trains`, `/trains/:id`, `/routes`, `/routes/:id/fares`, `/trips`, `/support` (two views: Trips board, Find a booking; `?view=bookings`) | Admin | The section above it (breadcrumb), Back to site | The page's own action |
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

## Live trips board (admin support)

Decision: the board and the booking search are two **views** (chips at the top, `/admin/support` and `/admin/support?view=bookings`), not two pages, so Support stays one entry in the sidebar. The board is the default view.

- **Data**: `GET /admin/board/trips?from=&to=&state=` (Booking & Seat, through the Gateway, ADMIN only) and `GET /admin/board/trips/{id}` (composed by the Gateway from the layout and the seat states). Read-only.
- **Refresh**: polling every 5 s (paused while the tab is hidden, immediate when it is visible again, doubling back-off after errors, up to 1 minute). The last data stays on screen when a refresh fails; the status line says "Connection lost, retrying". The WebSocket is not used: it would need an admin topic and a new authorization rule.
- **Filters** live in the address: `range` (live window, today, tomorrow, next7, last7, custom with `date`), `state` (a comma list of UPCOMING, DEPARTED, ARRIVED, FULL, CANCELLED), `q` (train or station text). The summary tiles are the same state filters.
- **States**: Upcoming, Departed (left the first stop, not yet at the last), Arrived, Cancelled (a trip being cancelled counts as cancelled), plus the Full flag on an upcoming trip. **Full means no seat (and no standing place) is free for the whole journey.**
- **Rows** keep the order of the departure time; a row whose numbers changed is highlighted for two seconds (no motion with reduced motion). Enter or click on a row opens the trip in a slide-over (counts per class, a read-only coach plan with free / held / taken seats, the booking reference as plain text on taken seats, the data time); Escape closes it and focus returns to the row.
- **New Gateway routes**: `GET /admin/board/trips`, `GET /admin/board/trips/{id}`, `GET /bookings/{id}/ticket/qr`.
