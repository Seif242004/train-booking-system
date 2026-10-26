import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

export function Home() {
  const { status, authenticated, user, error, login, register } = useAuth();

  if (authenticated) {
    return (
      <section className="hero">
        <h1>Train Booking</h1>
        <p>
          Signed in as <strong>{user?.name ?? user?.email}</strong>. <Link to="/search">Search trips</Link> or see your <Link to="/profile">profile</Link>.
        </p>
      </section>
    );
  }

  return (
    <section className="hero">
      <h1>Train Booking</h1>
      <p className="muted">Sign in or create an account to book your journey.</p>
      {error && <p className="notice notice-error">{error}</p>}
      {/* Both buttons send the browser to Keycloak's own pages; this app has no login or registration forms. */}
      {status === 'ready' && !error && (
        <div className="actions actions-center">
          <button type="button" className="button button-large button-primary" onClick={() => login()}>
            Login
          </button>
          <button type="button" className="button button-large" onClick={register}>
            Register
          </button>
        </div>
      )}
      {status === 'loading' && <p className="muted">Loading…</p>}
    </section>
  );
}
