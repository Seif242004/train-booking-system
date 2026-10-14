import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

export function Home() {
  const { authenticated, user, error } = useAuth();
  return (
    <section>
      <h1>Train Booking</h1>
      {error && <p className="notice notice-error">{error}</p>}
      {authenticated ? (
        <p>
          Signed in as <strong>{user?.name ?? user?.email}</strong>. See your <Link to="/profile">profile</Link>.
        </p>
      ) : (
        <p>Sign in or create an account with the buttons at the top. Both pages are provided by Keycloak.</p>
      )}
    </section>
  );
}
