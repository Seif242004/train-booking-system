import { Link, NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

export function Layout() {
  const { authenticated, user, hasRole, logout } = useAuth();

  return (
    <>
      <header className="topbar">
        <Link to="/" className="brand">
          Train Booking
        </Link>
        <nav aria-label="Main">
          {authenticated && <NavLink to="/profile">Profile</NavLink>}
          {hasRole('ADMIN') && <NavLink to="/admin">Admin</NavLink>}
        </nav>
        {/* Logged out, the Login and Register buttons are on the home page; the header only shows the session. */}
        {authenticated && (
          <div className="session">
            <span className="who">{user?.name ?? user?.email}</span>
            <button type="button" className="button" onClick={logout}>
              Logout
            </button>
          </div>
        )}
      </header>
      <main>
        <Outlet />
      </main>
    </>
  );
}
