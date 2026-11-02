// The WebSocket proxy (Flow 2, "Live updates"): the browser opens ONE WebSocket to the Gateway, the Gateway checks the Keycloak token
// on the HTTP upgrade and only then connects the socket to Booking & Seat, which pushes the live seat updates.
//
// The token cannot go in an Authorization header (a browser cannot set one on a WebSocket), and it must never go in the URL (query
// strings end up in logs). So the browser offers it as a second WebSocket subprotocol:
//     new WebSocket(url, ['v12.stomp', 'access_token.<jwt>'])
// The Gateway reads it from the Sec-WebSocket-Protocol header. The header is passed on unchanged, so Booking & Seat validates the same
// token again itself (every service is a resource server); it answers with the real subprotocol (v12.stomp), never with the token.
//
// No library is needed: after the upgrade is accepted, the two sockets are simply piped into each other.
import net from 'node:net';
import { authenticate } from './auth.js';
import { config } from './config.js';
import { log } from './log.js';

const TOKEN_PREFIX = 'access_token.';

/** The token offered as a subprotocol, or from an Authorization header (for tools that can set one). Never from the URL. */
export function tokenFromUpgrade(headers) {
  const offered = String(headers['sec-websocket-protocol'] ?? '').split(',').map((p) => p.trim());
  const subprotocol = offered.find((p) => p.startsWith(TOKEN_PREFIX));
  if (subprotocol && subprotocol.length > TOKEN_PREFIX.length) return subprotocol.slice(TOKEN_PREFIX.length);
  const match = /^Bearer (\S+)$/i.exec(headers.authorization ?? '');
  return match ? match[1] : null;
}

function reject(socket, status, text) {
  if (socket.writable) socket.write(`HTTP/1.1 ${status} ${text}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
  socket.destroy();
}

/** Adds the /ws upgrade handling to an http.Server. `options` exist for the tests. */
export function attachWebSocketProxy(server, options = {}) {
  const settings = {
    path: '/ws',
    bookingSeatUrl: config.bookingSeatUrl,
    corsOrigin: config.corsOrigin,
    authenticate,
    connectTimeoutMs: 5000,
    ...options,
  };
  const upstreamUrl = new URL(settings.bookingSeatUrl);

  server.on('upgrade', (req, socket, head) => {
    socket.on('error', () => socket.destroy()); // a browser that disappears must not crash the Gateway
    handle(req, socket, head).catch(() => reject(socket, 500, 'Internal Server Error'));
  });

  async function handle(req, socket, head) {
    // Only /ws is a WebSocket route. The query string is never read and never logged.
    if (new URL(req.url, 'http://gateway').pathname !== settings.path) return reject(socket, 404, 'Not Found');
    if (req.method !== 'GET' || String(req.headers.upgrade).toLowerCase() !== 'websocket') return reject(socket, 400, 'Bad Request');
    // A browser always sends its Origin: only the web app's origin may open the connection (cross-site WebSocket hijacking).
    if (req.headers.origin !== undefined && req.headers.origin !== settings.corsOrigin) return reject(socket, 403, 'Forbidden');

    const token = tokenFromUpgrade(req.headers);
    if (!token) return reject(socket, 401, 'Unauthorized');
    let user;
    try {
      user = await settings.authenticate(`Bearer ${token}`);
    } catch (err) {
      return err?.status === 503 ? reject(socket, 503, 'Service Unavailable') : reject(socket, 401, 'Unauthorized');
    }

    const upstream = net.connect({ host: upstreamUrl.hostname, port: Number(upstreamUrl.port || 80) });
    upstream.setTimeout(settings.connectTimeoutMs, () => upstream.destroy());
    upstream.on('error', () => reject(socket, 502, 'Bad Gateway'));
    upstream.on('connect', () => {
      upstream.setTimeout(0);
      // The upgrade request is passed on as it came, byte for byte (the headers keep their order and case).
      let raw = `${req.method} ${req.url} HTTP/${req.httpVersion}\r\n`;
      for (let i = 0; i < req.rawHeaders.length; i += 2) raw += `${req.rawHeaders[i]}: ${req.rawHeaders[i + 1]}\r\n`;
      upstream.write(`${raw}\r\n`);
      if (head?.length) upstream.write(head);
      upstream.pipe(socket);
      socket.pipe(upstream);
      log('info', 'WebSocket opened', { userId: user.sub });
    });
    socket.on('close', () => upstream.destroy());
    upstream.on('close', () => socket.destroy());
  }
}
