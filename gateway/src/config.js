// Configuration comes from environment variables (loaded from .env by `node --env-file-if-exists`).
// Every value has a development default, so `npm start` works on a fresh checkout.

const keycloakIssuer = (process.env.KEYCLOAK_ISSUER ?? 'http://localhost:8180/realms/train-booking').replace(/\/+$/, '');

export const config = {
  port: Number(process.env.PORT ?? 8080),
  host: process.env.HOST ?? '127.0.0.1',
  // Must match the `iss` claim of the tokens exactly. Keycloak builds it from KC_HOSTNAME.
  keycloakIssuer,
  // The keys are fetched from here. In Docker the Gateway reaches Keycloak by its service name, while the issuer in the tokens stays the
  // address of the browser, so the URI can be set on its own.
  jwksUri: process.env.KEYCLOAK_JWKS_URI ?? `${keycloakIssuer}/protocol/openid-connect/certs`,
  corsOrigin: process.env.CORS_ORIGIN ?? 'http://localhost:5173',
  // Backend services the Gateway forwards to.
  trainScheduleUrl: process.env.TRAIN_SCHEDULE_URL ?? 'http://localhost:8081',
  bookingSeatUrl: process.env.BOOKING_SEAT_URL ?? 'http://localhost:8082',
  paymentUrl: process.env.PAYMENT_URL ?? 'http://localhost:8083',
  // OpenObserve: structured logs are shipped here when OPENOBSERVE_URL is set (for example http://openobserve:5080/api/default).
  openobserve: {
    url: (process.env.OPENOBSERVE_URL ?? '').replace(/\/+$/, ''),
    user: process.env.OPENOBSERVE_USER ?? '',
    password: process.env.OPENOBSERVE_PASSWORD ?? '',
  },
  upstreamTimeoutMs: Number(process.env.UPSTREAM_TIMEOUT_MS ?? 10_000),
  // The availability of each trip in the search results: if Booking & Seat is slower than this, the trip is shown without it.
  availabilityTimeoutMs: Number(process.env.AVAILABILITY_TIMEOUT_MS ?? 3000),
};

if (!Number.isInteger(config.port) || config.port < 1 || config.port > 65535) {
  throw new Error(`Invalid PORT: ${process.env.PORT}`);
}
