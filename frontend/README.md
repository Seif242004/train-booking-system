# Frontend

React web app (Vite, TypeScript, React Router, plain CSS, no UI library). This is the first slice: sign-in with
Keycloak, a profile page, an admin-only area and a live call through the API Gateway. There are no custom login,
registration or account screens: Login and Register send the browser to Keycloak's own pages.

## What it does

- **Login / Register:** full-page redirect to Keycloak (authorization code flow with PKCE, `keycloak-js`).
  **Logout** ends the Keycloak session and returns to the home page.
- **Profile** (`/profile`): name, email and roles read from the access token, plus the live response of
  `GET /me` through the Gateway. Buttons also try `GET /admin/ping` (403 for a normal user) and `GET /me` without a
  token (401).
- **Admin** (`/admin`): reachable only with the `ADMIN` role; calls `GET /admin/ping`.
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
