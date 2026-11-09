// app.js builds the Express application: CORS, the access log, and EVERY route of the Gateway (see docs/guide/08-gateway.md for the
// table).
// Express is a small Node.js web framework: app.get/post/use register a handler for a method and path, and handlers run in the order they
// are registered. A route that lists several handlers runs them left to right: requireAuth (valid Keycloak token), requireRole('ADMIN')
// (role check, 403 otherwise), a body reader, then forwardTo or a composing handler.
import cors from 'cors';
import express from 'express';
import { requireAuth, requireRole } from './auth.js';
import { accessLog } from './accesslog.js';
import { boardTripDetail } from './board.js';
import { config } from './config.js';
import { HttpError, sendError } from './errors.js';
import { log } from './log.js';
import { forwardTo } from './proxy.js';
import { tripSearch } from './search.js';
import { seatMap } from './seatmap.js';
import { ticketPdf, ticketQr } from './ticket.js';

/**
 * Creates the Express app without starting it (server.js listens; the tests call this directly).
 * Order matters: CORS, then the access log, then the routes, then the 404 fallback, then the error handler.
 * @returns the Express application
 */
export function createApp() {
  const app = express();
  // Do not announce "Express" in a response header: it only helps an attacker choose exploits.
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

  // Logs one line per request when the response is finished (it only reads the path, never the query string or headers).
  app.use(accessLog);

  // Public: used by humans and health checks. No token needed.
  // GET /health: public, no token. Used by Docker and by humans to see that the Gateway is up.
  app.get('/health', (_req, res) => {
    res.json({ status: 'UP' });
  });

  // Any valid token.
  // GET /me: any signed-in user. Echoes the identity read from the verified token (sub, name, email, roles).
  app.get('/me', requireAuth, (req, res) => {
    const { sub, name, email, roles } = req.user;
    res.json({ sub, name, email, roles });
  });

  // Example admin-only route: shows the role check that the real /admin/* routes will use (Flow 7).
  // GET /admin/ping: ADMIN only. A tiny route that proves the role check works.
  app.get('/admin/ping', requireAuth, requireRole('ADMIN'), (req, res) => {
    res.json({ message: 'pong', admin: req.user.sub });
  });

  // Routes to the backend services. The Gateway checks the token and the ADMIN role first (so a non-admin gets 403
  // here and never reaches the service), then forwards the call with the caller's token. express.raw only reads the
  // body of requests that passed the checks, and keeps it as bytes so it is forwarded unchanged.
  // express.raw keeps the request body as raw bytes (a Buffer), so the Gateway forwards JSON, or anything else, unchanged and never
  // parses it.
  // The limit stops a body over 1 MB. type: () => true means "read the body whatever the content type".
  const passThroughBody = express.raw({ type: () => true, limit: '1mb' });
  // Train & Schedule owns stations, trains, coaches, seats, routes, fares and trips (SRS 9.2). Note that
  // /admin/trains/{id}/coaches is under /admin/trains, the seats of a coach are under /admin/coaches/{id}/seats, and
  // the fares of a route are under /admin/routes/{id}/fares.
  // Cancelling a whole trip is Booking & Seat's job (Flow 8), not Train & Schedule's: these two exact paths are routed there BEFORE the
  // generic /admin/trips rules below, which send the trip CRUD to Train & Schedule. Booking & Seat tells Train & Schedule to mark the
  // trip cancelled.
  // POST /admin/trips/{id}/cancel (ADMIN): cancel a whole trip (Flow 8). Forwarded to Booking & Seat, which owns the trip cancellation.
  app.post('/admin/trips/:id/cancel', requireAuth, requireRole('ADMIN'), passThroughBody, forwardTo(config.bookingSeatUrl, '/admin/trips'));
  // GET /admin/trips/{id}/cancellation (ADMIN): progress of that trip cancellation (CANCELLING until every booking is cancelled). To
  // Booking & Seat.
  app.get('/admin/trips/:id/cancellation', requireAuth, requireRole('ADMIN'), forwardTo(config.bookingSeatUrl, '/admin/trips'));
  // The live trips board (read-only, admin): the list is Booking & Seat's own aggregate; the detail of one trip is composed here with
  // the layout.
  // Looking up a passenger by exact email for a counter booking (Booking & Seat answers one match or 404, never a list).
  // GET /admin/passengers/lookup?email= (ADMIN): finds one passenger by exact email for a counter booking. To Booking & Seat (one match
  // or 404).
  app.get('/admin/passengers/lookup', requireAuth, requireRole('ADMIN'), forwardTo(config.bookingSeatUrl, '/admin/passengers'));
  // GET /admin/board/trips (ADMIN): the live trips board list, an aggregate that Booking & Seat computes. A plain forward, query string
  // included.
  app.get('/admin/board/trips', requireAuth, requireRole('ADMIN'), forwardTo(config.bookingSeatUrl, '/admin/board/trips'));
  // GET /admin/board/trips/{id} (ADMIN): one trip on the board. Composed here by boardTripDetail (layout from Train & Schedule + seat
  // states from Booking & Seat).
  app.get('/admin/board/trips/:id', requireAuth, requireRole('ADMIN'), boardTripDetail());
  // ADMIN CRUD for the timetable data: every method (GET, POST, PUT, PATCH, DELETE) under these prefixes goes to Train & Schedule.
  // app.use(prefix, ...) matches the prefix and everything below it. The more specific trip routes above are registered first, so they
  // win.
  for (const prefix of ['/admin/stations', '/admin/trains', '/admin/coaches', '/admin/seats', '/admin/routes', '/admin/trips']) {
    app.use(prefix, requireAuth, requireRole('ADMIN'), passThroughBody, forwardTo(config.trainScheduleUrl, prefix));
  }

  // Read-only routes for any signed-in user (not only admins): trip search, and the station list that the search page
  // offers. They are exact GET paths on purpose: /trips/{id}/... (the seat map, availability, layout) is routed later,
  // each to its own service, and must not be swallowed by a broad /trips prefix.
  // The search is composed here (Flow 2): the trips come from Train & Schedule, the seats and standing places left for
  // each journey from Booking & Seat. /trips/{id}/availability is called by the Gateway itself and has no public route.
  // GET /trips?from=&to=&date= (any signed-in user): trip search, composed by tripSearch (adds availability per class from Booking &
  // Seat).
  app.get('/trips', requireAuth, tripSearch());
  // GET /stations (any signed-in user): the station list for the search page. A plain forward to Train & Schedule.
  app.get('/stations', requireAuth, forwardTo(config.trainScheduleUrl, '/stations'));
  // The one seat-map call of the browser (Flow 2), composed here from the layout (Train & Schedule) and the availability
  // (Booking & Seat). /trips/{id}/layout and /trips/{id}/availability themselves have no public route.
  // GET /trips/{id}/seat-map?from=&to= (any signed-in user): composed by seatMap (layout + availability + the caller's own hold).
  app.get('/trips/:id/seat-map', requireAuth, seatMap());

  // The bookings of the passenger (SRS 9.1): holds, the booking, payment, cancellation. Booking & Seat checks that a booking belongs
  // to the caller (the token is forwarded), and answers 404 for anyone else's.
  // The ticket PDF (the one Ticket service route the browser can reach): checked here, then fetched from the internal Ticket service.
  // GET /bookings/{id}/ticket (signed-in): the PDF, after an ownership check (or ADMIN). Registered BEFORE the /bookings forward so it
  // is not swallowed.
  app.get('/bookings/:id/ticket', requireAuth, ticketPdf());
  // GET /bookings/{id}/ticket/qr (signed-in): the QR code PNG, same checks as the PDF.
  app.get('/bookings/:id/ticket/qr', requireAuth, ticketQr());
  // Every other /bookings call (pick, release, hold, payment, cancel, my bookings, resend ticket...) goes to Booking & Seat with the
  // caller's token.
  // Booking & Seat does the owner check by the token's sub and answers 404 for somebody else's booking.
  app.use('/bookings', requireAuth, passThroughBody, forwardTo(config.bookingSeatUrl, '/bookings'));
  // Support (Flow 6 way B, Flow 7): an admin cancels a booking on the passenger's request. The Gateway checks the ADMIN role first;
  // Booking & Seat
  // checks it again.
  // /admin/bookings/** (ADMIN): support search, one booking, admin cancel, counter and complimentary issue. To Booking & Seat.
  app.use('/admin/bookings', requireAuth, requireRole('ADMIN'), passThroughBody, forwardTo(config.bookingSeatUrl, '/admin/bookings'));

  // Stripe's webhook (Flow 4, SRS 9.3): no token (Stripe has none), the signature is what authenticates it, and Payment verifies it.
  // The body
  // must reach Payment byte for byte, because the signature is computed over the exact bytes: express.raw, never a JSON parser. Only the
  // content type and Stripe-Signature are passed on. Only POST on this exact path.
  // The Stripe webhook is the only route without a user token. express.raw is essential: Stripe signs the exact bytes, so a JSON parser
  // would break it.
  app.post(
    '/webhooks/payment',
    express.raw({ type: () => true, limit: '1mb' }),
    forwardTo(config.paymentUrl, '/webhooks/payment', { requestHeaders: ['content-type', 'stripe-signature'] }),
  );

  // Fallback: any path or method that no route above matched gets a JSON 404 (and is never forwarded anywhere).
  app.use((req, res) => {
    sendError(res, 404, 'not_found', `No route for ${req.method} ${req.path}.`);
  });

  // Express 5 forwards errors thrown (or rejected) in handlers and middleware here.
  // The four parameters are what mark this as the error handler, even though `_next` is unused.
  // The error handler. Errors thrown in async handlers (in Express 5) arrive here: an HttpError becomes its status and code; anything
  // else is
  // logged and answered as a generic 500 so no internal detail reaches the client.
  app.use((err, _req, res, _next) => {
    if (err instanceof HttpError) {
      return sendError(res, err.status, err.code, err.message, err.headers);
    }
    log('error', 'Unhandled error', { cause: String(err?.stack ?? err) });
    sendError(res, 500, 'internal_error', 'Something went wrong.');
  });

  return app;
}
