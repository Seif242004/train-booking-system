import type { CoachClass } from './adminTypes';

export type BookingStatus = 'PENDING_PAYMENT' | 'CONFIRMED' | 'EXPIRED' | 'CANCELLED' | 'ABANDONED';
export type RefundStatus = 'NONE' | 'REFUND_PENDING' | 'REFUNDED' | 'REFUND_FAILED' | 'NOT_APPLICABLE' | 'MANUAL_REFUND_DUE' | 'REFUNDED_AT_COUNTER';

/** How a booking was paid: by card (Stripe), at the counter, or not at all (complimentary). */
export type PaymentMethod = 'CARD' | 'COUNTER' | 'COMPLIMENTARY';

export const METHOD_LABEL: Record<PaymentMethod, string> = { CARD: 'Card', COUNTER: 'Counter', COMPLIMENTARY: 'Complimentary' };
/** The words a passenger sees on a ticket and in My bookings. */
export const METHOD_PAID_LABEL: Record<PaymentMethod, string> = { CARD: 'Paid by card', COUNTER: 'Paid at counter', COMPLIMENTARY: 'Complimentary' };

/** The audit of an admin-issued booking (docs/design/COUNTER-BOOKING.md); null for a card booking. */
export interface IssueAudit {
  method: PaymentMethod;
  chargedAmount: number | null;
  issuedBy: string;
  issuedAt: string;
  note: string | null;
  walkInName: string | null;
  walkInPhone: string | null;
  walkInEmail: string | null;
  counterRefundedBy: string | null;
  counterRefundedAt: string | null;
}

/** A booking as support sees it (GET /admin/bookings). */
export interface AdminBooking {
  booking: Booking;
  userId: string;
  userEmail: string;
  userName: string | null;
  paymentStatus: 'PAID' | 'NOT_PAID';
  confirmedAt: string | null;
  cancelledAt: string | null;
  cancelledBy: string | null;
  cancelledByRole: 'USER' | 'ADMIN' | null;
  cancelReason: string | null;
  cancellable: boolean;
  issue: IssueAudit | null;
}

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
  /** CARD for every booking that was paid through Stripe. */
  paymentMethod?: PaymentMethod;
}
