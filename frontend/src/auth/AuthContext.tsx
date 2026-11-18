import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { initKeycloak, keycloak, notifySessionEnded, onSessionEnded, userFromToken, type AuthUser } from './keycloak';

// The value that useAuth() gives to every component: who is signed in and the actions login, register and logout.
// Keycloak does the real work; this file only mirrors its state into React state so components re-render.
interface AuthState {
  /** `loading` until Keycloak has answered the first session check. */
  status: 'loading' | 'ready';
  authenticated: boolean;
  user: AuthUser | null;
  error: string | null;
  /** The token can no longer be refreshed: the UI asks the user to sign in again. */
  sessionEnded: boolean;
  login: (redirectUri?: string) => void;
  register: () => void;
  logout: () => void;
  hasRole: (role: string) => boolean;
  /** Renews the access token now and re-reads the name and email from it (after the user changed their details). */
  refreshUser: () => Promise<void>;
}

// A React context lets any component read this value without passing props down. null = used outside the provider.
const AuthContext = createContext<AuthState | null>(null);

// Refresh in the background when less than this many seconds of the token are left. Access tokens live 5 minutes,
// so this renews them about once every 4 minutes while the tab is open. (getFreshToken also refreshes before each call.)
// Background refresh timing, see the comment above.
const REFRESH_WHEN_SECONDS_LEFT = 60;
const REFRESH_CHECK_EVERY_MS = 20_000;

/**
 * Holds the session state for the whole app. Two effects run here:
 * (1) once at start: initKeycloak() checks silently for an existing session; `cancelled` stops state updates if the component was
 * unmounted meanwhile (React StrictMode mounts twice in development). (2) while signed in: an interval renews the access token and
 * is cleared when the user signs out or the component unmounts. If renewing fails, the "session ended" panel is shown.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthState['status']>('loading');
  const [user, setUser] = useState<AuthUser | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [sessionEnded, setSessionEnded] = useState(false);

  // Subscribe to the "session ended" signal that keycloak.ts raises when a token refresh fails. The returned function unsubscribes.
  useEffect(() => onSessionEnded(() => setSessionEnded(true)), []);

  useEffect(() => {
    let cancelled = false;
    initKeycloak()
      .then(() => {
        if (cancelled) return;
        setUser(keycloak.authenticated ? userFromToken() : null);
        setStatus('ready');
      })
      .catch(() => {
        if (cancelled) return;
        setError('Cannot reach the sign-in service (Keycloak). Is it running?');
        setStatus('ready');
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // Keep the token fresh while signed in, and pick up the renewed token's claims.
  const signedIn = user !== null;
  useEffect(() => {
    if (!signedIn) return;
    const timer = window.setInterval(() => {
      keycloak
        .updateToken(REFRESH_WHEN_SECONDS_LEFT)
        .then((refreshed) => {
          if (refreshed) setUser(userFromToken());
        })
        .catch(() => {
          // The refresh token is no longer valid (session ended in Keycloak): ask the user to sign in again.
          notifySessionEnded();
        });
    }, REFRESH_CHECK_EVERY_MS);
    return () => window.clearInterval(timer);
  }, [signedIn]);

  // useMemo keeps the same object between renders unless one of the listed values changed, so consumers do not re-render needlessly.
  const value = useMemo<AuthState>(
    () => ({
      status,
      authenticated: user !== null,
      user,
      error,
      sessionEnded,
      // After Keycloak's page the user returns to where they were (or tried to go): the current address, deep links included.
      login: (redirectUri) => void keycloak.login({ redirectUri: redirectUri ?? window.location.href }),
      register: () => void keycloak.register({ redirectUri: window.location.href }),
      logout: () => void keycloak.logout({ redirectUri: `${window.location.origin}/` }),
      hasRole: (role) => user?.roles.includes(role) ?? false,
      // updateToken(-1) renews the token even if it is still valid, so the new claims (name) arrive at once.
      refreshUser: async () => {
        await keycloak.updateToken(-1);
        setUser(userFromToken());
      },
    }),
    [status, user, error, sessionEnded],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

/** The hook components use to read the session. It throws a clear error if the provider is missing. */
export function useAuth(): AuthState {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used inside <AuthProvider>');
  return value;
}
