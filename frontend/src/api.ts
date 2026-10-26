import { getFreshToken } from './auth/keycloak';
import { config } from './config';

export interface GatewayResult {
  /** HTTP status, or 0 when the Gateway could not be reached at all. */
  status: number;
  body: unknown;
}

interface RequestOptions {
  /** JSON body for POST and PUT. */
  body?: unknown;
  /** `false` sends the request without the Authorization header (used to show the 401 response). */
  withToken?: boolean;
}

/**
 * Calls the API Gateway. The browser only ever talks to the Gateway for application data.
 * A network failure becomes status 0 with an error body instead of an exception.
 */
export async function gatewayRequest(method: string, path: string, { body, withToken = true }: RequestOptions = {}): Promise<GatewayResult> {
  const headers: Record<string, string> = {};
  if (withToken) headers.Authorization = `Bearer ${await getFreshToken()}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  try {
    const response = await fetch(`${config.gatewayUrl}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await response.text();
    let parsed: unknown = text === '' ? null : text;
    try {
      if (text !== '') parsed = JSON.parse(text);
    } catch {
      // Not JSON: keep the raw text.
    }
    return { status: response.status, body: parsed };
  } catch {
    return { status: 0, body: { error: { code: 'gateway_unreachable', message: `Cannot reach the Gateway at ${config.gatewayUrl}.` } } };
  }
}

export function gatewayGet(path: string, options: Omit<RequestOptions, 'body'> = {}): Promise<GatewayResult> {
  return gatewayRequest('GET', path, options);
}

export interface ApiErrorDetail {
  field: string;
  message: string;
}

/** Reads the shared error shape { error: { code, message, details? } } from a failed result. */
export function readError(result: GatewayResult): { code: string; message: string; details: ApiErrorDetail[] } {
  const error = (result.body as { error?: { code?: string; message?: string; details?: ApiErrorDetail[] } } | null)?.error;
  return {
    code: error?.code ?? 'unknown_error',
    message: error?.message ?? `The request failed (HTTP ${result.status}).`,
    details: error?.details ?? [],
  };
}
