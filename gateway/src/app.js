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

  app.use(accessLog);

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
  // Cancelling a whole trip is Booking & Seat's job (Flow 8), not Train & Schedule's: these two exact paths are routed there BEFORE the
  // generic /admin/trips rules below, which send the trip CRUD to Train & Schedule. Booking & Seat tells Train & Schedule to mark the trip cancelled.
  app.post('/admin/trips/:id/cancel', requireAuth, requireRole('ADMIN'), passThroughBody, forwardTo(config.bookingSeatUrl, '/admin/trips'));
  app.get('/admin/trips/:id/cancellation', requireAuth, requireRole('ADMIN'), forwardTo(config.bookingSeatUrl, '/admin/trips'));
  // The live trips board (read-only, admin): the list is Booking & Seat's own aggregate; the detail of one trip is composed here with the layout.
  // Looking up a passenger by exact email for a counter booking (Booking & Seat answers one match or 404, never a list).
  app.get('/admin/passengers/lookup', requireAuth, requireRole('ADMIN'), forwardTo(config.bookingSeatUrl, '/admin/passengers'));
  app.get('/admin/board/trips', requireAuth, requireRole('ADMIN'), forwardTo(config.bookingSeatUrl, '/admin/board/trips'));
  app.get('/admin/board/trips/:id', requireAuth, requireRole('ADMIN'), boardTripDetail());
  for (const prefix of ['/admin/stations', '/admin/trains', '/admin/coaches', '/admin/seats', '/admin/routes', '/admin/trips']) {
    app.use(prefix, requireAuth, requireRole('ADMIN'), passThroughBody, forwardTo(config.trainScheduleUrl, prefix));
  }

  // Read-only routes for any signed-in user (not only admins): trip search, and the station list that the search page
  // offers. They are exact GET paths on purpose: /trips/{id}/... (the seat map, availability, layout) is routed later,
  // each to its own service, and must not be swallowed by a broad /trips prefix.
  // The search is composed here (Flow 2): the trips come from Train & Schedule, the seats and standing places left for
  // each journey from Booking & Seat. /trips/{id}/availability is called by the Gateway itself and has no public route.
  app.get('/trips', requireAuth, tripSearch());
  app.get('/stations', requireAuth, forwardTo(config.trainScheduleUrl, '/stations'));
  // The one seat-map call of the browser (Flow 2), composed here from the layout (Train & Schedule) and the availability
  // (Booking & Seat). /trips/{id}/layout and /trips/{id}/availability themselves have no public route.
  app.get('/trips/:id/seat-map', requireAuth, seatMap());

  // The bookings of the passenger (SRS 9.1): holds, the booking, payment, cancellation. Booking & Seat checks that a booking belongs
  // to the caller (the token is forwarded), and answers 404 for anyone else's.
  // The ticket PDF (the one Ticket service route the browser can reach): checked here, then fetched from the internal Ticket service.
  app.get('/bookings/:id/ticket', requireAuth, ticketPdf());
  app.get('/bookings/:id/ticket/qr', requireAuth, ticketQr());
  app.use('/bookings', requireAuth, passThroughBody, forwardTo(config.bookingSeatUrl, '/bookings'));
  // Support (Flow 6 way B, Flow 7): an admin cancels a booking on the passenger's request. The Gateway checks the ADMIN role first; Booking & Seat
  // checks it again.
  app.use('/admin/bookings', requireAuth, requireRole('ADMIN'), passThroughBody, forwardTo(config.bookingSeatUrl, '/admin/bookings'));

  // Stripe's webhook (Flow 4, SRS 9.3): no token (Stripe has none), the signature is what authenticates it, and Payment verifies it. The body
  // must reach Payment byte for byte, because the signature is computed over the exact bytes: express.raw, never a JSON parser. Only the
  // content type and Stripe-Signature are passed on. Only POST on this exact path.
  app.post(
    '/webhooks/payment',
    express.raw({ type: () => true, limit: '1mb' }),
    forwardTo(config.paymentUrl, '/webhooks/payment', { requestHeaders: ['content-type', 'stripe-signature'] }),
  );

  app.use((req, res) => {
    sendError(res, 404, 'not_found', `No route for ${req.method} ${req.path}.`);
  });

  // Express 5 forwards errors thrown (or rejected) in handlers and middleware here.
  // The four parameters are what mark this as the error handler, even though `_next` is unused.
  app.use((err, _req, res, _next) => {
    if (err instanceof HttpError) {
      return sendError(res, err.status, err.code, err.message, err.headers);
    }
    log('error', 'Unhandled error', { cause: String(err?.stack ?? err) });
    sendError(res, 500, 'internal_error', 'Something went wrong.');
  });

  return app;
}
