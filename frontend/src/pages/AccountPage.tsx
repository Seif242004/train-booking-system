import { Link, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { ProfileCard } from '../components/ProfileCard';
import { initialsOf } from '../components/UserMenu';
import { config } from '../config';

/** Where Keycloak's own account console lives. It is only the fallback for what our own screens do not cover (two-factor, linked accounts). */
// referrer and referrer_uri are the console's own way to show a "Back to Train Booking" link in its header: the client id of this app and the
// address to return to (Keycloak accepts it only when it matches a redirect URI of that client).
function accountConsoleUrl(): string {
  const returnTo = new URLSearchParams({ referrer: config.keycloakClientId, referrer_uri: `${window.location.origin}/account` });
  return `${config.keycloakUrl}/realms/${config.keycloakRealm}/account?${returnTo}`;
}

/** The passenger's account: who they are, their details, password and sessions, shortcuts and sign out. Only things a passenger understands. */
// Route /account (signed-in users; /profile redirects here). The details card is our own screen on Keycloak's Account API: ProfileCard (first and
// last name). We write no password code and no sign-in pages. The header card shows data already in the access token (name, email, roles).
// "notice" comes from router state: RequireAdmin redirects a non-admin here with the message "The admin area is only for administrators."
export function AccountPage() {
  const { user, hasRole, logout } = useAuth();
  const notice = (useLocation().state as { notice?: string } | null)?.notice;
  const displayName = user?.name ?? user?.email ?? 'Your account';
  const isAdmin = hasRole('ADMIN');

  return (
    <section className="account-page">
      <h1 className="sr-only">Your account</h1>
      {notice && <p className="notice" role="status">{notice}</p>}

      <div className="account-card">
        <span className="avatar avatar-large" aria-hidden="true">{initialsOf(displayName)}</span>
        <div className="account-who">
          <p className="account-name">{displayName}</p>
          <p className="account-sub">{user?.email ?? 'No email address on file'}</p>
          <p className="account-chips">
            <span className={isAdmin ? 'status-chip is-warn' : 'status-chip is-ok'}>{isAdmin ? 'Admin' : 'Passenger'}</span>
          </p>
        </div>
      </div>

      <ProfileCard />

      <h2 className="account-h2">Shortcuts</h2>
      <ul className="account-shortcuts">
        <li>
          <Link className="shortcut" to="/bookings">
            <strong>My bookings</strong>
            <span>Tickets, running holds and cancellations</span>
          </Link>
        </li>
        <li>
          <Link className="shortcut" to="/search">
            <strong>Search trains</strong>
            <span>Find a trip and pick your seats</span>
          </Link>
        </li>
        {isAdmin && (
          <li>
            <Link className="shortcut" to="/admin">
              <strong>Admin</strong>
              <span>Trains, trips and support tools</span>
            </Link>
          </li>
        )}
      </ul>

      <div className="actions account-actions">
        <button type="button" className="button" onClick={logout}>Sign out</button>
      </div>
      <p className="account-advanced">
        <a href={accountConsoleUrl()}>Advanced security settings</a>
        <span className="muted"> for two-factor sign-in and linked accounts.</span>
      </p>
    </section>
  );
}
