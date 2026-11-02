import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { CLASS_LABEL } from '../adminTypes';
import { gatewayGet, gatewayRequest, readError } from '../api';
import type { Booking, BookingStatus, RefundStatus } from '../bookingTypes';
import { remainingMs, startCountdown } from '../countdown';
import { clock } from '../time';

interface MyBooking {
  booking: Booking;
  /** A confirmed booking whose train has not yet left the boarding stop. The server checks again when the cancellation arrives. */
  cancellable: boolean;
}

const STATUS_LABEL: Record<BookingStatus, string> = {
  PENDING_PAYMENT: 'Waiting for payment',
  CONFIRMED: 'Confirmed',
  CANCELLED: 'Cancelled',
  EXPIRED: 'Expired',
  ABANDONED: 'Released',
};

const REFUND_LABEL: Record<RefundStatus, string> = {
  NONE: '',
  REFUND_PENDING: 'Refund in progress',
  REFUNDED: 'Refunded in full',
  REFUND_FAILED: 'Refund failed: contact support',
};

/** The reasons a cancellation can be refused, in words for the passenger. */
function cancelProblem(code: string, fallback: string): string {
  if (code === 'trip_departed') return 'The train has already left your boarding stop, so this booking can no longer be cancelled.';
  if (code === 'not_cancellable') return 'Only a confirmed booking can be cancelled.';
  return fallback;
}

/**
 * Passenger page (Flow 6, way A): the passenger's holds, confirmed and cancelled bookings. A confirmed booking whose train has not left can
 * be cancelled here: instant, no approval, refunded in full. The ticket can be emailed again. A hold that is still running offers
 * "Continue to payment".
 */
export function MyBookingsPage() {
  const [items, setItems] = useState<MyBooking[] | null>(null);
  // When the list arrived: with the server time inside each booking it corrects a browser clock that is off (as the payment page does).
  const [loadedAt, setLoadedAt] = useState(0);
  const [error, setError] = useState<string | null>(null);
  // The booking whose cancellation is being confirmed, and the one being sent right now.
  const [confirming, setConfirming] = useState<number | null>(null);
  const [busy, setBusy] = useState<number | null>(null);
  const [message, setMessage] = useState<{ bookingId: number; text: string; ok: boolean } | null>(null);

  const load = useCallback(async () => {
    const result = await gatewayGet('/bookings');
    if (result.status === 200) {
      setItems(result.body as MyBooking[]);
      setLoadedAt(Date.now());
      setError(null);
    } else {
      setError(readError(result).message);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function cancel(id: number) {
    setBusy(id);
    setMessage(null);
    const result = await gatewayRequest('POST', `/bookings/${id}/cancel`);
    setBusy(null);
    setConfirming(null);
    if (result.status === 200) {
      setMessage({ bookingId: id, ok: true, text: 'The booking is cancelled and the seats are free again. The whole amount is being refunded; we will email you.' });
    } else {
      const failure = readError(result);
      setMessage({ bookingId: id, ok: false, text: cancelProblem(failure.code, failure.message) });
    }
    await load();
  }

  async function resend(id: number) {
    setBusy(id);
    setMessage(null);
    const result = await gatewayRequest('POST', `/bookings/${id}/resend-ticket`);
    setBusy(null);
    setMessage(result.status === 202
      ? { bookingId: id, ok: true, text: 'The ticket is on its way to your email.' }
      : { bookingId: id, ok: false, text: readError(result).message });
  }

  if (error) return <section><h1>My bookings</h1><p className="notice notice-error">{error}</p></section>;
  if (!items) return <p className="muted">Loading your bookings…</p>;

  return (
    <section>
      <h1>My bookings</h1>
      {items.length === 0 && <p className="muted">You have no bookings yet. <Link to="/search">Search trips</Link></p>}
      {items.map(({ booking, cancellable }) => {
        const trip = booking.trip;
        const holdRunning = booking.status === 'PENDING_PAYMENT' && remainingMs(startCountdown(booking.expiresAt, booking.serverTime, loadedAt), Date.now()) > 0;
        return (
          <div key={booking.id} className="coach-card booking-card">
            <h2>
              {trip.from.stationName} → {trip.to.stationName}{' '}
              <span className={`badge${booking.status === 'CONFIRMED' ? ' badge-ok' : ''}`}>{STATUS_LABEL[booking.status]}</span>
            </h2>
            <p className="muted">
              {trip.departureDate} · {clock(trip.from.departureTime)} → {clock(trip.to.arrivalTime)} · <span className="mono">{trip.trainNumber}</span> {trip.trainName} ·
              reference <strong className="mono">{booking.reference}</strong>
            </p>
            <ul className="booking-items">
              {booking.items.map((item, index) => (
                <li key={index}>
                  {item.type === 'SEAT' ? `Seat ${item.seatNumber} · coach ${item.coachCode}` : `${item.qty} standing ${item.qty === 1 ? 'place' : 'places'} · coach ${item.coachCode}`}
                  {' '}<span className="muted">({CLASS_LABEL[item.coachClass]})</span>
                </li>
              ))}
            </ul>
            <p className="booking-total">
              Total <strong>{booking.amount.toFixed(2)}</strong> {booking.currency}
              {REFUND_LABEL[booking.refundStatus] && <span className="muted"> · {REFUND_LABEL[booking.refundStatus]}</span>}
            </p>

            {holdRunning && <p><Link className="button button-primary" to={`/bookings/${booking.id}/pay`}>Continue to payment</Link> <span className="muted">Your seats are held for a few minutes.</span></p>}

            {booking.status === 'CONFIRMED' && (
              <p className="actions">
                <button type="button" className="button" disabled={busy === booking.id} onClick={() => void resend(booking.id)}>Email my ticket again</button>
                {cancellable && confirming !== booking.id && (
                  <button type="button" className="button" disabled={busy === booking.id} onClick={() => setConfirming(booking.id)}>Cancel booking</button>
                )}
                {!cancellable && <span className="muted">The train has left your boarding stop: this booking can no longer be cancelled.</span>}
              </p>
            )}

            {confirming === booking.id && (
              <div className="notice notice-error" role="alertdialog" aria-label="Confirm cancellation">
                <p>Cancel this booking? Your seats are given up and the whole amount, {booking.amount.toFixed(2)} {booking.currency}, is refunded. This cannot be undone.</p>
                <p className="actions">
                  <button type="button" className="button button-primary" disabled={busy === booking.id} onClick={() => void cancel(booking.id)}>
                    {busy === booking.id ? 'Cancelling…' : 'Yes, cancel and refund'}
                  </button>
                  <button type="button" className="button" disabled={busy === booking.id} onClick={() => setConfirming(null)}>Keep my booking</button>
                </p>
              </div>
            )}

            {message && message.bookingId === booking.id && (
              <p className={`notice ${message.ok ? 'notice-ok' : 'notice-error'}`} role="status">{message.text}</p>
            )}
          </div>
        );
      })}
    </section>
  );
}
