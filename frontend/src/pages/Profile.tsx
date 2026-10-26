import { useEffect, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { gatewayGet, type GatewayResult } from '../api';
import { useAuth } from '../auth/AuthContext';
import { GatewayResultView } from '../components/GatewayResultView';

type Check = { label: string; path: string; withToken: boolean };

// Buttons for trying the Gateway by hand: a valid call, an admin-only call (403 for a normal user) and a call
// without a token (401).
const CHECKS: Check[] = [
  { label: 'GET /me', path: '/me', withToken: true },
  { label: 'GET /admin/ping', path: '/admin/ping', withToken: true },
  { label: 'GET /admin/stations', path: '/admin/stations', withToken: true },
  { label: 'GET /admin/trains', path: '/admin/trains', withToken: true },
  { label: 'GET /admin/routes', path: '/admin/routes', withToken: true },
  { label: 'GET /admin/trips', path: '/admin/trips', withToken: true },
  { label: 'GET /me without a token', path: '/me', withToken: false },
];

export function Profile() {
  const { user } = useAuth();
  const location = useLocation();
  const notice = (location.state as { notice?: string } | null)?.notice;

  const [shown, setShown] = useState<Check>(CHECKS[0]);
  const [result, setResult] = useState<GatewayResult | null>(null);

  // Runs once on load for GET /me, and again whenever a button picks another check.
  useEffect(() => {
    let cancelled = false;
    setResult(null);
    gatewayGet(shown.path, { withToken: shown.withToken })
      .then((r) => !cancelled && setResult(r))
      .catch((e: Error) => !cancelled && setResult({ status: 0, body: { error: { code: 'client_error', message: e.message } } }));
    return () => {
      cancelled = true;
    };
  }, [shown]);

  return (
    <section>
      <h1>Profile</h1>
      {notice && <p className="notice">{notice}</p>}

      <dl className="facts">
        <dt>Name</dt>
        <dd>{user?.name ?? '—'}</dd>
        <dt>Email</dt>
        <dd>{user?.email ?? '—'}</dd>
        <dt>Roles</dt>
        <dd>
          {user?.roles.length ? (
            <ul className="chips">
              {user.roles.map((role) => (
                <li key={role}>{role}</li>
              ))}
            </ul>
          ) : (
            '—'
          )}
        </dd>
        <dt>User id</dt>
        <dd className="mono">{user?.sub}</dd>
      </dl>

      <h2>Gateway response</h2>
      <div className="actions">
        {CHECKS.map((check) => (
          <button key={check.label} type="button" className={check === shown ? 'button button-selected' : 'button'} onClick={() => setShown(check)}>
            {check.label}
          </button>
        ))}
      </div>
      <p className="muted">
        Showing <code>{shown.label}</code> through the API Gateway.
      </p>
      <GatewayResultView result={result} />
    </section>
  );
}
