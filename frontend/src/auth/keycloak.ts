import Keycloak from 'keycloak-js';
import { config } from '../config';

// The one Keycloak adapter instance. Login, registration and logout are full-page redirects to Keycloak's own
// pages (authorization code flow with PKCE). The app never sees a password.
export const keycloak = new Keycloak({
  url: config.keycloakUrl,
  realm: config.keycloakRealm,
  clientId: config.keycloakClientId,
});

export interface AuthUser {
  sub: string;
  name: string | null;
  email: string | null;
  roles: string[];
}

interface AccessTokenClaims {
  sub?: string;
  name?: string;
  preferred_username?: string;
  email?: string;
  realm_access?: { roles?: string[] };
}

/** Reads the signed-in user from the access token. Only for display and UI guards: the Gateway is what enforces access. */
export function userFromToken(): AuthUser | null {
  const claims = keycloak.tokenParsed as AccessTokenClaims | undefined;
  if (!claims?.sub) return null;
  return {
    sub: claims.sub,
    name: claims.name ?? claims.preferred_username ?? null,
    email: claims.email ?? null,
    roles: claims.realm_access?.roles ?? [],
  };
}

// keycloak.init() must run once. React StrictMode runs effects twice in development, so the promise is shared.
let initPromise: Promise<boolean> | null = null;
export function initKeycloak(): Promise<boolean> {
  initPromise ??= keycloak.init({
    // Look for an existing Keycloak session in a hidden iframe, so a page reload keeps the user signed in.
    onLoad: 'check-sso',
    silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`,
    pkceMethod: 'S256',
    checkLoginIframe: false,
  });
  return initPromise;
}

/**
 * Returns a token that is valid for at least 30 more seconds, refreshing it first when needed (updateToken).
 * Call this right before every request to the Gateway. If the session can no longer be refreshed, the user is
 * sent to Keycloak to sign in again.
 */
export async function getFreshToken(): Promise<string> {
  try {
    await keycloak.updateToken(30);
  } catch {
    void keycloak.login({ redirectUri: window.location.href });
    throw new Error('Your session has expired. Redirecting to sign in.');
  }
  if (!keycloak.token) throw new Error('Not signed in.');
  return keycloak.token;
}
