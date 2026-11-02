import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { BrandLogo } from './BrandLogo';

export function Layout() {
  const { status, authenticated, user, hasRole, login, logout } = useAuth();
  // The landing page draws its own full-width sections; every other page sits in the 1120px container.
  const fullBleed = useLocation().pathname === '/';

  return (
    <div className="app">
      <a className="skip-link" href="#main">
        Skip to the content
      </a>
      <header className="site-header on-dark">
        <div className="site-header-inner">
          <BrandLogo height={30} />
          <nav className="site-nav" aria-label="Main">
            <NavLink to="/search">Search trains</NavLink>
            <NavLink to="/bookings">My bookings</NavLink>
            {authenticated && <NavLink to="/profile">Profile</NavLink>}
            {hasRole('ADMIN') && <NavLink to="/admin">Admin</NavLink>}
            {/* Sign-in and registration are Keycloak's own pages; this app has no login or registration forms. */}
            {authenticated ? (
              <>
                <span className="who">{user?.name ?? user?.email}</span>
                <button type="button" className="nav-button" onClick={logout}>
                  Logout
                </button>
              </>
            ) : (
              status === 'ready' && (
                <button type="button" className="nav-button nav-pill" onClick={() => login()}>
                  Sign in
                </button>
              )
            )}
          </nav>
        </div>
      </header>
      <main id="main" className="app-main" tabIndex={-1}>
        {fullBleed ? <Outlet /> : (
          <div className="container">
            <Outlet />
          </div>
        )}
      </main>
      <footer className="site-footer on-dark">
        <div className="site-footer-inner">
          <BrandLogo height={26} home={false} />
          <nav aria-label="Footer">
            <Link to="/search">Search trains</Link>
            <Link to="/bookings">My bookings</Link>
            {!authenticated && status === 'ready' && (
              <button type="button" onClick={() => login()}>
                Sign in
              </button>
            )}
          </nav>
          <p>Card payments are processed by Stripe.</p>
        </div>
      </footer>
    </div>
  );
}
