# API Gateway

Single entry point of the system (Express, Node.js). It verifies the Keycloak access token on every request and
enforces roles. It does not handle logins or passwords: the browser talks to Keycloak directly (authorization code
flow with PKCE). The Gateway only fetches Keycloak's public keys (JWKS) to check tokens, and it has no Keycloak proxy.

This is the first slice. Routing to the other services, the WebSocket proxy and the Stripe webhook forwarding
come with the later steps.

## Endpoints

| Method | Path | Auth | Response |
|---|---|---|---|
| GET | `/health` | none | `{ "status": "UP" }` |
| GET | `/me` | any valid token | `{ sub, name, email, roles }` |
| GET | `/admin/ping` | role `ADMIN` | `{ "message": "pong", "admin": "<sub>" }` (example admin-only route) |

Errors always use one JSON shape: `{ "error": { "code": "...", "message": "..." } }`.

| Status | `error.code` | When |
|---|---|---|
| 401 | `unauthenticated` | no `Authorization: Bearer <token>` header |
| 401 | `invalid_token` | bad signature, expired, wrong issuer, malformed |
| 403 | `forbidden` | valid token without the required role |
| 404 | `not_found` | unknown route |
| 503 | `auth_unavailable` | Keycloak's keys cannot be fetched (an outage is not a bad token) |
| 500 | `internal_error` | unexpected error |

## How a token is verified

See `src/auth.js` for the commented code.

1. The token must be an `RS256` JWT; its `kid` selects the key from Keycloak's JWKS
   (`<issuer>/protocol/openid-connect/certs`), which is fetched on first use and cached for 10 minutes. An unknown
   `kid` triggers one refresh, at most every 30 s.
2. The signature, `exp` and `iss` are checked (`iss` must equal `KEYCLOAK_ISSUER` exactly).
3. There is no `aud` check: Keycloak sets `aud: "account"`, which is not an audience for the Gateway.
4. Roles are read only from `realm_access.roles`.

CORS allows only `CORS_ORIGIN` (default `http://localhost:5173`).

## Environment variables

All are optional; the defaults are for local development. See `.env.example`.

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | Listen port |
| `HOST` | `127.0.0.1` | Listen address |
| `KEYCLOAK_ISSUER` | `http://localhost:8180/realms/train-booking` | Expected token issuer; the JWKS URL is derived from it |
| `CORS_ORIGIN` | `http://localhost:5173` | The one allowed browser origin |

## Run

Keycloak must be running first (`docker compose up -d` from the repository root). Needs Node.js 22.12 or newer.

```
cd gateway
npm install
npm start
```

Try it: `curl http://localhost:8080/health`. For the Gateway in Docker Compose, see step 12.
