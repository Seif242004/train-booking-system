/** A change of the availability of a trip, pushed by Booking & Seat after it was committed (SRS section 6). */
export interface SeatEvent {
  type: 'SEAT_HELD' | 'SEAT_RELEASED' | 'SEAT_BOOKED' | 'STANDING_CHANGED' | 'TRIP_CANCELLED';
  tripId: number;
  seatIds: number[];
  coachId: number | null;
  delta: number | null;
  /** The segments the change applies to; empty means all of them (the trip was cancelled). */
  segments: number[];
  /** The counter of the changes of the trip: the next event has the next number, so a gap means a missed event. */
  version: number;
}

/** An event for this user only: the hold ended (the five minutes ran out, the trip was cancelled, or the user let go of it). */
export interface UserEvent {
  type: 'HOLD_EXPIRED';
  userId: string;
  tripId: number;
  bookingId: number;
  reason: 'EXPIRED' | 'TRIP_CANCELLED' | 'RELEASED';
}
