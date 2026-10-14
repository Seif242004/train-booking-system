import cors from 'cors';
import express from 'express';
import { requireAuth, requireRole } from './auth.js';
import { config } from './config.js';
import { HttpError, sendError } from './errors.js';

export function createApp() {
  const app = express();
  app.disable('x-powered-by');

  // CORS: only the web app's origin is allowed. For any other origin no CORS headers are sent, so the browser
  // blocks the response. Requests without an Origin header (curl, other servers) are not affected by CORS.
  app.use(
    cors({
      origin: (origin, callback) => callback(null, origin === config.corsOrigin ? origin : false),
      methods: ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'],
      allowedHeaders: ['Authorization', 'Content-Type'],
      maxAge: 600,
    }),
  );

  // Public: used by humans and health checks. No token needed.
  app.get('/health', (_req, res) => {
    res.json({ status: 'UP' });
  });

  // Any valid token.
  app.get('/me', requireAuth, (req, res) => {
    const { sub, name, email, roles } = req.user;
    res.json({ sub, name, email, roles });
  });

  // Example admin-only route: shows the role check that the real /admin/* routes will use (Flow 7).
  app.get('/admin/ping', requireAuth, requireRole('ADMIN'), (req, res) => {
    res.json({ message: 'pong', admin: req.user.sub });
  });

  app.use((req, res) => {
    sendError(res, 404, 'not_found', `No route for ${req.method} ${req.path}.`);
  });

  // Express 5 forwards errors thrown (or rejected) in handlers and middleware here.
  // The four parameters are what mark this as the error handler, even though `_next` is unused.
  app.use((err, _req, res, _next) => {
    if (err instanceof HttpError) {
      return sendError(res, err.status, err.code, err.message, err.headers);
    }
    console.error(JSON.stringify({ level: 'error', msg: 'Unhandled error', cause: String(err?.stack ?? err) }));
    sendError(res, 500, 'internal_error', 'Something went wrong.');
  });

  return app;
}
