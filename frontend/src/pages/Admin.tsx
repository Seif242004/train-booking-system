import { useEffect, useState } from 'react';
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
      <p>Train management and support tools will live here. Gateway check for the admin role:</p>
      <GatewayResultView result={result} />
    </section>
  );
}
