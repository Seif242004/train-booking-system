import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { initKeycloak, keycloak, userFromToken, type AuthUser } from './keycloak';

interface AuthState {
  /** `loading` until Keycloak has answered the first session check. */
  status: 'loading' | 'ready';
  authenticated: boolean;
  user: AuthUser | null;
  error: string | null;
  login: (redirectUri?: string) => void;
  register: () => void;
  logout: () => void;
  hasRole: (role: string) => boolean;
}

const AuthContext = createContext<AuthState | null>(null);

// Refresh in the background when less than this many seconds of the token are left. Access tokens live 5 minutes,
// so this renews them about once every 4 minutes while the tab is open. (getFreshToken also refreshes before each call.)
const REFRESH_WHEN_SECONDS_LEFT = 60;
const REFRESH_CHECK_EVERY_MS = 20_000;

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthState['status']>('loading');
  const [user, setUser] = useState<AuthUser | null>(null);
  const [error, setError] = useState<string | null>(null);

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
          // The refresh token is no longer valid (session ended in Keycloak): sign in again.
          void keycloak.login({ redirectUri: window.location.href });
        });
    }, REFRESH_CHECK_EVERY_MS);
    return () => window.clearInterval(timer);
  }, [signedIn]);

  const value = useMemo<AuthState>(
    () => ({
      status,
      authenticated: user !== null,
      user,
      error,
      login: (redirectUri) => void keycloak.login({ redirectUri: redirectUri ?? `${window.location.origin}/profile` }),
      register: () => void keycloak.register({ redirectUri: `${window.location.origin}/profile` }),
      logout: () => void keycloak.logout({ redirectUri: `${window.location.origin}/` }),
      hasRole: (role) => user?.roles.includes(role) ?? false,
    }),
    [status, user, error],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used inside <AuthProvider>');
  return value;
}
