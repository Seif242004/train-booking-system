// Values come from Vite env variables (VITE_*), with development defaults. None of these are secrets:
// everything in a browser bundle is public. The client is a public client that uses PKCE, so there is no client secret.
export const config = {
  keycloakUrl: import.meta.env.VITE_KEYCLOAK_URL ?? 'http://localhost:8180',
  keycloakRealm: import.meta.env.VITE_KEYCLOAK_REALM ?? 'train-booking',
  keycloakClientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID ?? 'web-app',
  gatewayUrl: import.meta.env.VITE_GATEWAY_URL ?? 'http://localhost:8080',
};
