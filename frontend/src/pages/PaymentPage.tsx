import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { CLASS_LABEL } from '../adminTypes';
import { gatewayGet, readError } from '../api';
import type { Booking } from '../bookingTypes';
import { connectLive } from '../live/connectLive';
import { formatRemaining, remainingMs, startCountdown, type Countdown } from '../countdown';
import { clock } from '../time';

/** What the seat map says when the five minutes ran out (SRS FR-H5). */
export const EXPIRED_MESSAGE = 'Your 5 minutes ran out. The seats were released, please select again.';
export const TRIP_CANCELLED_MESSAGE = 'This trip was cancelled.';

/** Passenger page (Flow 3, 4 and 5): the hold, its 5:00 countdown, and the payment. */
export function PaymentPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [booking, setBooking] = useState<Booking | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [left, setLeft] = useState<number | null>(null);
  const countdown = useRef<Countdown | null>(null);
  // Why the hold ended, when the server pushed it (the poll below only knows that it ended).
  const endedBecause = useRef<string | null>(null);

  const load = useCallback(async () => {
    const result = await gatewayGet(`/bookings/${id}`);
    if (result.status !== 200) {
      setError(readError(result).message);
      return;
    }
    const fresh = result.body as Booking;
    countdown.current = startCountdown(fresh.expiresAt, fresh.serverTime, Date.now());
    setBooking(fresh);
  }, [id]);

  useEffect(() => {
    void load();
  }, [load]);

  // The server pushes it when the hold ends (HOLD_EXPIRED, only to this user); the page then asks at once instead of waiting for the poll.
  const tripId = booking?.tripId;
  useEffect(() => {
    if (tripId === undefined) return undefined;
    return connectLive(tripId, {
      onUserEvent: (event) => {
        if (event.bookingId === Number(id)) {
          endedBecause.current = event.reason;
          void load();
        }
      },
      onConnected: (again) => {
        if (again) void load();
      },
    });
  }, [tripId, id, load]);

  // The server decides when the hold is over: this page asks it every two seconds while it is open, and when it reports that the
  // hold ended, goes back to the seat map with the right message. (Live pushes, when available, only make this faster.)
  useEffect(() => {
    if (!booking || booking.status !== 'PENDING_PAYMENT') return undefined;
    const timer = setInterval(() => void load(), 2000);
    return () => clearInterval(timer);
  }, [booking, load]);

  // The countdown on screen, from the server's expiry time. At 0:00 the payment form is disabled, nothing else happens here.
  useEffect(() => {
    if (!booking || booking.status !== 'PENDING_PAYMENT') return undefined;
    const tick = () => countdown.current && setLeft(remainingMs(countdown.current, Date.now()));
    tick();
    const timer = setInterval(tick, 250);
    return () => clearInterval(timer);
  }, [booking]);

  useEffect(() => {
    if (!booking) return;
    if (booking.status === 'EXPIRED' || booking.status === 'ABANDONED') {
      navigate(`/trips/${booking.tripId}/seats?from=${booking.fromStop}&to=${booking.toStop}`, {
        replace: true,
        state: { message: endedBecause.current === 'TRIP_CANCELLED' ? TRIP_CANCELLED_MESSAGE : EXPIRED_MESSAGE },
      });
    }
  }, [booking, navigate]);

  if (error) {
    return (
      <section>
        <h1>Payment</h1>
        <p className="notice notice-error">{error}</p>
        <p><Link to="/search">Back to the search</Link></p>
      </section>
    );
  }
  if (!booking) return <p className="muted">Loading your booking…</p>;

  const timeIsUp = left !== null && left <= 0;

  return (
    <section>
      {booking.status === 'PENDING_PAYMENT' && (
        <p><Link to={`/trips/${booking.tripId}/seats?from=${booking.fromStop}&to=${booking.toStop}`}>← Back to the seat map</Link> (your seats stay held)</p>
      )}
      <h1>{booking.status === 'CONFIRMED' ? 'Booking confirmed' : 'Payment'}</h1>
      <p>Booking reference <strong className="mono">{booking.reference}</strong></p>

      {booking.status === 'PENDING_PAYMENT' && (
        <p className={`countdown${timeIsUp ? ' countdown-over' : ''}`} role="timer" aria-live="off">
          <span className="muted">Time left to pay:</span> <strong>{formatRemaining(left ?? 5 * 60 * 1000)}</strong>
          {timeIsUp && <span> · Time is up. Checking with the server…</span>}
        </p>
      )}

      <div className="coach-card">
        <h2>
          {booking.trip.from.stationName} → {booking.trip.to.stationName}
        </h2>
        <p className="muted">
          {booking.trip.departureDate} · {clock(booking.trip.from.departureTime)} → {clock(booking.trip.to.arrivalTime)} ·{' '}
          <span className="mono">{booking.trip.trainNumber}</span> {booking.trip.trainName}
        </p>
        <ul className="booking-items">
          {booking.items.map((item, index) => (
            <li key={index}>
              {item.type === 'SEAT' ? `Seat ${item.seatNumber} · coach ${item.coachCode}` : `${item.qty} standing ${item.qty === 1 ? 'place' : 'places'} · coach ${item.coachCode}`}
              {' '}<span className="muted">({CLASS_LABEL[item.coachClass]})</span> <strong>{item.price.toFixed(2)}</strong> {booking.currency}
            </li>
          ))}
        </ul>
        <p className="booking-total">Total <strong>{booking.amount.toFixed(2)}</strong> {booking.currency}</p>
      </div>

      {booking.status === 'CONFIRMED' && <p className="notice notice-ok">Your booking is confirmed.</p>}
      {booking.status === 'PENDING_PAYMENT' && (
        <fieldset className="payment-box" disabled={timeIsUp}>
          <legend>Pay</legend>
          <p className="muted">The payment form appears here.</p>
        </fieldset>
      )}
    </section>
  );
}
