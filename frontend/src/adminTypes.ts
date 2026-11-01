// Shapes returned by the admin API of Train & Schedule (SRS section 8 and 9.2). Field names are the JSON names.

export type TrainStatus = 'ACTIVE' | 'INACTIVE';
export type CoachClass = 'FIRST' | 'SECOND' | 'THIRD';

export const TRAIN_STATUSES: TrainStatus[] = ['ACTIVE', 'INACTIVE'];
export const COACH_CLASSES: CoachClass[] = ['FIRST', 'SECOND', 'THIRD'];

export const CLASS_LABEL: Record<CoachClass, string> = { FIRST: 'First', SECOND: 'Second', THIRD: 'Third' };

export interface Train {
  id: number;
  number: string;
  name: string;
  type: string;
  status: TrainStatus;
}

export interface Coach {
  id: number;
  trainId: number;
  code: string;
  class: CoachClass;
  /** Order of the coach in the train, starting at 1. */
  position: number;
  standingCapacity: number;
}

export interface Station {
  id: number;
  code: string;
  name: string;
  city: string;
}

/** One stop of a route, with its station's details. Segment k is the leg from stop k to stop k+1. */
export interface RouteStop {
  stopIndex: number;
  stationId: number;
  stationCode: string;
  stationName: string;
  stationCity: string;
}

export interface Route {
  id: number;
  name: string;
  stops: RouteStop[];
}

/** The price of one route segment for one class, in EGP. */
export interface Fare {
  segmentIndex: number;
  class: CoachClass;
  amount: number;
  currency: 'EGP';
}

export type TripStatus = 'ACTIVE' | 'CANCELLED';

/** One stop of a trip. The times are local date-times such as "2026-11-02T08:00:00"; the first stop has no arrival and the last no departure. */
export interface TripStop {
  stopIndex: number;
  stationId: number;
  stationCode: string;
  stationName: string;
  arrivalTime: string | null;
  departureTime: string | null;
}

export interface Trip {
  id: number;
  trainId: number;
  trainNumber: string;
  trainName: string;
  routeId: number;
  routeName: string;
  departureDate: string;
  status: TripStatus;
  stops: TripStop[];
}

/** The price of a whole journey for one class: the sum of its segments, in EGP. */
export interface JourneyFare {
  class: CoachClass;
  amount: number;
  currency: 'EGP';
}

/**
 * What is left for one journey, per class of coach, as counted by Booking & Seat. A class is listed only if the trip has
 * seats (or standing places) of it. Seats that are held or booked are not counted.
 */
export interface JourneyAvailability {
  seatsLeft: Partial<Record<CoachClass, number>>;
  standingLeft: Partial<Record<CoachClass, number>>;
}

/** One trip returned by the passenger search. */
export interface TripSearchResult {
  tripId: number;
  trainNumber: string;
  trainName: string;
  trainType: string;
  routeName: string;
  from: Station;
  to: Station;
  fromStopIndex: number;
  toStopIndex: number;
  departureTime: string;
  arrivalTime: string;
  stops: TripStop[];
  fares: JourneyFare[];
  /** Null when Booking & Seat could not be asked: the timetable and fares are still correct. */
  availability: JourneyAvailability | null;
}

/** The answer of GET /trips: the date is present only when there are no trips and a later trip exists. */
export interface TripSearchResponse {
  trips: TripSearchResult[];
  nextAvailableDate?: string;
}

export interface Seat {
  id: number;
  coachId: number;
  seatNumber: string;
  row: number;
  column: string;
  /** Always the class of the seat's coach; set by the server. */
  class: CoachClass;
}
