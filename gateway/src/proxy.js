// Forwards a request to a backend service and relays the answer. Written with Node's built-in fetch, so it needs
// no extra library. It handles plain request/response traffic (JSON APIs). The WebSocket proxy comes with the
// live-updates step.
import { config } from './config.js';
import { sendError } from './errors.js';

// Only these request headers are passed on. Cookies, the Host header and anything else the client sent stay behind.
// The Authorization header is the caller's Keycloak token: the service validates it again itself.
const FORWARDED_REQUEST_HEADERS = ['authorization', 'content-type', 'accept'];

// Only these response headers are relayed back. Hop-by-hop headers (connection, transfer-encoding, ...) must not be copied.
const RELAYED_RESPONSE_HEADERS = ['content-type', 'location', 'www-authenticate'];

/**
 * Express handler that forwards the request to `baseUrl`, keeping method, path, query string and body.
 * `pathPrefix` is the only part of the backend the route may reach: it stops a crafted URL from escaping
 * to another host or another path.
 * Mount it after the auth middleware and after a body parser that produces a Buffer (express.raw).
 */
export function forwardTo(baseUrl, pathPrefix) {
  const base = new URL(baseUrl);

  return async (req, res) => {
    const target = new URL(req.originalUrl, base);
    if (target.origin !== base.origin || !target.pathname.startsWith(pathPrefix)) {
      return sendError(res, 400, 'bad_request', 'The request path is not valid.');
    }

    const headers = {};
    for (const name of FORWARDED_REQUEST_HEADERS) {
      if (req.headers[name]) headers[name] = req.headers[name];
    }
    const hasBody = !['GET', 'HEAD'].includes(req.method) && Buffer.isBuffer(req.body) && req.body.length > 0;

    try {
      const upstream = await fetch(target, {
        method: req.method,
        headers,
        body: hasBody ? req.body : undefined,
        redirect: 'manual', // a redirect from the service is the service's answer, not something to follow silently
        signal: AbortSignal.timeout(config.upstreamTimeoutMs),
      });
      const payload = Buffer.from(await upstream.arrayBuffer());

      res.status(upstream.status);
      for (const name of RELAYED_RESPONSE_HEADERS) {
        const value = upstream.headers.get(name);
        if (value) res.set(name, value);
      }
      res.send(payload);
    } catch (err) {
      if (err?.name === 'TimeoutError') {
        return sendError(res, 504, 'upstream_timeout', 'The service took too long to answer.');
      }
      // Connection refused, DNS failure, reset: the service is down or unreachable. No request details are logged.
      console.error(JSON.stringify({ level: 'error', msg: 'Service unreachable', target: target.origin, cause: String(err?.cause?.code ?? err) }));
      sendError(res, 502, 'upstream_unavailable', 'The service is unavailable. Try again shortly.');
    }
  };
}
