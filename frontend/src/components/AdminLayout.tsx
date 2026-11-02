import { NavLink, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

const SECTIONS = [
  { to: '/admin', label: 'Overview', end: true },
  { to: '/admin/stations', label: 'Stations', end: false },
  { to: '/admin/trains', label: 'Trains', end: false },
  { to: '/admin/routes', label: 'Routes and fares', end: false },
  { to: '/admin/trips', label: 'Trips', end: false },
  { to: '/admin/support', label: 'Support', end: false },
];

/** Every admin page: a dark sidebar on the left (a scrolling bar on phones), a thin top line, and the page. */
export function AdminLayout() {
  const { user } = useAuth();
  const { pathname } = useLocation();
  const section = SECTIONS.filter((s) => !s.end && pathname.startsWith(s.to)).at(0)?.label ?? 'Overview';

  return (
    <div className="admin-shell">
      <aside className="admin-side on-dark">
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
          <p>Admin / {section}</p>
          <span>{user?.name ?? user?.email} · Admin</span>
        </div>
        <div className="admin-content">
          <Outlet />
        </div>
      </div>
    </div>
  );
}
