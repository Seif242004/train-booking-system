import type { CoachClass, TripStop } from './adminTypes';

/** The state of a seat for the journey. "Selected" is the browser's own state and never comes from the server. */
export type SeatState = 'AVAILABLE' | 'HELD' | 'BOOKED';

export interface SeatMapSeat {
  id: number;
  seatNumber: string;
  row: number;
  column: string;
  class: CoachClass;
  state: SeatState;
}

export interface SeatMapCoach {
  id: number;
  code: string;
  class: CoachClass;
  position: number;
  seats: SeatMapSeat[];
  standing: { capacity: number; remaining: number };
}

/** The answer of GET /trips/{id}/seat-map?from=&to= (composed by the Gateway). */
export interface SeatMap {
  tripId: number;
  /** The trip in Train & Schedule. */
  status: 'ACTIVE' | 'CANCELLED';
  /** Booking's own copy of the trip. */
  tripStatus: 'OPEN' | 'CANCELLING' | 'CANCELLED';
  departureDate: string;
  train: { number: string; name: string; type: string };
  routeName: string;
  stops: TripStop[];
  journey: {
    fromStop: number;
    toStop: number;
    from: TripStop | null;
    to: TripStop | null;
    departureTime: string | null;
    arrivalTime: string | null;
  };
  /** The price of the whole journey per class, in EGP. */
  fares: Partial<Record<CoachClass, number>>;
  summary: { seatsLeft: Partial<Record<CoachClass, number>>; standingLeft: Partial<Record<CoachClass, number>> };
  /** Counter of the changes of the trip, to detect missed live updates. Null until Booking provides it. */
  version: number | null;
  coaches: SeatMapCoach[];
}
