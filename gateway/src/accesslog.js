import { trace } from '@opentelemetry/api';
import { log } from './log.js';

const BOOKING = /\/bookings\/(\d+)/;
const TRIP = /\/trips\/(\d+)/;

/**
 * One structured log line per request, with the user id (the token's `sub`), the booking id and the trip id when the path has them, the
 * status, the duration and the trace id. Only the PATH is logged: never the query string, a header or a body, so tokens and card data
 * cannot reach the logs. Health checks are skipped.
 */
export function accessLog(req, res, next) {
  if (req.path === '/health') return next();
  const start = process.hrtime.bigint();
  res.on('finish', () => {
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
