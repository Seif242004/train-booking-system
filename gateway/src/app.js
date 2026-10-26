import cors from 'cors';
import express from 'express';
import { requireAuth, requireRole } from './auth.js';
import { config } from './config.js';
import { HttpError, sendError } from './errors.js';
import { forwardTo } from './proxy.js';

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

  // Routes to the backend services. The Gateway checks the token and the ADMIN role first (so a non-admin gets 403
  // here and never reaches the service), then forwards the call with the caller's token. express.raw only reads the
  // body of requests that passed the checks, and keeps it as bytes so it is forwarded unchanged.
  const passThroughBody = express.raw({ type: () => true, limit: '1mb' });
  // Train & Schedule owns stations, trains, coaches, seats, routes, fares and trips (SRS 9.2). Note that
  // /admin/trains/{id}/coaches is under /admin/trains, the seats of a coach are under /admin/coaches/{id}/seats, and
  // the fares of a route are under /admin/routes/{id}/fares.
  for (const prefix of ['/admin/stations', '/admin/trains', '/admin/coaches', '/admin/seats', '/admin/routes', '/admin/trips']) {
    app.use(prefix, requireAuth, requireRole('ADMIN'), passThroughBody, forwardTo(config.trainScheduleUrl, prefix));
  }

  // Read-only routes for any signed-in user (not only admins): trip search, and the station list that the search page
  // offers. They are exact GET paths on purpose: /trips/{id}/... (the seat map, availability, layout) is routed later,
  // each to its own service, and must not be swallowed by a broad /trips prefix.
  app.get('/trips', requireAuth, forwardTo(config.trainScheduleUrl, '/trips'));
  app.get('/stations', requireAuth, forwardTo(config.trainScheduleUrl, '/stations'));

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
