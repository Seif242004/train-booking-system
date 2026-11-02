# Train Booking: UI design brief

Source of truth for the frontend redesign. The mockups in `mockups/` are visual references (HTML with inline CSS). Copy the look, not the markup: rebuild them as React components with plain CSS. Mockup data (trains, prices, names, seat patterns, admin fields) is sample data only. Real data comes from the existing API.

Brand direction (from the supervisor): free choice of design, but colours and logo follow the My Etisalat rebrand. Used only as the internal skin of this project.

## Assets (`assets/`)
- `hero.jpg`: 1680x944 photo (a train beside palm trees on the Nile at sunset). AI-generated placeholder; a licensed photo may replace it later. Reference it from ONE place so it is swappable.
- `logo-etisalat-white.png`: 272x64 white wordmark cropped from a screenshot. Placeholder until the official SVG arrives. Reference it from ONE component (`BrandLogo`) so the swap is a one-file change.
- Destination in the frontend: `frontend/public/brand/` (served as `/brand/hero.jpg`, `/brand/logo-etisalat-white.png`).

## Colour tokens (CSS custom properties in `src/styles/tokens.css`)
| Token | Value | Use |
|---|---|---|
| `--red` | `#e10801` | brand, primary buttons, selected states, header bar |
| `--red-hover` | `#b80600` | primary button hover |
| `--red-text` | `#c20700` | links and small red text on white (contrast) |
| `--ink` | `#191329` | main text, dark sections, footer |
| `--ink-2` | `#3b3450` | secondary text, labels |
| `--muted` | `#4a4360` | tertiary text (min 4.5:1 on white and on paper) |
| `--paper` | `#f4f3f5` | page background |
| `--white` | `#ffffff` | cards, inputs |
| `--line` | `#dcd8e2` | input and chip borders |
| `--line-soft` | `#eeebf2` | dividers inside cards |
| `--ok-bg` / `--ok-text` | `#d9f3e3` / `#006b2b` | confirmed, available seats count |
| `--warn-bg` / `--warn-text` | `#fff4dc` / `#7a4b00` | refund pending, test mode |
| `--danger-tint` / `--danger-border` / `--danger-text` | `#fff4f3` / `#f3c9c7` / `#8c0500` | cancel confirm, errors |
| `--timer-ok` / `--timer-low` | `#00b64c` / `#ff6b64` | hold countdown bar (low under 60 s) |
| `--disabled` | `#6d6680` | disabled button background, taken seat text |
Rules: red is for the brand header, the main action on each screen, and selection. Do not add other accent colours. No gradient decoration; the only gradients are the photo scrims and the loading shimmer.

## Type
Plus Jakarta Sans 400/500/600/700/800 (Google Fonts `<link>` in `index.html`; do NOT add an npm font package). Fallback `system-ui, sans-serif`.
- Display (landing h1): `clamp(40px, 6vw, 68px)`, 800, line-height 1.02, letter-spacing -0.035em
- Page h1: 34-36px, 800, -0.02em. Section h2: `clamp(30px, 4vw, 40px)`, 800, -0.025em. Card h2: 20-26px. h3: 18-21px.
- Body 16px / 1.6. Secondary 14-15px. Smallest 13px (never below 12px).
- Sentence case everywhere. No all-caps labels. Numbers that change (countdown, prices) use `font-variant-numeric: tabular-nums`.

## Shape, spacing, depth
- Radius: inputs and buttons 14px, cards 20px, hero search card 24px, pills 999px, seats 12px.
- Spacing on a 4/8 scale. Page container max-width 1120px, side padding 24px (16px on phones if needed).
- Touch targets at least 44x44 px. Inputs 52-54px high. Primary buttons 54-56px high.
- Shadows: resting `0 2px 8px rgba(25,19,41,.05)`, raised `0 18px 48px rgba(25,19,41,.14)`, card hover `0 14px 36px rgba(25,19,41,.12)`.

## Motion (CSS only, no animation library)
Allowed properties: `transform`, `opacity`, `background-color`, `border-color`, `box-shadow`, `stroke-dashoffset`.
- Hover and press: 150 ms ease (buttons scale .98 on press, cards lift 2px).
- Entrances: 300-500 ms, `cubic-bezier(.2,.7,.2,1)`. Landing headline lines rise once on load (0.9 s, staggered). Result cards rise with a 70 ms stagger. Do NOT fade-slide every section on scroll.
- Hero photo: slow camera drift, `transform: scale(1) -> scale(1.12) translate(-2.5%, 1.5%)`, 30 s, alternate, infinite.
- Loading: shimmer skeletons (1.3 s) for trip lists and My bookings; none for waits under ~300 ms.
- Ticket confirmation: ring and tick draw (stroke-dashoffset), then the ticket rises.
- Seat hold bar: width transition 1 s linear; turns `--timer-low` under 60 s.
- Everything above must switch to no animation or an instant state under `@media (prefers-reduced-motion: reduce)`.

## Screens: mockup to app page
| Mockup | App page | Notes |
|---|---|---|
| `Main.dc.html` | new landing page at `/` | hero photo + search card; steps; "what you get" dark section; footer. Search card submits to the existing search flow. |
| `Results.dc.html` | existing search results page | compact summary bar with day chips and "Change search"; skeleton while loading; trip cards with stops line and fare chips; "last train today" suggestion card. |
| `SeatMap.dc.html` | existing seat map page | class tabs, coach chips, 2+2 / 2+1 plan, standing stepper, hold card with countdown (starts at the FIRST pick, never extended), selection list, "Continue to payment". |
| `Payment.dc.html` | existing payment page | Stripe Payment Element inside the styled card; test-mode badge; order summary; hold timer. The mockup's card fields are only a stand-in for the Stripe element. |
| `Ticket.dc.html` | existing confirmation / ticket view | animated check, boarding-pass style ticket, QR from the real ticket data, download PDF. |
| `MyBookings.dc.html` | existing My bookings page | Upcoming / Past and cancelled tabs, status chips, inline cancel confirmation with full-refund text, refund status after cancelling. |
| `Admin.dc.html` | all admin pages (stations, trains, trips, support search, trip cancellation) | dark sidebar + list table + slide-over edit panel. Use the REAL fields of each existing form, not the mockup's. |
| `Login.dc.html`, `Register.dc.html` | Keycloak theme (see below) | split layout: photo panel left, form right. |

Header: red bar, white wordmark left, nav right (Search trains, My bookings, Sign in / user name). Footer: dark, wordmark + links + "Card payments are processed by Stripe."

## Keycloak login theme
- Theme name `train-booking`, login theme only, CSS-first. Folder `infra/keycloak/themes/train-booking/login/` (`theme.properties`, `resources/css/`, `resources/img/`). Mount `./infra/keycloak/themes` to `/opt/keycloak/themes` in Docker Compose and set `"loginTheme": "train-booking"` in the realm import.
- Use the built-in `keycloak.v2` theme as parent and restyle with CSS. Add a minimal template override only if the split layout cannot be done with CSS. Do NOT add Keycloakify or any new library.
- Cover: login, registration, forgot password, error and info pages, required-action pages. Fields keep Keycloak's own names and validation messages.
- The photo panel uses `/resources/.../img/hero.jpg` (copy of `assets/hero.jpg`) with the same drift and scrim as the landing page.

## Copy rules
Plain words, sentence case, buttons start with a verb ("Search trains", "Continue to payment", "Cancel booking and refund"). Same action keeps the same name through the flow. Errors say what happened and how to fix it; they never blame the user. Empty states point to the next action.

## Accessibility and quality floor
Real `<button>` and `<a>` elements, visible focus ring (3px, `--ink`, white on dark areas), labelled inputs, `aria-pressed` on toggle chips and seats, `aria-live` for the result count and standing counter, `role="timer"` with `aria-live="off"` for the countdown, text contrast 4.5:1 or better, layouts work from 375px to 1440px with no horizontal page scroll (wide tables scroll inside their own box).
