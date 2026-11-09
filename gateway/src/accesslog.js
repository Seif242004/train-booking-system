import { trace } from '@opentelemetry/api';
import { log } from './log.js';

// Pull the booking id and trip id out of the path for the log line (digits only, so nothing else can be injected).
const BOOKING = /\/bookings\/(\d+)/;
const TRIP = /\/trips\/(\d+)/;

/**
 * One structured log line per request, with the user id (the token's `sub`), the booking id and the trip id when the path has them, the
 * status, the duration and the trace id. Only the PATH is logged: never the query string, a header or a body, so tokens and card data
 * cannot reach the logs. Health checks are skipped.
 */
export function accessLog(req, res, next) {
  if (req.path === '/health') return next();
  // Read the clock now; the line is written when the response finishes, so the status and duration are known.
  const start = process.hrtime.bigint();
  res.on('finish', () => {
    // req.user is set by requireAuth, which runs after this middleware; by the time the response finishes it is available.
    const path = req.originalUrl.split('?')[0];
    const fields = { method: req.method, path, status: res.statusCode, ms: Number((process.hrtime.bigint() - start) / 1_000_000n) };
    if (req.user?.sub) fields.userId = req.user.sub;
    const booking = BOOKING.exec(path);
    if (booking) fields.bookingId = booking[1];
    const tripId = TRIP.exec(path);
    if (tripId) fields.tripId = tripId[1];
    const traceId = trace.getActiveSpan()?.spanContext().traceId;
    if (traceId) fields.trace_id = traceId;
    log(res.statusCode >= 500 ? 'error' : 'info', 'request', fields);
  });
  next();
}
