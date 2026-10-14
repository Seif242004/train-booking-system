import { getFreshToken } from './auth/keycloak';
import { config } from './config';

export interface GatewayResult {
  /** HTTP status, or 0 when the Gateway could not be reached at all. */
  status: number;
  body: unknown;
}

/**
 * GET a path on the API Gateway. The browser only ever talks to the Gateway for application data.
 * `withToken: false` sends the request without the Authorization header (used to show the 401 response).
 */
export async function gatewayGet(path: string, { withToken = true }: { withToken?: boolean } = {}): Promise<GatewayResult> {
  const headers: Record<string, string> = {};
  if (withToken) headers.Authorization = `Bearer ${await getFreshToken()}`;
  try {
    const response = await fetch(`${config.gatewayUrl}${path}`, { headers });
    const text = await response.text();
    let body: unknown = text;
    try {
      body = JSON.parse(text);
    } catch {
      // Not JSON: keep the raw text.
    }
    return { status: response.status, body };
  } catch {
    return { status: 0, body: { error: { code: 'gateway_unreachable', message: `Cannot reach the Gateway at ${config.gatewayUrl}.` } } };
  }
}
