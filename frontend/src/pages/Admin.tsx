import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { gatewayGet, type GatewayResult } from '../api';
import { GatewayResultView } from '../components/GatewayResultView';

// Placeholder for the admin area (Flow 7). For now it only proves that the Gateway accepts this admin.
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
      <h1>Admin</h1>
      <p>Train management and support tools.</p>
      <ul className="link-list">
        <li>
          <Link to="/admin/stations">Stations</Link>
        </li>
        <li>
          <Link to="/admin/trains">Trains, coaches and seats</Link>
        </li>
        <li>
          <Link to="/admin/routes">Routes and fares</Link>
        </li>
        <li>
          <Link to="/admin/trips">Trips</Link>
        </li>
        <li>
          <Link to="/admin/support">Support: find and cancel bookings</Link>
        </li>
      </ul>
      <h2>Gateway check for the admin role</h2>
      <GatewayResultView result={result} />
    </section>
  );
}
