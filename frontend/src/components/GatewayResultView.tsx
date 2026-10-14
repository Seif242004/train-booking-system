import type { GatewayResult } from '../api';

/** Shows a Gateway response: the HTTP status and the JSON body. */
export function GatewayResultView({ result }: { result: GatewayResult | null }) {
  if (!result) return <p className="muted">Waiting for the Gateway…</p>;
  const kind = result.status >= 200 && result.status < 300 ? 'ok' : 'fail';
  return (
    <div className="result" aria-live="polite">
      <p>
        <span className={`status status-${kind}`}>{result.status === 0 ? 'No response' : `HTTP ${result.status}`}</span>
      </p>
      <pre>{JSON.stringify(result.body, null, 2)}</pre>
    </div>
  );
}
