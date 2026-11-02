// Token verification for the Gateway. The Gateway never sees a password and never talks to Keycloak on
// behalf of the user: it only checks that a token was really issued by our Keycloak realm.
import { createRemoteJWKSet, errors, jwtVerify } from 'jose';
import { config } from './config.js';
import { HttpError } from './errors.js';
import { log } from './log.js';

// Keycloak's public keys (JWKS). createRemoteJWKSet downloads them lazily on the first token and caches them.
// - cacheMaxAge: keys are re-downloaded at most every 10 minutes, so most requests need no call to Keycloak.
// - cooldownDuration: when a token names a key id (kid) we do not know, for example after Keycloak rotated its
//   keys, the set is refreshed once, but not more often than every 30 s. This stops a stream of forged tokens
//   from turning into a stream of requests to Keycloak.
// - timeoutDuration: a hung Keycloak must not hang the Gateway.
const jwks = createRemoteJWKSet(new URL(config.jwksUri), {
  cacheMaxAge: 10 * 60 * 1000,
  cooldownDuration: 30 * 1000,
  timeoutDuration: 5 * 1000,
});

// Errors that mean "this token is not acceptable" (401). Anything else coming out of jwtVerify is a problem
// with reaching or reading Keycloak's key set, which is not the caller's fault (503).
const BAD_TOKEN_ERRORS = [
  errors.JWTExpired,
  errors.JWTClaimValidationFailed, // wrong issuer, or a malformed claim
  errors.JWSSignatureVerificationFailed,
  errors.JWSInvalid,
  errors.JWTInvalid,
  errors.JWKSNoMatchingKey, // signed with a key Keycloak does not publish
  errors.JWKSMultipleMatchingKeys,
  errors.JOSEAlgNotAllowed,
];
const KEY_SET_UNAVAILABLE_ERRORS = [
  errors.JWKSTimeout,
  errors.JWKSInvalid,
  TypeError, // what fetch throws when the connection fails ("fetch failed")
];

const bearerChallenge = (detail = '') => ({ 'WWW-Authenticate': `Bearer realm="train-booking"${detail}` });

/**
 * Verifies a bearer token and returns the caller as { sub, name, email, roles }.
 * Throws HttpError(401) for a missing or invalid token and HttpError(503) when Keycloak's keys cannot be fetched.
 */
export async function authenticate(authorizationHeader) {
  const match = /^Bearer (\S+)$/i.exec(authorizationHeader ?? '');
  if (!match) {
    throw new HttpError(401, 'unauthenticated', 'A bearer token is required.', bearerChallenge());
  }

  let payload;
  try {
    ({ payload } = await jwtVerify(match[1], jwks, {
      // Signature: checked against the key whose id (kid) is in the token header, taken from Keycloak's JWKS.
      // Only RS256 is accepted, which is what Keycloak uses by default. This also blocks the classic
      // "alg: none" and HMAC-with-the-public-key downgrade tricks.
      algorithms: ['RS256'],
      // Issuer: the token must come from our realm, not from another realm or another Keycloak.
      issuer: config.keycloakIssuer,
      // Expiry: jwtVerify rejects a token whose `exp` has passed (and one whose `nbf` is in the future).
      // clockTolerance allows a few seconds of clock difference between Keycloak and this machine.
      clockTolerance: 5,
      // No `audience` option on purpose. Keycloak puts `aud: "account"` in these access tokens, which names
      // Keycloak's own account client, not the Gateway. The Gateway is not a Keycloak client and has no audience
      // of its own, so requiring one would reject every valid token. Authorization is based on the realm roles.
    }));
  } catch (err) {
    if (BAD_TOKEN_ERRORS.some((type) => err instanceof type)) {
      const reason = err instanceof errors.JWTExpired ? 'The token has expired.' : 'The token is not valid.';
      throw new HttpError(401, 'invalid_token', reason, bearerChallenge(', error="invalid_token"'));
    }
    if (KEY_SET_UNAVAILABLE_ERRORS.some((type) => err instanceof type)) {
      log('error', 'Cannot fetch the Keycloak signing keys', { cause: String(err) });
      throw new HttpError(503, 'auth_unavailable', 'The authentication service is unavailable. Try again shortly.');
    }
    throw err; // unexpected: let the error handler answer 500
  }

  // Roles come only from realm_access.roles (Keycloak realm roles, for example USER and ADMIN).
  const roles = Array.isArray(payload.realm_access?.roles)
    ? payload.realm_access.roles.filter((role) => typeof role === 'string')
    : [];

  return {
    sub: payload.sub, // the user id; other services store it as a plain value
    name: payload.name ?? payload.preferred_username ?? null,
    email: payload.email ?? null,
    roles,
  };
}

/** Express middleware: any valid token. Puts the caller on req.user. */
export async function requireAuth(req, _res, next) {
  req.user = await authenticate(req.get('authorization'));
  next();
}

/** Express middleware factory: the caller must have the given realm role, otherwise 403. Use after requireAuth. */
export function requireRole(role) {
  return (req, _res, next) => {
    if (!req.user.roles.includes(role)) {
      throw new HttpError(403, 'forbidden', `The ${role} role is required.`);
    }
    next();
  };
}
