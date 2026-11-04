import type { CoachClass, TripStop } from './adminTypes';

/** Where a trip is in its life, worked out by the server. FULL is a flag on an upcoming trip, not a state of its own. */
export type TripState = 'UPCOMING' | 'DEPARTED' | 'ARRIVED' | 'CANCELLED';

/** capacity = confirmed + held + free. */
export interface SeatCounts {
  capacity: number;
  confirmed: number;
  held: number;
  free: number;
}

export interface BoardTrip {
  tripId: number;
  trainNumber: string;
  trainName: string;
  routeName: string;
  origin: string;
  destination: string;
  departureTime: string;
  arrivalTime: string;
  tripStatus: 'OPEN' | 'CANCELLING' | 'CANCELLED';
  state: TripState;
  full: boolean;
  first: SeatCounts;
  second: SeatCounts;
  third: SeatCounts;
  standing: SeatCounts;
  totals: SeatCounts;
  departedAt: string | null;
  totalSeats: number | null;
  confirmedAtDeparture: number | null;
}

export interface BoardSummary {
  trips: number;
  upcoming: number;
  departed: number;
  arrived: number;
  full: number;
  cancelled: number;
}

export interface BoardResponse {
  generatedAt: string;
  summary: BoardSummary;
  trips: BoardTrip[];
}

export type BoardSeatState = 'FREE' | 'HELD' | 'TAKEN';

export interface BoardDetailSeat {
  id: number;
  seatNumber: string;
  row: number;
  column: string;
  class: CoachClass;
  state: BoardSeatState;
  /** Only on TAKEN seats. */
  reference: string | null;
  /** For a HELD seat: when the hold ends. */
  holdExpiresAt: string | null;
}

export interface BoardDetailCoach {
  id: number;
  code: string;
  class: CoachClass;
  position: number;
  seats: BoardDetailSeat[];
  standing: SeatCounts;
}

/** The answer of GET /admin/board/trips/{id} (composed by the Gateway). */
export interface BoardDetail {
  generatedAt: string;
  trip: BoardTrip;
  stops: TripStop[];
  coaches: BoardDetailCoach[];
}

export const STATE_LABEL: Record<TripState, string> = { UPCOMING: 'Upcoming', DEPARTED: 'Departed', ARRIVED: 'Arrived', CANCELLED: 'Cancelled' };
