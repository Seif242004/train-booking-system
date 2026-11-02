import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { CLASS_LABEL } from '../adminTypes';
import { gatewayGet, gatewayRequest, readError } from '../api';
import type { Booking, BookingItem, BookingStatus, RefundStatus } from '../bookingTypes';
import { remainingMs, startCountdown } from '../countdown';
import { money } from '../format';
import { clock, dayLabel } from '../time';
import { useDelayedFlag } from '../useDelayedFlag';

interface MyBooking {
  booking: Booking;
  /** A confirmed booking whose train has not yet left the boarding stop. The server checks again when the cancellation arrives. */
  cancellable: boolean;
}

type Tab = 'upcoming' | 'past';

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

const REFUND_TONE: Record<RefundStatus, string> = { NONE: '', REFUND_PENDING: 'is-warn', REFUNDED: 'is-ok', REFUND_FAILED: 'is-danger' };

/** The reasons a cancellation can be refused, in words for the passenger. */
function cancelProblem(code: string, fallback: string): string {
  if (code === 'trip_departed') return 'The train has already left your boarding stop, so this booking can no longer be cancelled.';
  if (code === 'not_cancellable') return 'Only a confirmed booking can be cancelled.';
  return fallback;
}

/** The seats of a booking written out per coach: "First class · Coach A1 · Seats 1A, 1B", "Second class · Coach B · 2 standing places". */
function describeItems(items: BookingItem[]): string[] {
  const byCoach = new Map<number, BookingItem[]>();
  for (const item of items) byCoach.set(item.coachId, [...(byCoach.get(item.coachId) ?? []), item]);
  return [...byCoach.values()].map((group) => {
    const seats = group.filter((i) => i.type === 'SEAT').map((i) => i.seatNumber);
    const standing = group.filter((i) => i.type === 'STANDING').reduce((sum, i) => sum + i.qty, 0);
    const what = [
      seats.length > 0 ? `${seats.length === 1 ? 'Seat' : 'Seats'} ${seats.join(', ')}` : '',
      standing > 0 ? `${standing} standing ${standing === 1 ? 'place' : 'places'}` : '',
    ].filter(Boolean).join(' + ');
    return `${CLASS_LABEL[group[0].coachClass]} class · Coach ${group[0].coachCode} · ${what}`;
  });
}

function departureOf(booking: Booking): number {
  return booking.trip.from.departureTime ? Date.parse(booking.trip.from.departureTime) : 0;
}

/**
 * Passenger page (Flow 6, way A): the passenger's holds, confirmed and cancelled bookings. A confirmed booking whose train has not left can
 * be cancelled here: instant, no approval, refunded in full. The ticket can be emailed again. A hold that is still running offers
 * "Continue to payment". "Upcoming" holds the running holds and the confirmed bookings whose train has not left; everything else is
 * "Past and cancelled".
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
  const [tab, setTab] = useState<Tab>('upcoming');
  const showSkeleton = useDelayedFlag(items === null && error === null);

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

  if (error) {
    return (
      <section className="bookings-page">
        <h1>My bookings</h1>
        <p className="notice notice-error" role="alert">{error}</p>
      </section>
    );
  }
  if (!items) {
    return (
      <section className="bookings-page" aria-busy="true">
        <h1>My bookings</h1>
        <p className="sr-only">Loading your bookings…</p>
        {showSkeleton && (
          <ul className="booking-list" aria-label="Loading your bookings">
            {[0, 1, 2].map((n) => (
              <li key={n} className="booking-card booking-card-skeleton">
                <div className="skeleton" style={{ height: 26, width: 140 }} />
                <div className="skeleton" style={{ height: 28, width: '60%', marginTop: 18 }} />
                <div className="skeleton" style={{ height: 16, width: '45%', marginTop: 12 }} />
              </li>
            ))}
          </ul>
        )}
      </section>
    );
  }

  const now = Date.now();
  const isRunningHold = (booking: Booking) => booking.status === 'PENDING_PAYMENT' && remainingMs(startCountdown(booking.expiresAt, booking.serverTime, loadedAt), now) > 0;
  const isUpcoming = ({ booking }: MyBooking) => isRunningHold(booking) || (booking.status === 'CONFIRMED' && departureOf(booking) > now);
  const upcoming = items.filter(isUpcoming).sort((a, b) => departureOf(a.booking) - departureOf(b.booking));
  const past = items.filter((entry) => !isUpcoming(entry)).sort((a, b) => departureOf(b.booking) - departureOf(a.booking));
  const shown = tab === 'upcoming' ? upcoming : past;

  return (
    <section className="bookings-page">
      <h1>My bookings</h1>

      <div role="group" aria-label="Filter bookings" className="booking-tabs">
        <button type="button" className="chip" aria-pressed={tab === 'upcoming'} onClick={() => { setTab('upcoming'); setConfirming(null); }}>
          Upcoming <span className="tab-count">{upcoming.length}</span>
        </button>
        <button type="button" className="chip" aria-pressed={tab === 'past'} onClick={() => { setTab('past'); setConfirming(null); }}>
          Past and cancelled <span className="tab-count">{past.length}</span>
        </button>
      </div>

      {shown.length === 0 ? (
        <div className="empty-state">
          {tab === 'upcoming' ? (
            <>
              <h2>No upcoming bookings</h2>
              <p>When you book a train it shows up here, with its ticket and the option to cancel.</p>
              <Link className="button button-primary" to="/search">Search trains</Link>
            </>
          ) : (
            <>
              <h2>Nothing here yet</h2>
              <p>Journeys you have taken, and bookings you cancelled, are listed here.</p>
            </>
          )}
        </div>
      ) : (
        <ul className="booking-list" key={tab}>
          {shown.map(({ booking, cancellable }, index) => {
            const trip = booking.trip;
            const holdRunning = isRunningHold(booking);
            const departed = booking.status === 'CONFIRMED' && departureOf(booking) <= now;
            const statusText = departed ? 'Completed' : STATUS_LABEL[booking.status];
            const statusTone = booking.status === 'CONFIRMED' && !departed ? 'is-ok' : booking.status === 'PENDING_PAYMENT' && holdRunning ? 'is-warn' : '';
            const refund = REFUND_LABEL[booking.refundStatus];
            const route = `${trip.from.stationName} → ${trip.to.stationName}`;
            return (
              <li key={booking.id} className="booking-card" style={{ ['--i' as string]: index }}>
                <div className="booking-card-top">
                  <div className="booking-chips">
                    <span className={`status-chip ${statusTone}`}>{statusText}</span>
                    {refund && <span className={`status-chip ${REFUND_TONE[booking.refundStatus]}`}>{refund}</span>}
                  </div>
                  <span className="booking-ref">Reference <span className="mono">{booking.reference}</span></span>
                </div>

                <div className="booking-card-main">
                  <div>
                    <h2>{route}</h2>
                    <p className="booking-when">
                      {dayLabel(trip.departureDate)} · {clock(trip.from.departureTime)} → {clock(trip.to.arrivalTime)} · Train <span className="mono">{trip.trainNumber}</span> {trip.trainName}
                    </p>
                    {describeItems(booking.items).map((line) => (
                      <p key={line} className="booking-items-line">{line}</p>
                    ))}
                  </div>
                  <p className="booking-price">{money(booking.amount, booking.currency)}</p>
                </div>

                {holdRunning && (
                  <div className="booking-actions">
                    <Link className="button button-primary button-compact" to={`/bookings/${booking.id}/pay`}>Continue to payment</Link>
                    <span className="muted">Your seats are held for a few minutes.</span>
                  </div>
                )}

                {booking.status === 'CONFIRMED' && (
                  <div className="booking-actions">
                    <Link className="button" to={`/bookings/${booking.id}/pay`}>View ticket</Link>
                    <button type="button" className="button" disabled={busy === booking.id} onClick={() => void resend(booking.id)}>Email my ticket again</button>
                    {cancellable && confirming !== booking.id && (
                      <button type="button" className="button button-danger" disabled={busy === booking.id} onClick={() => setConfirming(booking.id)}>Cancel booking</button>
                    )}
                    {!cancellable && !departed && <span className="muted">The train has left your boarding stop: this booking can no longer be cancelled.</span>}
                  </div>
                )}

                {confirming === booking.id && (
                  <div className="cancel-confirm" role="alertdialog" aria-label="Confirm cancellation">
                    <p className="cancel-confirm-title">Cancel {route} on {dayLabel(trip.departureDate)}?</p>
                    <p>
                      Your seats are released right away and the whole amount, {money(booking.amount, booking.currency)}, is refunded to the card you paid with. Banks
                      usually show it within a few days. This cannot be undone.
                    </p>
                    <div className="booking-actions">
                      <button type="button" className="button button-danger-solid" disabled={busy === booking.id} onClick={() => void cancel(booking.id)}>
                        {busy === booking.id ? 'Cancelling…' : 'Cancel booking and refund'}
                      </button>
                      <button type="button" className="button" disabled={busy === booking.id} onClick={() => setConfirming(null)}>Keep booking</button>
                    </div>
                  </div>
                )}

                {message && message.bookingId === booking.id && (
                  <p className={`notice ${message.ok ? 'notice-ok' : 'notice-error'} booking-message`} role="status">{message.text}</p>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
