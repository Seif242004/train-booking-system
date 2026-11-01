// Trip search for passengers (Flow 2): the Gateway composes the answer of Train & Schedule (the trips) with the answer
// of Booking & Seat (how many seats and standing places are left for each journey), so the browser makes one call.
//
// Booking & Seat is optional for the search: if it is down or slow, the trips are still returned, each with
// `availability: null`, and the page says that availability is not available. A booking outage must not hide the timetable.
import { config } from './config.js';
import { sendError } from './errors.js';

/**
 * Asks Booking & Seat for the totals of one journey. Returns { seatsLeft, standingLeft } (counts per class), or null if
 * the answer is not usable for any reason. Never throws: a failure here only costs this trip its availability.
 */
export async function fetchAvailabilitySummary(trip, authorization, { bookingSeatUrl, timeoutMs, fetchImpl = fetch }) {
  const { tripId, fromStopIndex, toStopIndex } = trip;
  // The values go into a URL, so they must be plain integers: nothing else may reach the path or the query.
  if (![tripId, fromStopIndex, toStopIndex].every(Number.isSafeInteger)) return null;
  const url = new URL(`/trips/${tripId}/availability`, bookingSeatUrl);
  url.search = new URLSearchParams({ from: String(fromStopIndex), to: String(toStopIndex), view: 'summary' }).toString();
  try {
    const res = await fetchImpl(url, {
      headers: { authorization, accept: 'application/json' },
      signal: AbortSignal.timeout(timeoutMs),
    });
    if (!res.ok) {
      console.error(JSON.stringify({ level: 'warn', msg: 'Availability not available', tripId, status: res.status }));
      return null;
    }
    const { summary } = await res.json();
    if (!summary || typeof summary.seatsLeft !== 'object' || typeof summary.standingLeft !== 'object') return null;
    return { seatsLeft: summary.seatsLeft, standingLeft: summary.standingLeft };
  } catch (err) {
    console.error(JSON.stringify({ level: 'warn', msg: 'Availability not available', tripId, cause: String(err?.cause?.code ?? err?.name ?? err) }));
    return null;
  }
}

/**
 * GET /trips?from=&to=&date=. Forwards the search to Train & Schedule; if it answers 200, adds `availability` to every trip.
 * Any other answer of Train & Schedule (a 400 for a bad station code, for example) is relayed unchanged.
 */
export function tripSearch(options = {}) {
  const settings = {
    trainScheduleUrl: config.trainScheduleUrl,
    bookingSeatUrl: config.bookingSeatUrl,
    upstreamTimeoutMs: config.upstreamTimeoutMs,
    availabilityTimeoutMs: config.availabilityTimeoutMs,
    fetchImpl: fetch,
    ...options,
  };

  return async (req, res) => {
    const target = new URL(req.originalUrl, settings.trainScheduleUrl);
    if (target.pathname !== '/trips') return sendError(res, 400, 'bad_request', 'The request path is not valid.');
    const authorization = req.headers.authorization;

    let upstream;
    let payload;
    try {
      upstream = await settings.fetchImpl(target, {
        headers: { authorization, accept: 'application/json' },
        signal: AbortSignal.timeout(settings.upstreamTimeoutMs),
      });
      payload = Buffer.from(await upstream.arrayBuffer());
    } catch (err) {
      if (err?.name === 'TimeoutError') return sendError(res, 504, 'upstream_timeout', 'The service took too long to answer.');
      console.error(JSON.stringify({ level: 'error', msg: 'Service unreachable', target: target.origin, cause: String(err?.cause?.code ?? err) }));
      return sendError(res, 502, 'upstream_unavailable', 'The service is unavailable. Try again shortly.');
    }

    let body;
    if (upstream.status === 200) {
      try {
        body = JSON.parse(payload.toString('utf8'));
      } catch {
        return sendError(res, 502, 'upstream_unavailable', 'The service is unavailable. Try again shortly.');
      }
    }
    if (!body || !Array.isArray(body.trips)) {
      // Not a successful search: relay it as it is.
      res.status(upstream.status);
      const type = upstream.headers.get('content-type');
      if (type) res.set('content-type', type);
      return res.send(payload);
    }

    // All the trips are looked up at the same time, so the search is as slow as the slowest single lookup, not the sum.
    const summaries = await Promise.all(body.trips.map((trip) => fetchAvailabilitySummary(trip, authorization, {
      bookingSeatUrl: settings.bookingSeatUrl,
      timeoutMs: settings.availabilityTimeoutMs,
      fetchImpl: settings.fetchImpl,
    })));
    body.trips.forEach((trip, i) => { trip.availability = summaries[i]; });
    res.json(body);
  };
}
