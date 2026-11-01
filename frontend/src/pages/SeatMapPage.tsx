import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { CLASS_LABEL } from '../adminTypes';
import { gatewayGet, gatewayRequest, readError, type GatewayResult } from '../api';
import type { Booking } from '../bookingTypes';
import { formatRemaining, remainingMs, startCountdown, type Countdown } from '../countdown';
import { applySeatEvent } from '../live/applySeatEvent';
import { connectLive } from '../live/connectLive';
import type { SeatMap, SeatMapCoach, SeatMapSeat, SeatState } from '../seatMapTypes';
import { clock } from '../time';
import { EXPIRED_MESSAGE, TRIP_CANCELLED_MESSAGE } from './PaymentPage';

const STATE_LABEL: Record<SeatState, string> = { AVAILABLE: 'Available', HELD: 'Held by someone', BOOKED: 'Booked' };
// A glyph next to the seat number, so the state never depends on colour alone (NFR-9).
const STATE_MARK: Record<SeatState, string> = { AVAILABLE: '', HELD: '◐', BOOKED: '✕' };

/** The seats of a coach as rows: seats with the same row number side by side, in column order. */
function rowsOf(coach: SeatMapCoach): SeatMapSeat[][] {
  const byRow = new Map<number, SeatMapSeat[]>();
  for (const seat of coach.seats) byRow.set(seat.row, [...(byRow.get(seat.row) ?? []), seat]);
  return [...byRow.entries()].sort((a, b) => a[0] - b[0]).map(([, seats]) => seats.sort((a, b) => a.column.localeCompare(b.column)));
}

/**
 * Passenger page (Flow 2 and Flow 3): the seat map of a trip for the journey the passenger searched. Picking a seat holds it at once;
 * the first pick starts the 5 minutes, shown here as a countdown, and later picks join the same hold without extending it.
 * Clicking a seat of your own releases it at once. "Continue to payment" opens the payment page with the time that is left.
 */
export function SeatMapPage() {
  const { id } = useParams();
  const [query] = useSearchParams();
  const from = query.get('from') ?? '';
  const to = query.get('to') ?? '';
  const navigate = useNavigate();
  // The payment page sends the user back here with a message when the hold ran out or the trip was cancelled (SRS FR-H5).
  const redirectMessage = (useLocation().state as { message?: string } | null)?.message ?? null;

  const [map, setMap] = useState<SeatMap | null>(null);
  // The live events are applied to the map as it is right now, also when several arrive before the page has drawn again.
  const mapRef = useRef<SeatMap | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(redirectMessage);
  // What is being sent right now (a seat id or "coach:<id>"): a second click on it waits for the answer.
  const [busy, setBusy] = useState<Set<string>>(new Set());
  const [left, setLeft] = useState<number | null>(null);
  const countdown = useRef<Countdown | null>(null);

  const load = useCallback(async () => {
    const result = await gatewayGet(`/trips/${id}/seat-map?from=${from}&to=${to}`);
    if (result.status === 200) {
      const fresh = result.body as SeatMap;
      mapRef.current = fresh;
      setMap(fresh);
      setError(null);
    } else {
      setError(readError(result).message);
    }
  }, [id, from, to]);

  useEffect(() => {
    void load();
  }, [load]);

  const hold = map?.myHold ?? null;
  const tripId = map?.tripId;
  const sameJourney = hold ? hold.fromStop === map?.journey.fromStop && hold.toStop === map?.journey.toStop : true;

  // The countdown of the hold, from the server's expiry time corrected with the server's clock (FR-H4). It only shows the time;
  // the server decides when the hold is over and tells this page (HOLD_EXPIRED) or the next reload.
  const expiresAt = hold?.expiresAt;
  const serverTime = hold?.serverTime;
  useEffect(() => {
    if (expiresAt === undefined || serverTime === undefined) {
      countdown.current = null;
      setLeft(null);
      return undefined;
    }
    countdown.current = startCountdown(expiresAt, serverTime, Date.now());
    let reloaded = false;
    const tick = () => {
      const remaining = countdown.current ? remainingMs(countdown.current, Date.now()) : null;
      setLeft(remaining);
      if (remaining === 0 && !reloaded) {
        reloaded = true;
        setTimeout(() => void load(), 1500); // the sweeper releases within seconds; then the map shows it
      }
    };
    tick();
    const timer = setInterval(tick, 250);
    return () => clearInterval(timer);
  }, [expiresAt, serverTime, load]);

  // Live updates (Flow 2): the changes of the trip arrive by WebSocket and the map is updated without a reload. After a reconnect,
  // or when an event was missed, the full map is loaded again (FR-R4). The end of the user's own hold (time ran out, trip cancelled)
  // is pushed to this user only.
  useEffect(() => {
    if (tripId === undefined) return undefined;
    return connectLive(tripId, {
      onSeatEvent: (event) => {
        const current = mapRef.current;
        if (!current) return;
        const result = applySeatEvent(current, event);
        if (result.kind === 'reload') {
          void load();
        } else if (result.kind === 'applied') {
          mapRef.current = result.map;
          setMap(result.map);
        }
      },
      onUserEvent: (event) => {
        if (event.reason === 'EXPIRED') setNotice(EXPIRED_MESSAGE);
        if (event.reason === 'TRIP_CANCELLED') setNotice(TRIP_CANCELLED_MESSAGE);
        void load();
      },
      onConnected: (again) => {
        if (again) void load();
      },
    });
  }, [tripId, load]);

  // What the user holds, as the seat map needs it.
  const mySeatIds = useMemo(() => new Set((hold?.items ?? []).filter((i) => i.type === 'SEAT').map((i) => i.seatId as number)), [hold]);
  const myStanding = useMemo(() => new Map((hold?.items ?? []).filter((i) => i.type === 'STANDING').map((i) => [i.coachId, i.qty])), [hold]);

  const withBusy = useCallback(async (key: string, action: () => Promise<void>) => {
    setBusy((current) => new Set(current).add(key));
    try {
      await action();
    } finally {
      setBusy((current) => {
        const next = new Set(current);
        next.delete(key);
        return next;
      });
    }
  }, []);

  /** Shows the hold as the server answered (an ended hold, for example after the last release, clears it) and the seat that changed. */
  function showHold(booking: Booking, changedSeat: { id: number; state: SeatState }) {
    const current = mapRef.current;
    if (!current) return;
    const coaches = current.coaches.map((coach) => ({
      ...coach,
      seats: coach.seats.map((seat) => (seat.id === changedSeat.id ? { ...seat, state: changedSeat.state } : seat)),
    }));
    const next = { ...current, coaches, myHold: booking.status === 'PENDING_PAYMENT' ? booking : null };
    mapRef.current = next;
    setMap(next);
  }

  /** Says what went wrong with a pick or a release, and reloads the map, which may be out of date. */
  async function explain(result: GatewayResult) {
    const failure = readError(result);
    if (failure.code === 'seats_unavailable') {
      // Only this pick failed; what is already held stays held.
      setProblem(`${failure.details.map((d) => d.message).join('; ') || failure.message}. Your other picks are still held.`);
    } else if (failure.code === 'hold_expired') {
      setNotice(EXPIRED_MESSAGE);
    } else {
      setProblem(failure.message);
    }
    await load();
  }

  const journey = map ? { tripId: map.tripId, fromStop: map.journey.fromStop, toStop: map.journey.toStop } : null;

  async function pickSeat(seat: SeatMapSeat) {
    if (!journey) return;
    setProblem(null);
    await withBusy(String(seat.id), async () => {
      const result = await gatewayRequest('POST', '/bookings/pick', { body: { ...journey, seatId: seat.id } });
      if (result.status === 200 || result.status === 201) showHold(result.body as Booking, { id: seat.id, state: 'HELD' });
      else await explain(result);
    });
  }

  async function releaseSeat(seat: SeatMapSeat) {
    if (!hold) return;
    setProblem(null);
    await withBusy(String(seat.id), async () => {
      const result = await gatewayRequest('POST', `/bookings/${hold.id}/release`, { body: { seatId: seat.id } });
      if (result.status === 200) showHold(result.body as Booking, { id: seat.id, state: 'AVAILABLE' });
      else await explain(result);
    });
  }

  async function changeStanding(coach: SeatMapCoach, delta: 1 | -1) {
    if (!journey) return;
    setProblem(null);
    await withBusy(`coach:${coach.id}`, async () => {
      const result = delta === 1
        ? await gatewayRequest('POST', '/bookings/pick', { body: { ...journey, coachId: coach.id, qty: 1 } })
        : await gatewayRequest('POST', `/bookings/${hold?.id}/release`, { body: { coachId: coach.id, qty: 1 } });
      if (result.status === 200 || result.status === 201) await load(); // the standing counters belong to the server: show them as they are
      else await explain(result);
    });
  }

  async function releaseAll() {
    if (!hold) return;
    setProblem(null);
    const result = await gatewayRequest('DELETE', `/bookings/${hold.id}/hold`);
    if (result.status === 200) await load();
    else await explain(result);
  }

  if (error) {
    return (
      <section>
        <h1>Seat map</h1>
        <p className="notice notice-error">{error}</p>
        <p><Link to="/search">Back to the search</Link></p>
      </section>
    );
  }
  if (!map) return <p className="muted">Loading the seat map…</p>;

  const closed = map.status === 'CANCELLED' || map.tripStatus !== 'OPEN';
  const timeIsUp = left !== null && left <= 0;
  const canPick = !closed && sameJourney && !timeIsUp;
  const itemCount = hold ? hold.items.reduce((sum, item) => sum + item.qty, 0) : 0;
  const priceOf = (coachClass: SeatMapCoach['class']) => map.fares[coachClass] ?? 0;

  return (
    <section>
      <p><Link to="/search">← Back to the search</Link></p>
      <h1>Choose your seats</h1>
      <p className="seatmap-journey">
        <strong>{map.journey.from?.stationName}</strong> {clock(map.journey.departureTime)} → <strong>{map.journey.to?.stationName}</strong>{' '}
        {clock(map.journey.arrivalTime)} · {map.departureDate} · <span className="mono">{map.train.number}</span> {map.train.name}
      </p>
      {notice && <p className="notice notice-error" role="alert">{notice}</p>}
      {problem && <p className="notice notice-error" role="alert">{problem}</p>}
      {closed && <p className="notice notice-error">This trip is closed: it is cancelled or being cancelled, so seats cannot be held.</p>}
      {hold && !sameJourney && (
        <p className="notice notice-error">
          You are holding seats on this trip for another journey ({hold.trip.from.stationName} to {hold.trip.to.stationName}).{' '}
          <Link to={`/trips/${map.tripId}/seats?from=${hold.fromStop}&to=${hold.toStop}`}>Go to that journey</Link> or{' '}
          <button type="button" className="button" onClick={() => void releaseAll()}>release them</button> to choose here.
        </p>
      )}
      {!hold && !closed && <p className="muted">Pick a seat to hold it. Your 5 minutes start with the first seat you pick.</p>}

      <ul className="seatmap-legend" aria-label="Legend">
        <li><span className="seat seat-legend">1A</span> Available</li>
        <li><span className="seat seat-legend seat-selected">1A ✓</span> Yours (held)</li>
        <li><span className="seat seat-legend seat-held">1A ◐</span> Held by someone</li>
        <li><span className="seat seat-legend seat-booked">1A ✕</span> Booked</li>
      </ul>

      {map.coaches.map((coach) => (
        <div key={coach.id} className="coach-card">
          <h2>
            Coach {coach.code} <span className="muted">· {CLASS_LABEL[coach.class]} class · {priceOf(coach.class).toFixed(2)} EGP per seat for this journey</span>
          </h2>
          {coach.seats.length === 0 ? (
            <p className="muted">This coach has no seats.</p>
          ) : (
            <div className="coach-rows" role="group" aria-label={`Seats of coach ${coach.code}`}>
              {rowsOf(coach).map((row) => (
                <div key={row[0].row} className="coach-row">
                  {row.map((seat) => {
                    const mine = mySeatIds.has(seat.id);
                    const state: SeatState = seat.state;
                    const clickable = mine ? sameJourney && !timeIsUp : canPick && state === 'AVAILABLE';
                    return (
                      <button key={seat.id} type="button"
                        className={`seat ${mine ? 'seat-selected' : state === 'HELD' ? 'seat-held' : state === 'BOOKED' ? 'seat-booked' : ''}`}
                        disabled={!clickable || busy.has(String(seat.id))}
                        aria-pressed={mine}
                        aria-label={`Seat ${seat.seatNumber}, ${mine ? 'yours, held. Press to release it' : STATE_LABEL[state]}`}
                        onClick={() => void (mine ? releaseSeat(seat) : pickSeat(seat))}>
                        {seat.seatNumber} {mine ? '✓' : STATE_MARK[state]}
                      </button>
                    );
                  })}
                </div>
              ))}
            </div>
          )}
          {coach.standing.capacity > 0 && (
            <div className="standing-row">
              <span>Standing places: <strong>{coach.standing.remaining}</strong> of {coach.standing.capacity} left</span>
              <span className="stepper">
                <button type="button" className="button" aria-label={`Give back a standing place in coach ${coach.code}`}
                  disabled={!sameJourney || timeIsUp || (myStanding.get(coach.id) ?? 0) === 0 || busy.has(`coach:${coach.id}`)}
                  onClick={() => void changeStanding(coach, -1)}>−</button>
                <output aria-label={`Your standing places in coach ${coach.code}`}>{myStanding.get(coach.id) ?? 0}</output>
                <button type="button" className="button" aria-label={`Take a standing place in coach ${coach.code}`}
                  disabled={!canPick || coach.standing.remaining === 0 || busy.has(`coach:${coach.id}`)}
                  onClick={() => void changeStanding(coach, 1)}>+</button>
              </span>
            </div>
          )}
        </div>
      ))}

      <div className="selection-bar">
        {hold && sameJourney ? (
          <>
            <span>
              <span className={`countdown${timeIsUp ? ' countdown-over' : ''}`} role="timer" aria-live="off">
                <span className="muted">Time left:</span> <strong>{formatRemaining(left ?? 5 * 60 * 1000)}</strong>
              </span>
              {' '}· {itemCount} held · <strong>{hold.amount.toFixed(2)}</strong> {hold.currency}
              {timeIsUp && <span> · Time is up. Checking with the server…</span>}
            </span>
            <span className="actions">
              <button type="button" className="button" onClick={() => void releaseAll()}>Release all</button>
              <button type="button" className="button button-primary" disabled={timeIsUp} onClick={() => navigate(`/bookings/${hold.id}/pay`)}>
                Continue to payment
              </button>
            </span>
          </>
        ) : (
          <span>Nothing held yet.</span>
        )}
      </div>
    </section>
  );
}
