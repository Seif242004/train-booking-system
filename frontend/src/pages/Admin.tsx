import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { gatewayGet, type GatewayResult } from '../api';
import { AdminHeader } from '../components/AdminHeader';
import { GatewayResultView } from '../components/GatewayResultView';

const SECTIONS = [
  { to: '/admin/stations', title: 'Stations', text: 'The stations trains stop at.' },
  { to: '/admin/trains', title: 'Trains', text: 'Trains, their coaches and seats, and standing places.' },
  { to: '/admin/routes', title: 'Routes and fares', text: 'The stops of each route and the fare of every leg and class.' },
  { to: '/admin/trips', title: 'Trips', text: 'Schedule a trip, change its times, or cancel it.' },
  { to: '/admin/support', title: 'Support', text: 'Find a booking, see who paid, cancel it for a passenger.' },
];

// The overview of the admin area (Flow 7). It also proves that the Gateway accepts this admin (the check at the bottom).
export function Admin() {
  const [result, setResult] = useState<GatewayResult | null>(null);

  useEffect(() => {
    let cancelled = false;
    gatewayGet('/admin/ping')
      .then((r) => !cancelled && setResult(r))
      .catch((e: Error) => !cancelled && setResult({ status: 0, body: { error: { code: 'client_error', message: e.message } } }));
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <section>
      <AdminHeader title="Admin" />
      <p className="muted">Train management and support tools.</p>
      <ul className="admin-cards">
        {SECTIONS.map((section) => (
          <li key={section.to}>
            <Link className="admin-card" to={section.to}>
              <h2>{section.title}</h2>
              <p>{section.text}</p>
              <span className="admin-card-go">Open {section.title.toLowerCase()} →</span>
            </Link>
          </li>
        ))}
      </ul>
      <details className="gateway-check">
        <summary>Gateway check for the admin role</summary>
        <GatewayResultView result={result} />
      </details>
    </section>
  );
}
