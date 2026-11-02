import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { backFor } from '../routes';
import { BrandLogo } from './BrandLogo';
import { HoldBanner } from './HoldBanner';
import { OfflineBanner } from './OfflineBanner';
import { PageNav } from './PageNav';
import { RouteEffects } from './RouteEffects';
import { SessionEnded } from './SessionEnded';
import { UserMenu } from './UserMenu';

// Pages that draw their own full-width sections and their own container.
const FULL_BLEED = ['/', '/search'];
// The admin area has its own sidebar layout.
const isFullBleed = (path: string) => FULL_BLEED.includes(path) || path === '/admin' || path.startsWith('/admin/');

const FOOTER_LINKS = [
  { to: '/help', label: 'Help and FAQ' },
  { to: '/refunds', label: 'Cancellations and refunds' },
  { to: '/terms', label: 'Terms of use' },
  { to: '/privacy', label: 'Privacy notice' },
  { to: '/contact', label: 'Contact' },
];

export function Layout() {
  const { status, authenticated, hasRole, login, register } = useAuth();
  const { pathname } = useLocation();
  // Every other page sits in the 1120px container.
  const fullBleed = isFullBleed(pathname);
  const back = backFor(pathname);

  return (
    <div className="app">
      <a className="skip-link" href="#main">
        Skip to the content
      </a>
      <RouteEffects />
      <OfflineBanner />
      <header className="site-header on-dark">
        <div className="site-header-inner">
          <BrandLogo height={30} />
          <nav className="site-nav" aria-label="Main">
            <NavLink to="/search">Search trains</NavLink>
            {authenticated && <NavLink to="/bookings">My bookings</NavLink>}
            {hasRole('ADMIN') && <NavLink to="/admin">Admin</NavLink>}
          </nav>
          {/* Sign-in and registration are Keycloak's own pages; this app has no login or registration forms. */}
          <div className="site-account">
            {authenticated ? (
              <UserMenu />
            ) : (
              status === 'ready' && (
                <>
                  <button type="button" className="nav-button" onClick={() => login()}>
                    Sign in
                  </button>
                  <button type="button" className="nav-button nav-pill" onClick={() => register()}>
                    Create account
                  </button>
                </>
              )
            )}
          </div>
        </div>
      </header>
      <HoldBanner />
      <main id="main" className="app-main" tabIndex={-1}>
        {back && (
          <div className="container page-nav-wrap">
            <PageNav back={back} />
          </div>
        )}
        {/* A short rise when the page changes: the wrapper is new for every address. */}
        <div key={pathname} className="route-in">
          {fullBleed ? <Outlet /> : (
            <div className="container">
              <Outlet />
            </div>
          )}
        </div>
      </main>
      <SessionEnded />
      <footer className="site-footer on-dark">
        <div className="site-footer-inner">
          <div className="footer-brand">
            <BrandLogo height={26} home={false} />
            <p>Card payments are processed by Stripe.</p>
          </div>
          <nav aria-label="Help and policies">
            {FOOTER_LINKS.map((l) => (
              <Link key={l.to} to={l.to}>{l.label}</Link>
            ))}
          </nav>
        </div>
      </footer>
    </div>
  );
}
