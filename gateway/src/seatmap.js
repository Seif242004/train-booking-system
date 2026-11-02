// The seat map of a trip for a journey (Flow 2): the browser makes ONE call, GET /trips/{id}/seat-map?from=&to=, and the
// Gateway composes the answer from the layout of Train & Schedule (coaches, seats, standing capacity, stops) and the
// availability of Booking & Seat (the state of every seat and the standing places left for the journey).
// `from` and `to` are stop indexes of the trip (the boarding stop and the destination stop).
import { config } from './config.js';
import { log } from './log.js';
import { sendError } from './errors.js';

/** Puts the two answers together. Pure: no network, so it is tested on its own. */
export function composeSeatMap(layout, availability) {
  const stateBySeat = new Map((availability.seats ?? []).map((s) => [s.seatId, s.state]));
  const standingByCoach = new Map((availability.standing ?? []).map((s) => [s.coachId, s]));
  const stops = layout.stops ?? [];
  const from = stops[availability.fromStop];
  const to = stops[availability.toStop];

  return {
    tripId: layout.tripId,
    // `status` is the trip in Train & Schedule (ACTIVE, CANCELLED); `tripStatus` is Booking's own copy (OPEN, CANCELLING, CANCELLED).
    status: layout.status,
    tripStatus: availability.tripStatus,
    departureDate: layout.departureDate,
    train: { number: layout.trainNumber, name: layout.trainName, type: layout.trainType },
    routeName: layout.routeName,
    stops,
    journey: {
      fromStop: availability.fromStop,
      toStop: availability.toStop,
      from: from ?? null,
      to: to ?? null,
      departureTime: from?.departureTime ?? null,
      arrivalTime: to?.arrivalTime ?? null,
    },
    // The price of the whole journey per class in EGP; the server works out the real amount again when a hold is made.
    fares: availability.fares ?? {},
    summary: availability.summary,
    version: availability.version ?? null,
    // What the caller holds on this trip (the booking, with its expiry), so the page shows those seats as selected, with the countdown.
    myHold: availability.myHold ?? null,
    coaches: (layout.coaches ?? []).map((coach) => {
      const standing = standingByCoach.get(coach.id);
      return {
        id: coach.id,
        code: coach.code,
        class: coach.class,
        position: coach.position,
        // A seat that Booking does not know about is shown as unavailable, never as free.
        seats: coach.seats.map((seat) => ({ ...seat, state: stateBySeat.get(seat.id) ?? 'BOOKED' })),
        standing: { capacity: standing?.capacity ?? coach.standingCapacity, remaining: standing?.remaining ?? 0 },
      };
    }),
  };
}

async function getJson(url, authorization, timeoutMs, fetchImpl) {
  const res = await fetchImpl(url, { headers: { authorization, accept: 'application/json' }, signal: AbortSignal.timeout(timeoutMs) });
  const text = await res.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { body = null; }
  return { status: res.status, body };
}

const isIndex = (value) => /^\d{1,6}$/.test(value ?? '');

/** GET /trips/:id/seat-map?from=&to= */
export function seatMap(options = {}) {
  const settings = {
    trainScheduleUrl: config.trainScheduleUrl,
    bookingSeatUrl: config.bookingSeatUrl,
    upstreamTimeoutMs: config.upstreamTimeoutMs,
    fetchImpl: fetch,
    ...options,
  };

  return async (req, res) => {
    const { id } = req.params;
    const { from, to } = req.query;
    if (!isIndex(id)) return sendError(res, 400, 'bad_request', 'The trip id is not valid.');
    if (!isIndex(from) || !isIndex(to)) return sendError(res, 400, 'validation_failed', 'from and to must be stop numbers of the trip.');
    const authorization = req.headers.authorization;

    let layout;
    let availability;
    try {
      [layout, availability] = await Promise.all([
        getJson(new URL(`/trips/${id}/layout`, settings.trainScheduleUrl), authorization, settings.upstreamTimeoutMs, settings.fetchImpl),
        getJson(new URL(`/trips/${id}/availability?from=${from}&to=${to}`, settings.bookingSeatUrl), authorization, settings.upstreamTimeoutMs, settings.fetchImpl),
      ]);
    } catch (err) {
      if (err?.name === 'TimeoutError') return sendError(res, 504, 'upstream_timeout', 'The service took too long to answer.');
      log('error', 'Service unreachable', { cause: String(err?.cause?.code ?? err) });
      return sendError(res, 502, 'upstream_unavailable', 'The service is unavailable. Try again shortly.');
    }

    // A refusal of either service is relayed as it is: an unknown trip (404), or a journey that does not fit it (400).
    for (const answer of [layout, availability]) {
      if (answer.status !== 200 || !answer.body) {
        const status = answer.status >= 400 && answer.status < 500 ? answer.status : 502;
        return res.status(status).json(answer.body?.error ? answer.body : { error: { code: 'upstream_unavailable', message: 'The service is unavailable. Try again shortly.' } });
      }
    }
    res.json(composeSeatMap(layout.body, availability.body));
  };
}
