# 9. Frontend (React web app)

## Purpose
The web app is what passengers and admins see. It is a **single-page application**: the browser loads one HTML page and one bundle of JavaScript once, and
React draws every screen after that. Moving between screens changes the address (React Router) without a full page load.

It has two jobs and nothing else:
1. Show what the backend tells it (trips, seat maps, bookings, tickets, admin lists).
2. Send the user's actions to the **Gateway** (pick a seat, pay, cancel, edit a station).

It never decides anything that matters. Prices, the 5-minute hold, who wins a seat, refunds and cancellation rules are all decided by the services. The page
only displays the answer, so a user who edits the JavaScript in their browser gains nothing.

- Built with **Vite**, **TypeScript**, **React**, **React Router**, **keycloak-js** (login), **@stomp/stompjs** (live seat updates), **@stripe/react-stripe-js**
  (payment form). Plain CSS, no UI library.
- In Compose it is served by nginx on port **5173** (`infra/docker/frontend.Dockerfile`). During development `vite` serves it on the same port.
- Talks to **three** places only: the **Gateway** (all application data and the WebSocket), **Keycloak** (login, registration, logout, token refresh, in the
  browser) and **Stripe** (the card form inside Stripe's iframe).
- There are **no unit tests** in the frontend (the end-to-end smoke test in `scripts/smoke-test.mjs` covers the main flows through the real API).

## Folder structure
```
frontend/
  index.html, package.json, tsconfig.json, vite.config.ts      (not commented: configuration)
  public/brand/            logo and hero picture
  public/silent-check-sso.html   page Keycloak loads in a hidden iframe to find an existing session
  src/
    main.tsx, App.tsx      entry point and route table
    config.ts, api.ts      settings and the one fetch helper
    routes.ts              page titles and "Back" links per address
    auth/                  Keycloak adapter, session state, route guards
    components/            shared and page-specific building blocks
    pages/                 one file per screen (route)
    live/                  WebSocket client and the pure "apply a seat event" function
    styles/                tokens.css, base.css, components.css, shell.css, nav.css, feedback.css, pages/*.css
    *.ts                   types and small helpers (time, money, countdown, ics, recent searches)
```

## Every file, one line each
### Entry, settings, helpers
| File | What it does |
|---|---|
| `src/main.tsx` | Loads the CSS in a fixed order and mounts `<App />` in StrictMode. |
| `src/App.tsx` | Provider stack (ErrorBoundary, Toasts, Auth, Router) and the whole route table. |
| `src/config.ts` | Reads `VITE_*` settings (Keycloak url, realm, client id, Gateway url, support email) with development defaults. No secrets. |
| `src/api.ts` | `gatewayRequest`, `gatewayGet`, `gatewayDownload` and `readError`: every call to the Gateway goes through here with a fresh token. |
| `src/routes.ts` | Title and "Back" link for each address; used by `Layout` and `RouteEffects`. |
| `src/countdown.ts` | Server-time-corrected countdown of a hold (`startCountdown`, `remainingMs`, `formatRemaining`). |
| `src/format.ts` | `money` ("EGP 230.00") and `label` (enum to words). |
| `src/time.ts` | Helpers for trip times that are shown exactly as the API sends them (no browser time zone conversion). |
| `src/ics.ts` | Builds a calendar file for a booking and `saveFile`, the browser download trick. |
| `src/recentSearches.ts` | Last three searches in `localStorage` (this browser only, every access in try/catch). |
| `src/useDelayedFlag.ts` | Hook that turns true only after a wait of 300 ms (loading placeholders). |
| `src/useBoardData.ts` | Hook that polls the admin trips board every 5 s with back-off and change highlighting. |
| `src/adminTypes.ts`, `boardTypes.ts`, `bookingTypes.ts`, `seatMapTypes.ts` | TypeScript shapes of the API answers (no runtime code except a few label tables). |

### Auth (`src/auth/`)
| File | What it does |
|---|---|
| `keycloak.ts` | The single `keycloak-js` instance, `initKeycloak` (check-sso, PKCE S256), `getFreshToken`, and the "session ended" signal. |
| `AuthContext.tsx` | `AuthProvider` and `useAuth()`: user, roles, login, register, logout, background token refresh. |
| `guards.tsx` | `RequireAuth` (send to Keycloak if signed out) and `RequireAdmin` (role ADMIN, else back to /account). |

### Live updates (`src/live/`)
| File | What it does |
|---|---|
| `connectLive.ts` | Opens the STOMP connection through the Gateway and subscribes to the trip topic and the private hold queue. |
| `applySeatEvent.ts` | Pure function: applies one seat event to the map, or says "stale" or "reload". |
| `liveTypes.ts` | `SeatEvent` and `UserEvent` shapes. |

### Components (`src/components/`)
| File | What it does |
|---|---|
| `Layout.tsx` | Shell of every page: header, menu, hold banner, `<Outlet />`, footer. |
| `AdminLayout.tsx`, `AdminHeader.tsx` | Admin sidebar, breadcrumb and page heading. |
| `UserMenu.tsx` | Account menu with full keyboard behaviour. |
| `RouteEffects.tsx` | On navigation: document title, screen reader announcement, focus on the h1, scroll restore. |
| `HoldBanner.tsx`, `HoldCard.tsx` | Running-hold bar on every page; the countdown card. |
| `SessionEnded.tsx`, `OfflineBanner.tsx`, `ErrorBoundary.tsx`, `ErrorState.tsx`, `EmptyState.tsx`, `ListSkeleton.tsx`, `Toasts.tsx` | Feedback pieces: session over, offline, crash, failed load, empty list, placeholders, short messages. |
| `SearchForm.tsx`, `StationCombobox.tsx` | Search fields with blur-time checks; typing field with a station list. |
| `TrainStrip.tsx`, `SeatShape.tsx`, `CoachSeats.tsx`, `AdminSeatPicker.tsx` | Coach strip, seat drawing, admin seat layout editor, admin seat picker for counter bookings. |
| `BookingSteps.tsx`, `StripeCheckout.tsx`, `TicketView.tsx`, `TicketQr.tsx`, `CountUp.tsx` | Steps bar, Stripe card form, boarding pass, QR image, animated amount. |
| `BoardDetailPanel.tsx`, `BookingAdminPanel.tsx`, `IssueBookingForm.tsx`, `TripCancellation.tsx` | Admin: trip detail slide-over, one booking, issue booking at the counter, whole-trip cancel. |
| `SlideOver.tsx`, `PageNav.tsx`, `BrandLogo.tsx`, `HeroPhoto.tsx`, `GatewayResultView.tsx` | Modal panel with focus trap, back link, logo, hero picture, raw Gateway answer. |

### Pages (`src/pages/`)
| File | Route | Who |
|---|---|---|
| `Landing.tsx` | `/` | anyone |
| `InfoPages.tsx` | `/help`, `/refunds`, `/terms`, `/privacy`, `/contact` | anyone |
| `SearchTrips.tsx` | `/search` | signed in |
| `SeatMapPage.tsx` | `/trips/:id/seats` | signed in |
| `PaymentPage.tsx` | `/bookings/:id/pay` | signed in (owner) |
| `TicketPage.tsx` | `/bookings/:id/ticket` | signed in (owner) |
| `MyBookingsPage.tsx` | `/bookings` | signed in |
| `AccountPage.tsx` | `/account` (and `/profile`) | signed in |
| `Admin.tsx` | `/admin` | ADMIN |
| `Stations.tsx`, `Trains.tsx`, `TrainDetail.tsx`, `RouteList.tsx`, `RouteFares.tsx`, `Trips.tsx` | `/admin/stations`, `/admin/trains`, `/admin/trains/:id`, `/admin/routes`, `/admin/routes/:id/fares`, `/admin/trips` | ADMIN |
| `SupportPage.tsx`, `TripsBoard.tsx` | `/admin/support` | ADMIN |
| `NotFound.tsx` | everything else | anyone |

### Styles (`src/styles/`)
| File | Content |
|---|---|
| `tokens.css` | The design tokens (colours, type, radii, depth, layout, motion) as CSS variables. Everything else reads these. |
| `base.css` | Reset, typography, focus ring, `.container`, `.sr-only`, skeleton, reduced-motion rule. |
| `components.css` | Buttons, form fields, chips, status chips, cards, notices, tables, empty states. |
| `shell.css`, `nav.css`, `feedback.css` | Header/footer, menu and banners, toasts and error panels. |
| `pages/*.css` | One file per area: landing, search, search-extras, seatmap, booking (steps, hold, payment, ticket), bookings, account (and help pages), admin, board. |

## Routing and guards
`App.tsx` builds the table. Three levels, each inside the previous one:
1. **Public** (inside `Layout`): `/`, the five information pages and `*` (404).
2. **`RequireAuth`**: if Keycloak has no session, it calls `login(window.location.href)`. The user returns to the page they asked for after signing in.
3. **`RequireAdmin`**: needs the realm role `ADMIN` in the token, otherwise redirects to `/account` with a notice. Admin pages are drawn inside `AdminLayout`.

The guards are a convenience. The **Gateway verifies the token and the role again on every request** (`403` for non-admins), so hiding a page here is not security.

## Keycloak and tokens
- Login, registration and logout are **redirects to Keycloak's own pages** (authorization code flow with PKCE, client `web-app`). The app has no password code.
- `initKeycloak()` runs once (`check-sso` with `silent-check-sso.html`), so a reload keeps the user signed in. It is shared as a promise because React StrictMode runs effects twice.
- Access tokens live about 5 minutes. `AuthProvider` refreshes in the background (checks every 20 s, renews when under 60 s are left). `getFreshToken()` also
  refreshes (if under 30 s are left) **right before every Gateway call**, so a request never carries an expired token.
- If the refresh fails the session is over: `notifySessionEnded()` makes `SessionEnded` show a dialog with a **Sign in** button that comes back to the same page.
- The user object (`sub`, name, email, roles) is read from the token for display only.

## The fetch helper
`gatewayRequest(method, path, { body, withToken })` adds `Authorization: Bearer <fresh token>`, sends JSON, reads the text and tries `JSON.parse`. It **never throws on HTTP errors**:
it returns `{ status, body }`, and `status: 0` means the Gateway could not be reached. `readError(result)` reads the shared error shape
`{ error: { code, message, details? } }` into `{ code, message, details[] }`. Pages branch on `code` (for example `seats_unavailable`, `hold_expired`, `trip_departed`)
and show `message`. `gatewayDownload(path)` fetches binary answers (the ticket PDF and QR) with the token, because an `<img>` or a plain link cannot send a header.

## Endpoints the app calls (all through the Gateway)
| Area | Calls | Used by |
|---|---|---|
| Search | `GET /stations`, `GET /trips?from=&to=&date=` (codes) | Landing, SearchTrips |
| Seat map | `GET /trips/{id}/seat-map?from=&to=` (stop indexes) | SeatMapPage, AdminSeatPicker |
| Hold | `POST /bookings/pick`, `POST /bookings/{id}/release`, `DELETE /bookings/{id}/hold` | SeatMapPage, AdminSeatPicker, BoardDetailPanel |
| Payment | `GET /bookings/{id}`, `POST /bookings/{id}/payment` | PaymentPage |
| Bookings | `GET /bookings`, `POST /bookings/{id}/cancel`, `POST /bookings/{id}/resend-ticket` | MyBookingsPage, HoldBanner, TicketPage |
| Ticket | `GET /bookings/{id}/ticket` (PDF), `GET /bookings/{id}/ticket/qr` | TicketPage, TicketQr, BoardDetailPanel |
| Admin data | `/admin/stations`, `/admin/trains`, `/admin/trains/{id}/coaches`, `/admin/coaches/{id}`, `/admin/coaches/{id}/seats`, `/admin/seats/{id}`, `/admin/routes`, `/admin/routes/{id}/fares`, `/admin/trips` (GET, POST, PUT, DELETE as the page needs) | admin pages |
| Admin support | `GET /admin/bookings?...`, `GET /admin/bookings/{id}`, `POST /admin/bookings/{id}/cancel`, `POST /admin/bookings/{id}/issue`, `POST /admin/bookings/{id}/refunded-at-counter`, `GET /admin/passengers/lookup?q=` | SupportPage, BookingAdminPanel, IssueBookingForm |
| Admin board | `GET /admin/board/trips`, `GET /admin/board/trips/{id}`, `POST /admin/trips/{id}/cancel`, `GET /admin/trips/{id}/cancellation` | TripsBoard, BoardDetailPanel, TripCancellation |
| Misc | `GET /admin/ping` | Admin overview |
| WebSocket | `ws://<gateway>/ws` (STOMP), topics `/topic/trips/{id}` and `/user/queue/holds` | SeatMapPage, PaymentPage, AdminSeatPicker |

## Important flows, step by step
### A. Seat map and the 5-minute hold (`SeatMapPage.tsx`)
1. The address carries `from` and `to` as **stop indexes**. `load()` calls `GET /trips/{id}/seat-map`. The Gateway composes it from Train & Schedule (layout) and Booking & Seat (availability); the answer includes `myHold`, the user's own hold, and a `version` counter.
2. Clicking a free seat calls `POST /bookings/pick`. The first pick creates the hold (`expiresAt` = now + 5 min). Later picks join it and **never extend it**. `409 seats_unavailable` means only that pick failed; the seats already held stay held.
3. Clicking your own seat calls `POST /bookings/{id}/release`. Releasing the last item ends the hold on the server.
4. The countdown (`countdown.ts`) is `expiresAt` minus the **server's** clock, corrected by the skew measured when the answer arrived. At 0 the page waits 1.5 s and reloads, because the server's 1-second sweeper has released the hold by then.
5. `connectLive` subscribes to the trip topic. `applySeatEvent` checks the event's `version`: older is dropped, a gap means a missed event and the whole map is reloaded, a release that does not cover the whole journey also reloads (another hold may remain on part of it). After a reconnect the map is always reloaded.
6. "Continue to payment" navigates to `/bookings/{id}/pay`.

### B. Payment (`PaymentPage.tsx`, `StripeCheckout.tsx`)
1. `GET /bookings/{id}` loads the hold; a poll repeats it every 2 s and a WebSocket `HOLD_EXPIRED` makes it immediate.
2. Once, `POST /bookings/{id}/payment` makes the server check the hold and obtain a Stripe PaymentIntent. The answer has the `clientSecret` and the **publishable** key.
3. Stripe's Payment Element draws the card form in its own iframe. Card data goes straight to Stripe and never touches our code or servers.
4. `confirmPayment(..., redirect: 'if_required')`. A decline shows a message and keeps the hold, so the user can try again.
5. Stripe's browser answer is **not trusted**. The page shows "waiting for confirmation" until the poll sees `CONFIRMED` (set by the server after Stripe's signed webhook), then replaces itself with the ticket page.
6. If the booking becomes `EXPIRED` or `ABANDONED`, the page goes back to the seat map with a message ("Your 5 minutes ran out...", or "This trip was cancelled.").

### C. Cancelling (`MyBookingsPage.tsx`)
The list comes from `GET /bookings` (each entry has `cancellable`, computed by the server). The user presses **Cancel booking**, a confirmation text explains the full refund, and `POST /bookings/{id}/cancel` runs. The server re-checks the cutoff (the train has not left the boarding stop) and answers `409 trip_departed` otherwise. After any action the list is reloaded.

### D. Admin trips board (`TripsBoard.tsx`, `useBoardData.ts`)
`useBoardData` polls `GET /admin/board/trips` every 5 s (not while the tab is hidden, back-off up to 60 s on errors, old data stays on screen). Rows whose numbers changed flash. A row opens `BoardDetailPanel`, where the admin can select seats (`AdminSeatPicker`), issue a counter or complimentary booking (`IssueBookingForm`), open a taken seat's booking (`BookingAdminPanel`) or cancel the whole trip.

## Accessibility and motion
- Every control is a real `button`, `a` or form field with a label. State is never shown by colour alone: seats have shape marks and text labels, chips use `aria-pressed`, errors have text.
- `RouteEffects` moves focus to the page heading and announces the title on each navigation. `SlideOver` traps focus and restores it. `UserMenu` follows the menu keyboard pattern.
- Animations are switched off by one rule in `base.css` when the user asks for reduced motion.

## Configuration
Vite reads these at build time (see `src/config.ts`; defaults in brackets are for local development):

| Variable | Meaning |
|---|---|
| `VITE_KEYCLOAK_URL` | Address of Keycloak as the browser sees it (`http://localhost:8180`). |
| `VITE_KEYCLOAK_REALM` | Realm name (`train-booking`). |
| `VITE_KEYCLOAK_CLIENT_ID` | Public client (`web-app`). No client secret exists. |
| `VITE_GATEWAY_URL` | Address of the Gateway (`http://localhost:8080`); the WebSocket address is derived from it. |
| `VITE_SUPPORT_EMAIL` | Shown on the Contact page. |

## Known limits
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
