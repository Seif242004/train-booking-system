// Values come from Vite env variables (VITE_*), with development defaults. None of these are secrets:
// everything in a browser bundle is public. The client is a public client that uses PKCE, so there is no client secret.
export const config = {
  keycloakUrl: import.meta.env.VITE_KEYCLOAK_URL ?? 'http://localhost:8180',
  keycloakRealm: import.meta.env.VITE_KEYCLOAK_REALM ?? 'train-booking',
  keycloakClientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID ?? 'web-app',
  // The address shown on the Contact page. The default is a marked placeholder (.invalid never exists): set the real one in VITE_SUPPORT_EMAIL.
  supportEmail: import.meta.env.VITE_SUPPORT_EMAIL ?? 'support@example.invalid',
  gatewayUrl: import.meta.env.VITE_GATEWAY_URL ?? 'http://localhost:8080',
};
