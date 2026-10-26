# Frontend

React web app (Vite, TypeScript, React Router, plain CSS, no UI library). This is the first slice: sign-in with
Keycloak, a profile page, an admin-only area and a live call through the API Gateway. There are no custom login,
registration or account screens: Login and Register send the browser to Keycloak's own pages.

## What it does

- **Login / Register:** two buttons in the middle of the home page (shown while logged out), each a full-page
  redirect to Keycloak (authorization code flow with PKCE, `keycloak-js`). **Logout** is in the header once signed
  in; it ends the Keycloak session and returns to the home page.
- **Profile** (`/profile`): name, email and roles read from the access token, plus the live response of
  `GET /me` through the Gateway. Buttons also try `GET /admin/ping` (403 for a normal user) and `GET /me` without a
  token (401).
- **Admin** (`/admin`): reachable only with the `ADMIN` role; calls `GET /admin/ping` and links to the admin pages.
- **Stations** (`/admin/stations`): list, add, edit and delete stations through the Gateway. Duplicate codes and
  invalid input show their message next to the field; delete asks for confirmation first.
- **Trains** (`/admin/trains`): list with filters (number, name, status), add, edit and delete; deleting a train also
  deletes its coaches and seats, and the confirmation says so.
- **Routes** (`/admin/routes`): list (name and stops as `CAI → TNT → ALX`), add, edit and delete. The stop editor picks
  stations one at a time, with Up, Down and Remove to set the order. Changing the stops of a route that has fares asks
  for confirmation first, because the fares are removed.
- **Fares** (`/admin/routes/:id/fares`): a grid of segments by class with one EGP box per cell and the total for the
  whole route per class. Saving replaces the route's fares; an empty box means no fare; errors show on the box.
- **Train page** (`/admin/trains/:id`): the train's coaches in train order, with add, edit and delete, standing
  capacity, and a **Seats** panel per coach: a seat map, a layout generator (start row, number of rows, column
  letters, saved all at once or not at all) and edit or delete of a single seat. A seat's class is always its coach's
  class and is shown on the panel; it changes when the coach's class changes.
- **Route guards:** logged-out visitors are redirected to Keycloak's login page; signed-in users without `ADMIN`
  are sent back to their profile. The guards only shape the UI: the Gateway enforces the token and the role.
- **Token refresh:** `updateToken` runs before every Gateway call and in the background (every 20 s, renewing when
  less than 60 s are left). If the session can no longer be renewed, the user is sent to sign in again.

## Environment variables

All optional, with development defaults. See `.env.example`. They are public: nothing secret belongs in a browser app.

| Variable | Default |
|---|---|
| `VITE_KEYCLOAK_URL` | `http://localhost:8180` |
| `VITE_KEYCLOAK_REALM` | `train-booking` |
| `VITE_KEYCLOAK_CLIENT_ID` | `web-app` |
| `VITE_GATEWAY_URL` | `http://localhost:8080` |

## Run

Needs Node.js 22.12 or newer, and Keycloak and the Gateway running (see the repository's `docker-compose.yml` and
`gateway/README.md`).

```
cd frontend
npm install
npm run dev
```

Open http://localhost:5173. The port is fixed: Keycloak's `web-app` client and the Gateway's CORS rule both name it.

`npm run build` type-checks and builds to `dist/`.

Dev accounts (passwords are in the repository's local `.env`): `dev.user@example.com` (USER) and
`dev.admin@example.com` (ADMIN).
