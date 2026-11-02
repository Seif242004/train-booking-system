import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { BrandLogo } from './BrandLogo';

const SECTIONS = [
  { to: '/admin', label: 'Overview', end: true },
  { to: '/admin/stations', label: 'Stations', end: false },
  { to: '/admin/trains', label: 'Trains', end: false },
  { to: '/admin/routes', label: 'Routes and fares', end: false },
  { to: '/admin/trips', label: 'Trips', end: false },
  { to: '/admin/support', label: 'Support', end: false },
];

/**
 * Every admin page: a dark sidebar on the left (a scrolling bar on phones) with the logo home and a way back to the site, a breadcrumb
 * line on top, and the page.
 */
export function AdminLayout() {
  const { user } = useAuth();
  const { pathname } = useLocation();
  const section = SECTIONS.find((s) => !s.end && pathname.startsWith(s.to));
  // Admin / Trains / Details: a page below a section's list (one train, the fares of one route).
  const deeper = section && pathname !== section.to;

  return (
    <div className="admin-shell">
      <aside className="admin-side on-dark">
        <BrandLogo height={26} />
        <Link className="admin-back-site" to="/">
          ← Back to site
        </Link>
        <p className="admin-side-title">Admin</p>
        <nav aria-label="Admin" className="admin-nav">
          {SECTIONS.map((s) => (
            <NavLink key={s.to} to={s.to} end={s.end}>
              {s.label}
            </NavLink>
          ))}
        </nav>
      </aside>
      <div className="admin-main">
        <div className="admin-topbar">
          <nav aria-label="Breadcrumb">
            <ol className="breadcrumb">
              <li>{section ? <Link to="/admin">Admin</Link> : <span aria-current="page">Admin</span>}</li>
              {section && (
                <li>{deeper ? <Link to={section.to}>{section.label}</Link> : <span aria-current="page">{section.label}</span>}</li>
              )}
              {deeper && (
                <li>
                  <span aria-current="page">{pathname.endsWith('/fares') ? 'Fares' : 'Details'}</span>
                </li>
              )}
            </ol>
          </nav>
          <span>{user?.name ?? user?.email} · Admin</span>
        </div>
        <div className="admin-content">
          <Outlet />
        </div>
      </div>
    </div>
  );
}
