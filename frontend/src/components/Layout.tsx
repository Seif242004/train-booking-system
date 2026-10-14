import { Link, NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

export function Layout() {
  const { status, authenticated, user, hasRole, login, register, logout } = useAuth();

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
        <div className="session">
          {status === 'ready' &&
            (authenticated ? (
              <>
                <span className="who">{user?.name ?? user?.email}</span>
                <button type="button" className="button" onClick={logout}>
                  Logout
                </button>
              </>
            ) : (
              <>
                <button type="button" className="button button-primary" onClick={() => login()}>
                  Login
                </button>
                <button type="button" className="button" onClick={register}>
                  Register
                </button>
              </>
            ))}
        </div>
      </header>
      <main>
        <Outlet />
      </main>
    </>
  );
}
