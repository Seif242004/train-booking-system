import { useEffect } from 'react';
import { Navigate, Outlet } from 'react-router-dom';
import { useAuth } from './AuthContext';

// These guards decide what the UI shows. They are a convenience, not security: anyone can edit the JavaScript in
// their browser, so the Gateway re-checks the token and the ADMIN role on every request.

/** Logged-out visitors are sent to Keycloak's login page and come back to the page they asked for. */
export function RequireAuth() {
  const { status, authenticated, login } = useAuth();

  useEffect(() => {
    if (status === 'ready' && !authenticated) login(window.location.href);
  }, [status, authenticated, login]);

  if (status === 'loading') return <p className="muted">Loading…</p>;
  if (!authenticated) return <p className="muted">Redirecting to sign in…</p>;
  return <Outlet />;
}

/** Use inside RequireAuth. Signed-in users without the ADMIN role are sent to their profile. */
export function RequireAdmin() {
  const { hasRole } = useAuth();
  if (!hasRole('ADMIN')) {
    return <Navigate to="/profile" replace state={{ notice: 'The admin area is only for administrators.' }} />;
  }
  return <Outlet />;
}
