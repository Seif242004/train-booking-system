import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { CLASS_LABEL } from '../adminTypes';
import { BookingSteps } from '../components/BookingSteps';
import { CountUp } from '../components/CountUp';
import { HoldCard } from '../components/HoldCard';
import { StripeCheckout } from '../components/StripeCheckout';
import { PageNav } from '../components/PageNav';
import { gatewayGet, gatewayRequest, readError } from '../api';
import type { Booking, PaymentStart } from '../bookingTypes';
import { connectLive } from '../live/connectLive';
import { remainingMs, startCountdown, type Countdown } from '../countdown';
import { money } from '../format';
import { clock, dayLabel } from '../time';
import { lastSearchQuery } from '../recentSearches';

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
  // The payment (Stripe's form): asked from the server once when the page opens. 'waiting': Stripe took the payment (or it is being
  // processed) and the page waits for the server's confirmation, which comes from Stripe's signed webhook.
  const [payment, setPayment] = useState<{ state: 'idle' | 'loading' | 'ready' | 'waiting' | 'unavailable'; start?: PaymentStart; message?: string }>({ state: 'idle' });
  const paymentAsked = useRef(false);

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

  // "Continue to payment": the server checks the hold, works out the amount and returns the client secret for Stripe's form.
  const bookingStatus = booking?.status;
  useEffect(() => {
    if (bookingStatus !== 'PENDING_PAYMENT' || paymentAsked.current) return;
    paymentAsked.current = true;
    setPayment({ state: 'loading' });
    void (async () => {
      const result = await gatewayRequest('POST', `/bookings/${id}/payment`);
      if (result.status === 200) {
        setPayment({ state: 'ready', start: result.body as PaymentStart });
        return;
      }
      const failure = readError(result);
      if (failure.code === 'payment_in_progress' || failure.code === 'already_paid') {
        setPayment({ state: 'waiting' }); // paid (a bank page redirected back here) or being processed: the poll shows the result
      } else if (failure.code === 'hold_expired' || failure.code === 'payment_closed' || failure.code === 'not_a_hold') {
        void load(); // the hold ended: the poll below sends the user back to the seat map
      } else {
        setPayment({ state: 'unavailable', message: failure.message });
      }
    })();
  }, [bookingStatus, id, load]);

  useEffect(() => {
    if (!booking) return;
    // Paid: the ticket page replaces this one, so Back from the ticket does not return to a finished payment.
    if (booking.status === 'CONFIRMED') {
      navigate(`/bookings/${booking.id}/ticket`, { replace: true });
      return;
    }
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
        <BookingSteps current="payment" />
        <h1>Payment</h1>
        <p className="notice notice-error" role="alert">{error}</p>
        <p><Link to="/bookings">Go to my bookings</Link></p>
      </section>
    );
  }
  if (!booking) {
    return (
      <section aria-busy="true">
        <BookingSteps current="payment" />
        <p className="muted">Loading your booking…</p>
      </section>
    );
  }


  const seatsHref = `/trips/${booking.tripId}/seats?from=${booking.fromStop}&to=${booking.toStop}`;
  const searchHref = `/search${lastSearchQuery() ? `?${lastSearchQuery()}` : ''}`;
  const timeIsUp = left !== null && left <= 0;
  const holding = booking.status === 'PENDING_PAYMENT';
  const testMode = payment.start?.publishableKey?.startsWith('pk_test_') ?? false;

  return (
    <section className="payment-page">
      {holding && <PageNav back={{ to: seatsHref, label: 'Back to seats' }} />}
      <BookingSteps current="payment" links={{ search: searchHref, seats: seatsHref }} />
      <h1>Pay for your trip</h1>

      <div className="pay-layout">
        {holding && (
          <fieldset className="pay-card" disabled={timeIsUp}>
            <legend className="sr-only">Pay</legend>
            <div className="pay-card-head">
              <h2>Card details</h2>
              {testMode && <span className="status-chip is-warn">Test mode · no real charges</span>}
            </div>
            {payment.state === 'loading' && (
              <div aria-busy="true">
                <p className="muted">Preparing the payment form…</p>
                <div className="skeleton" style={{ height: 160 }} />
              </div>
            )}
            {payment.state === 'unavailable' && (
              <>
                <p className="notice notice-error" role="alert">{payment.message}</p>
                <button type="button" className="button" onClick={() => { paymentAsked.current = false; setPayment({ state: 'idle' }); void load(); }}>Try again</button>
              </>
            )}
            {payment.state === 'waiting' && (
              <>
                <p role="status" className="notice notice-ok">Your payment was received. Waiting for the confirmation…</p>
                {/* If the payment did not go through after all (a bank step failed), the form can be asked for again. */}
                <button type="button" className="button" onClick={() => { paymentAsked.current = false; setPayment({ state: 'idle' }); void load(); }}>
                  Pay again
                </button>
              </>
            )}
            {payment.state === 'ready' && payment.start && payment.start.publishableKey && (
              <StripeCheckout publishableKey={payment.start.publishableKey} clientSecret={payment.start.clientSecret}
                amountLabel={money(payment.start.amount, payment.start.currency)}
                returnUrl={`${window.location.origin}/bookings/${booking.id}/pay`} seatsHref={seatsHref} onSubmitted={() => setPayment({ state: 'waiting' })} />
            )}
            <p className="pay-note">Payments are processed by Stripe. Card details never touch our servers. Your booking is confirmed as soon as the payment succeeds.</p>
          </fieldset>
        )}

        <aside className="pay-summary" aria-label="Order summary">
          {holding && <HoldCard left={left} label="Seats held for" timeIsUp={timeIsUp} />}
          <div className="selection-card">
            <h2>Order summary</h2>
            <p className="summary-route">
              <span className="summary-clock">{clock(booking.trip.from.departureTime)}</span> <strong>{booking.trip.from.stationName}</strong>
              <span aria-hidden="true" className="trip-arrow"> → </span>
              <span className="summary-clock">{clock(booking.trip.to.arrivalTime)}</span> <strong>{booking.trip.to.stationName}</strong>
            </p>
            <p className="selection-sub">
              {dayLabel(booking.trip.departureDate)} · Train <span className="mono">{booking.trip.trainNumber}</span> {booking.trip.trainName}
            </p>
            <ul className="selection-list summary-items">
              {booking.items.map((item, index) => (
                <li key={index}>
                  <div className="selection-what">
                    <p className="selection-title">{item.type === 'SEAT' ? `Seat ${item.seatNumber}` : `${item.qty} standing ${item.qty === 1 ? 'place' : 'places'}`}</p>
                    <p className="selection-sub">{CLASS_LABEL[item.coachClass]} class · Coach {item.coachCode}</p>
                  </div>
                  <span className="selection-price">{money(item.price, booking.currency)}</span>
                </li>
              ))}
            </ul>
            <p className="selection-total">
              <span>Total</span>
              <strong><CountUp amount={booking.amount} currency={booking.currency} /></strong>
            </p>
            <p className="selection-count muted">
              Booking reference <strong className="mono">{booking.reference}</strong>
            </p>
          </div>
          {holding && (
            <Link className="link-button summary-back" to={seatsHref}>
              Back to seats <span className="muted">(your seats stay held)</span>
            </Link>
          )}
        </aside>
      </div>
    </section>
  );
}
