import type { SeatMap, SeatState } from '../seatMapTypes';
import type { SeatEvent } from './liveTypes';

/** What happened to the map when an event was applied. */
export type Applied =
  /** The event was older than the map (already included in it): nothing to do. */
  | { kind: 'stale' }
  /** An event was missed, or the change cannot be worked out from the event alone: the whole map must be loaded again. */
  | { kind: 'reload' }
  /** The map after the event. */
  | { kind: 'applied'; map: SeatMap };

/**
 * Applies one live event to the seat map the user is looking at (SRS section 6, FR-R2, FR-R3), without reloading.
 *
 * - The event counter of the trip must go up by exactly one: an older event is dropped, a gap means a missed event and the map is
 *   loaded again. An event that does not touch the user's journey still advances the counter, so that gaps are always seen.
 * - The user's journey uses the segments fromStop to toStop - 1. An event matters only if it shares a segment with it.
 * - A seat that becomes held or booked on any shared segment is not available for the whole journey. A seat that is released is
 *   only free again for the whole journey if the event covers every segment of the journey; otherwise another hold on the rest of
 *   the journey may remain, which the event cannot tell, so the map is loaded again. The same holds for the standing places.
 */
export function applySeatEvent(map: SeatMap, event: SeatEvent): Applied {
  if (map.version === null || event.version > map.version + 1) {
    return { kind: 'reload' };
  }
  if (event.version <= map.version) {
    return { kind: 'stale' };
  }

  const journey = Array.from({ length: map.journey.toStop - map.journey.fromStop }, (_, i) => map.journey.fromStop + i);
  const eventSegments = event.segments.length === 0 ? journey : event.segments; // empty: every segment
  const shared = journey.filter((segment) => eventSegments.includes(segment));
  if (shared.length === 0) {
    return { kind: 'applied', map: { ...map, version: event.version } };
  }
  const coversTheJourney = shared.length === journey.length;

  if (event.type === 'TRIP_CANCELLED') {
    return { kind: 'applied', map: { ...map, tripStatus: 'CANCELLED', version: event.version } };
  }

  if (event.type === 'STANDING_CHANGED') {
    if (!coversTheJourney) return { kind: 'reload' };
    const coaches = map.coaches.map((coach) => coach.id !== event.coachId ? coach
      : { ...coach, standing: { ...coach.standing, remaining: Math.max(0, Math.min(coach.standing.capacity, coach.standing.remaining + (event.delta ?? 0))) } });
    return { kind: 'applied', map: { ...map, coaches, version: event.version } };
  }

  const ids = new Set(event.seatIds);
  if (event.type === 'SEAT_RELEASED' && !coversTheJourney) {
    return { kind: 'reload' };
  }
  // The user's own picks are held for them, so no seat of theirs is ever "lost" here: the page shows its own seats from myHold.
  const coaches = map.coaches.map((coach) => ({
    ...coach,
    seats: coach.seats.map((seat) => {
      if (!ids.has(seat.id)) return seat;
      let state: SeatState = seat.state;
      if (event.type === 'SEAT_HELD' && seat.state === 'AVAILABLE') state = 'HELD';
      if (event.type === 'SEAT_BOOKED') state = 'BOOKED';
      if (event.type === 'SEAT_RELEASED') state = 'AVAILABLE';
      return state === seat.state ? seat : { ...seat, state };
    }),
  }));
  return { kind: 'applied', map: { ...map, coaches, version: event.version } };
}
