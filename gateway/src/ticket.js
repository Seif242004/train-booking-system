// The ticket PDF in the browser: GET /bookings/{id}/ticket. The Ticket service stays internal-only (it trusts the shared internal key and no
// user token), so the Gateway is the one that decides who may download: the booking's own passenger, or an admin (support). The decision
// is made by asking Booking & Seat for the booking WITH THE CALLER'S TOKEN, which answers 404 for anyone else's booking.
import { config } from './config.js';
import { log } from './log.js';
import { sendError } from './errors.js';

const isId = (value) => /^\d{1,12}$/.test(value ?? '');

async function getJson(url, headers, timeoutMs, fetchImpl) {
  const res = await fetchImpl(url, { headers, signal: AbortSignal.timeout(timeoutMs) });
  const text = await res.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { body = null; }
  return { status: res.status, body };
}

/** GET /bookings/:id/ticket. Mount it after requireAuth and before the generic /bookings forwarding. */
export function ticketPdf(options = {}) {
  const settings = {
    bookingSeatUrl: config.bookingSeatUrl,
    ticketUrl: config.ticketUrl,
    internalApiKey: config.internalApiKey,
    upstreamTimeoutMs: config.upstreamTimeoutMs,
    fetchImpl: fetch,
    ...options,
  };

  return async (req, res) => {
    const { id } = req.params;
    if (!isId(id)) return sendError(res, 400, 'bad_request', 'The booking id is not valid.');
    if (!settings.internalApiKey) {
      log('error', 'Ticket download is not configured: INTERNAL_API_KEY is empty');
      return sendError(res, 503, 'ticket_unavailable', 'Tickets cannot be downloaded right now. Try again shortly.');
    }
    const asCaller = { authorization: req.headers.authorization, accept: 'application/json' };

    try {
      // 1. Is it the caller's booking? Booking & Seat answers 404 for other users' bookings.
      let found = await getJson(new URL(`/bookings/${id}`, settings.bookingSeatUrl), asCaller, settings.upstreamTimeoutMs, settings.fetchImpl);
      let booking = found.status === 200 ? found.body : null;
      if (found.status === 404 && req.user?.roles?.includes('ADMIN')) {
        // 2. Support may download any ticket: the admin view of the booking (Booking & Seat checks the ADMIN role again).
        found = await getJson(new URL(`/admin/bookings/${id}`, settings.bookingSeatUrl), asCaller, settings.upstreamTimeoutMs, settings.fetchImpl);
        booking = found.status === 200 ? found.body?.booking : null;
      }
      if (found.status === 404) return sendError(res, 404, 'booking_not_found', 'There is no such booking.');
      if (!booking) return sendError(res, 502, 'upstream_unavailable', 'The service is unavailable. Try again shortly.');

      // 3. Only a confirmed booking has a valid ticket (a cancelled one has an invalidated QR code).
      if (booking.status !== 'CONFIRMED') {
        return sendError(res, 409, 'ticket_not_available', 'The ticket is available once the booking is confirmed.');
      }

      // 4. The PDF, from the internal Ticket service.
      const pdf = await settings.fetchImpl(new URL(`/internal/tickets/${id}/pdf`, settings.ticketUrl), {
        headers: { 'x-internal-api-key': settings.internalApiKey },
        signal: AbortSignal.timeout(settings.upstreamTimeoutMs),
      });
      if (pdf.status === 404) {
        // Tickets are made asynchronously after the payment: confirmed, but the PDF is not there yet.
        return sendError(res, 409, 'ticket_not_ready', 'Your ticket is still being prepared. Try again in a moment.');
      }
      if (pdf.status !== 200) return sendError(res, 502, 'upstream_unavailable', 'The service is unavailable. Try again shortly.');

      const bytes = Buffer.from(await pdf.arrayBuffer());
      const reference = String(booking.reference ?? id).replace(/[^A-Za-z0-9_-]/g, '');
      res.status(200);
      res.set('content-type', 'application/pdf');
      res.set('content-disposition', `attachment; filename="ticket-${reference}.pdf"`);
      res.set('cache-control', 'private, no-store');
      res.send(bytes);
    } catch (err) {
      if (err?.name === 'TimeoutError') return sendError(res, 504, 'upstream_timeout', 'The service took too long to answer.');
      log('error', 'Service unreachable', { cause: String(err?.cause?.code ?? err) });
      return sendError(res, 502, 'upstream_unavailable', 'The service is unavailable. Try again shortly.');
    }
  };
}
