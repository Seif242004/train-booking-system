// The detail of one trip on the admin trips board: GET /admin/board/trips/{id}. Same pattern as the seat map: the layout (coaches and seats,
// Train & Schedule) is put together with the state of every seat for the FULL journey (Booking & Seat, admin read). The list endpoint
// (/admin/board/trips) is a plain forward to Booking & Seat and is mounted in app.js.
import { config } from './config.js';
import { log } from './log.js';
import { sendError } from './errors.js';

/** Puts the two answers together. Pure: no network, so it is tested on its own. */
export function composeBoardDetail(layout, detail) {
  const stateBySeat = new Map((detail.seats ?? []).map((s) => [s.seatId, s]));
  const standingByCoach = new Map((detail.standing ?? []).map((s) => [s.coachId, s.counts]));
  return {
    generatedAt: detail.generatedAt,
    trip: detail.trip,
    stops: layout.stops ?? [],
    coaches: (layout.coaches ?? []).map((coach) => {
      const counts = standingByCoach.get(coach.id);
      return {
        id: coach.id,
        code: coach.code,
        class: coach.class,
        position: coach.position,
        // A seat that Booking does not know is shown as taken, never as free. Only a taken seat carries a booking reference.
        seats: coach.seats.map((seat) => {
          const known = stateBySeat.get(seat.id);
          return { ...seat, state: known?.state ?? 'TAKEN', reference: known?.state === 'TAKEN' ? known.reference ?? null : null,
            // When a hold ends, for the countdown on a HELD seat.
            holdExpiresAt: known?.state === 'HELD' ? known.holdExpiresAt ?? null : null };
        }),
        standing: counts
          ? { capacity: counts.capacity, confirmed: counts.confirmed, held: counts.held, free: counts.free }
          : { capacity: coach.standingCapacity ?? 0, confirmed: 0, held: 0, free: 0 },
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

/** GET /admin/board/trips/:id. Mount it after requireAuth and requireRole('ADMIN'). */
export function boardTripDetail(options = {}) {
  const settings = {
    trainScheduleUrl: config.trainScheduleUrl,
    bookingSeatUrl: config.bookingSeatUrl,
    upstreamTimeoutMs: config.upstreamTimeoutMs,
    fetchImpl: fetch,
    ...options,
  };

  return async (req, res) => {
    const { id } = req.params;
    if (!/^\d{1,12}$/.test(id)) return sendError(res, 400, 'bad_request', 'The trip id is not valid.');
    const authorization = req.headers.authorization;
    let layout;
    let detail;
    try {
      [layout, detail] = await Promise.all([
        getJson(new URL(`/trips/${id}/layout`, settings.trainScheduleUrl), authorization, settings.upstreamTimeoutMs, settings.fetchImpl),
        getJson(new URL(`/admin/board/trips/${id}`, settings.bookingSeatUrl), authorization, settings.upstreamTimeoutMs, settings.fetchImpl),
      ]);
    } catch (err) {
      if (err?.name === 'TimeoutError') return sendError(res, 504, 'upstream_timeout', 'The service took too long to answer.');
      log('error', 'Service unreachable', { cause: String(err?.cause?.code ?? err) });
      return sendError(res, 502, 'upstream_unavailable', 'The service is unavailable. Try again shortly.');
    }
    for (const answer of [layout, detail]) {
      if (answer.status !== 200 || !answer.body) {
        const status = answer.status >= 400 && answer.status < 500 ? answer.status : 502;
        return res.status(status).json(answer.body?.error ? answer.body : { error: { code: 'upstream_unavailable', message: 'The service is unavailable. Try again shortly.' } });
      }
    }
    res.set('cache-control', 'private, no-store');
    res.json(composeBoardDetail(layout.body, detail.body));
  };
}
