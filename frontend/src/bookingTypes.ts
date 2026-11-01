import type { CoachClass } from './adminTypes';

export type BookingStatus = 'PENDING_PAYMENT' | 'CONFIRMED' | 'EXPIRED' | 'CANCELLED' | 'ABANDONED';
export type RefundStatus = 'NONE' | 'REFUND_PENDING' | 'REFUNDED' | 'REFUND_FAILED';

export interface BookingItem {
  type: 'SEAT' | 'STANDING';
  seatId?: number;
  seatNumber?: string;
  coachId: number;
  coachCode: string;
  coachClass: CoachClass;
  qty: number;
  price: number;
}

export interface BookingStop {
  stopIndex: number;
  stationCode: string;
  stationName: string;
  arrivalTime: string | null;
  departureTime: string | null;
}

/** The answer of `POST /bookings/{id}/payment` (Continue to payment): what Stripe's embedded form needs. */
export interface PaymentStart {
  clientSecret: string;
  publishableKey: string;
  amount: number;
  currency: string;
  expiresAt: string;
  serverTime: string;
}

/** A booking as returned by the Gateway (Booking & Seat). */
export interface Booking {
  id: number;
  reference: string;
  status: BookingStatus;
  refundStatus: RefundStatus;
  tripId: number;
  fromStop: number;
  toStop: number;
  amount: number;
  currency: string;
  /** When the hold runs out, by the clock of the server. */
  expiresAt: string;
  /** The clock of the server when the answer was made: the countdown is corrected with it. */
  serverTime: string;
  createdAt: string;
  trip: { trainNumber: string; trainName: string; routeName: string; departureDate: string; from: BookingStop; to: BookingStop };
  items: BookingItem[];
}
