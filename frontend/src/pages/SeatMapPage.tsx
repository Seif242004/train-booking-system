import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { CLASS_LABEL, COACH_CLASSES, type CoachClass } from '../adminTypes';
import { gatewayGet, gatewayRequest, readError, type GatewayResult } from '../api';
import type { Booking } from '../bookingTypes';
import { BookingSteps } from '../components/BookingSteps';
import { HoldCard } from '../components/HoldCard';
import { SeatShape, type SeatLook } from '../components/SeatShape';
import { TrainStrip } from '../components/TrainStrip';
import { remainingMs, startCountdown, formatRemaining, type Countdown } from '../countdown';
import { money } from '../format';
import { applySeatEvent } from '../live/applySeatEvent';
import { connectLive } from '../live/connectLive';
import type { SeatMap, SeatMapCoach, SeatMapSeat, SeatState } from '../seatMapTypes';
import { clock, dayLabel, duration } from '../time';
import { useDelayedFlag } from '../useDelayedFlag';
import { lastSearchQuery } from '../recentSearches';
import { EXPIRED_MESSAGE, TRIP_CANCELLED_MESSAGE } from './PaymentPage';

const STATE_LABEL: Record<SeatState, string> = { AVAILABLE: 'Available', HELD: 'Held by someone', BOOKED: 'Booked' };
// How long a seat that someone else just took stays highlighted.
const FLASH_MS = 1600;

/** Where a seat sits, for the tooltip and the screen reader: the outer columns touch a window, the ones next to the aisle are aisle seats. */
function positionOf(plan: { left: string[]; right: string[] }, column: string): string {
  const side = plan.left.includes(column) ? plan.left : plan.right;
  const index = side.indexOf(column);
  const outer = side === plan.left ? index === 0 : index === side.length - 1;
  const inner = side === plan.left ? index === side.length - 1 : index === 0;
  return outer ? 'window' : inner ? 'aisle' : 'middle';
}

/** The seats whose state differs between two maps. */
function changedSeatIds(before: SeatMap, after: SeatMap): number[] {
  const old = new Map(before.coaches.flatMap((c) => c.seats).map((seat) => [seat.id, seat.state]));
  return after.coaches.flatMap((c) => c.seats).filter((seat) => old.get(seat.id) !== seat.state).map((seat) => seat.id);
}

/**
 * The plan of a coach: one line per seat row, the seat columns split into a left and a right side around the aisle (two and two, or two
 * and one, or whatever the coach has). A column that has no seat in some row is left empty, so the aisle stays straight.
 */
function planOf(coach: SeatMapCoach): { left: string[]; right: string[]; rows: { row: number; seats: Map<string, SeatMapSeat> }[] } {
  const columns = [...new Set(coach.seats.map((seat) => seat.column))].sort((a, b) => a.localeCompare(b));
  const leftCount = Math.ceil(columns.length / 2);
  const byRow = new Map<number, Map<string, SeatMapSeat>>();
  for (const seat of coach.seats) {
    const seats = byRow.get(seat.row) ?? new Map<string, SeatMapSeat>();
    seats.set(seat.column, seat);
    byRow.set(seat.row, seats);
  }
  return {
    left: columns.slice(0, leftCount),
    right: columns.slice(leftCount),
    rows: [...byRow.entries()].sort((a, b) => a[0] - b[0]).map(([row, seats]) => ({ row, seats })),
  };
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
  const classFromAddress = query.get('class');
  const navigate = useNavigate();
  const searchHref = `/search${lastSearchQuery() ? `?${lastSearchQuery()}` : ''}`;
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
  // Seats that someone else just took (shown with a brief highlight, never a jump) and the sentence for screen readers.
  const [flash, setFlash] = useState<Set<number>>(new Set());
  const [liveNote, setLiveNote] = useState('');
  const mineRef = useRef<Set<number>>(new Set());
  const [left, setLeft] = useState<number | null>(null);
  const countdown = useRef<Countdown | null>(null);
  // What the passenger chose to look at: a class tab and a coach chip. Null until they choose; then a sensible default is used.
  const [chosenClass, setChosenClass] = useState<CoachClass | null>(null);
  const [chosenCoach, setChosenCoach] = useState<number | null>(null);
  const showSkeleton = useDelayedFlag(map === null && error === null);

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
          const taken = changedSeatIds(current, result.map).filter((seatId) => !mineRef.current.has(seatId));
          mapRef.current = result.map;
          setMap(result.map);
          if (taken.length > 0) {
            setFlash((now) => new Set([...now, ...taken]));
            const names = result.map.coaches.flatMap((c) => c.seats).filter((seat) => taken.includes(seat.id)).map((seat) => seat.seatNumber);
            setLiveNote(`Seat ${names.join(', ')} just changed.`);
            window.setTimeout(() => setFlash((now) => new Set([...now].filter((seatId) => !taken.includes(seatId)))), FLASH_MS);
          }
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
  mineRef.current = mySeatIds;
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

  /** The remove button of a standing line in the selection list: gives back all the places of that coach at once. */
  async function releaseStanding(coachId: number, qty: number) {
    if (!hold) return;
    setProblem(null);
    await withBusy(`coach:${coachId}`, async () => {
      const result = await gatewayRequest('POST', `/bookings/${hold.id}/release`, { body: { coachId, qty } });
      if (result.status === 200) await load();
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
        <BookingSteps current="seats" links={{ search: searchHref }} />
        <h1>Choose your seats</h1>
        <p className="notice notice-error" role="alert">{error}</p>
        <p><Link to="/search">Back to the search</Link></p>
      </section>
    );
  }
  if (!map) {
    return (
      <section aria-busy="true">
        <BookingSteps current="seats" links={{ search: searchHref }} />
        <p className="sr-only">Loading the seat map…</p>
        {showSkeleton && (
          <>
            <div className="skeleton" style={{ height: 92, borderRadius: 20 }} />
            <div className="seatmap-layout">
              <div className="skeleton" style={{ flex: '1 1 520px', height: 520, borderRadius: 24 }} />
              <div className="skeleton" style={{ flex: '1 1 320px', maxWidth: 420, height: 320, borderRadius: 20 }} />
            </div>
          </>
        )}
      </section>
    );
  }

  const closed = map.status === 'CANCELLED' || map.tripStatus !== 'OPEN';
  const timeIsUp = left !== null && left <= 0;
  const canPick = !closed && sameJourney && !timeIsUp;
  const holding = hold !== null && sameJourney;
  const itemCount = hold ? hold.items.reduce((sum, item) => sum + item.qty, 0) : 0;
  const priceOf = (coachClass: SeatMapCoach['class']) => map.fares[coachClass] ?? 0;

  // Which class tab and coach chip are shown: the passenger's choice, else the class asked for in the address (the fare tile they came
  // from), else the class of what they already hold, else the first class that still has room.
  const classes = COACH_CLASSES.filter((c) => map.coaches.some((coach) => coach.class === c));
  const heldClass = hold?.items[0]?.coachClass;
  const roomIn = (c: CoachClass) => (map.summary.seatsLeft[c] ?? 0) + (map.summary.standingLeft[c] ?? 0) > 0;
  const shownClass: CoachClass | undefined =
    (chosenClass && classes.includes(chosenClass) ? chosenClass : undefined) ??
    classes.find((c) => c === classFromAddress) ??
    (heldClass && classes.includes(heldClass) ? heldClass : undefined) ??
    classes.find(roomIn) ??
    classes[0];
  const coachesOfClass = map.coaches.filter((coach) => coach.class === shownClass);
  const hasRoom = (coach: SeatMapCoach) => coach.seats.some((seat) => seat.state === 'AVAILABLE') || coach.standing.remaining > 0;
  const heldInCoach = (coach: SeatMapCoach) => coach.seats.some((seat) => mySeatIds.has(seat.id)) || (myStanding.get(coach.id) ?? 0) > 0;
  const shownCoach =
    coachesOfClass.find((coach) => coach.id === chosenCoach) ?? coachesOfClass.find(heldInCoach) ?? coachesOfClass.find(hasRoom) ?? coachesOfClass[0];
  const plan = shownCoach ? planOf(shownCoach) : null;
  const stops = map.journey.toStop - map.journey.fromStop + 1;

  function seatButton(seat: SeatMapSeat | undefined, key: string) {
    if (!seat || !plan) return <span key={key} className="plan-seat-empty" aria-hidden="true" />;
    const mine = mySeatIds.has(seat.id);
    const state: SeatState = seat.state;
    const clickable = mine ? sameJourney && !timeIsUp : canPick && state === 'AVAILABLE';
    const look: SeatLook = mine ? 'mine' : state === 'HELD' ? 'held' : state === 'BOOKED' ? 'booked' : 'available';
    const where = positionOf(plan, seat.column);
    const detail = `${where} seat, ${CLASS_LABEL[seat.class]} class, ${money(priceOf(seat.class))}`;
    return (
      <button key={key} type="button"
        className={`plan-seat is-${look}${flash.has(seat.id) ? ' is-flash' : ''}`}
        disabled={!clickable || busy.has(String(seat.id))}
        aria-pressed={mine}
        aria-label={`Seat ${seat.seatNumber}, ${detail}, ${mine ? 'yours, held. Press to release it' : STATE_LABEL[state]}`}
        onClick={() => void (mine ? releaseSeat(seat) : pickSeat(seat))}>
        <SeatShape look={look} />
        <span className="plan-seat-number" aria-hidden="true">{seat.seatNumber}</span>
        <span className="seat-tip" aria-hidden="true">
          Seat {seat.seatNumber} · {where} · {CLASS_LABEL[seat.class]} · {money(priceOf(seat.class))}
          {mine ? ' · yours' : look === 'held' ? ' · held' : look === 'booked' ? ' · taken' : ''}
        </span>
      </button>
    );
  }

  const seatById = (seatId: number | undefined) => map.coaches.flatMap((coach) => coach.seats).find((seat) => seat.id === seatId);
  const payButton = (
    <button type="button" className="button button-primary pay-button" disabled={timeIsUp} onClick={() => hold && navigate(`/bookings/${hold.id}/pay`)}>
      Continue to payment
      <svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
        <path d="M5 12h14M13 6l6 6-6 6" />
      </svg>
    </button>
  );

  return (
    <section className="seatmap-page">
      <BookingSteps current="seats" links={{ search: searchHref }} />

      <div className="trip-summary" aria-label="Trip">
        <div className="trip-summary-route">
          <span className="trip-summary-clock">{clock(map.journey.departureTime)}</span>
          <span className="trip-summary-station">{map.journey.from?.stationName}</span>
          <span aria-hidden="true" className="trip-arrow">→</span>
          <span className="trip-summary-clock">{clock(map.journey.arrivalTime)}</span>
          <span className="trip-summary-station">{map.journey.to?.stationName}</span>
        </div>
        <p className="trip-summary-meta">
          {dayLabel(map.departureDate)} · Train <span className="mono">{map.train.number}</span> {map.train.name}
          {map.journey.departureTime && map.journey.arrivalTime && <> · {duration(map.journey.departureTime, map.journey.arrivalTime)}</>} · {stops} stops
        </p>
        <Link className="link-button trip-summary-change" to={searchHref}>Change trip</Link>
      </div>

      <p className="sr-only" role="status" aria-live="polite">{liveNote}</p>
      {notice && <p className="notice notice-error" role="alert">{notice}</p>}
      {problem && <p className="notice notice-error" role="alert">{problem}</p>}
      {closed && <p className="notice notice-error">This trip is closed: it is cancelled or being cancelled, so seats cannot be held.</p>}
      {hold && !sameJourney && (
        <p className="notice notice-error">
          You are holding seats on this trip for another journey ({hold.trip.from.stationName} to {hold.trip.to.stationName}).{' '}
          <Link to={`/trips/${map.tripId}/seats?from=${hold.fromStop}&to=${hold.toStop}`}>Go to that journey</Link> or{' '}
          <button type="button" className="link-button" onClick={() => void releaseAll()}>release them</button> to choose here.
        </p>
      )}

      <div className="seatmap-layout">
        <section className="seat-picker" aria-label="Seat selection">
          <div role="group" aria-label="Class" className="class-tabs">
            {classes.map((c) => (
              <button key={c} type="button" className="class-tab" aria-pressed={c === shownClass} onClick={() => { setChosenClass(c); setChosenCoach(null); }}>
                {CLASS_LABEL[c]} class
                <span className="class-tab-price">{money(priceOf(c))}</span>
              </button>
            ))}
          </div>

          <TrainStrip coaches={map.coaches} currentId={shownCoach?.id} heldIn={heldInCoach}
            onSelect={(coach) => { setChosenClass(coach.class); setChosenCoach(coach.id); }} />

          {shownCoach && plan && (
            <div className="plan-pop" key={shownCoach.id}>
              <ul className="plan-legend" aria-label="Legend">
                <li><span className="legend-seat"><SeatShape look="available" /></span>Available</li>
                <li><span className="legend-seat"><SeatShape look="mine" /></span>Yours, held</li>
                <li><span className="legend-seat"><SeatShape look="held" /></span>Held by someone</li>
                <li><span className="legend-seat"><SeatShape look="booked" /></span>Taken</li>
              </ul>

              {shownCoach.seats.length === 0 ? (
                <p className="muted">This coach has no seats.</p>
              ) : (
                <div className="car-wrap">
                  <p className="car-front">
                    <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round"><path d="M12 19V5M6 11l6-6 6 6" /></svg>
                    Front of the train
                  </p>
                  <div className="car" role="group" aria-label={`Coach ${shownCoach.code}, ${CLASS_LABEL[shownCoach.class]} class`}>
                    <div className="car-nose" aria-hidden="true"><span /></div>
                    <div className="plan-rows" role="group" aria-label={`Seats of coach ${shownCoach.code}`}>
                      {plan.rows.map(({ row, seats }) => (
                        <div key={row} className="plan-row" style={{ gridTemplateColumns: `10px repeat(${plan.left.length}, minmax(44px, 48px)) 28px repeat(${plan.right.length}, minmax(44px, 48px)) 10px` }}>
                          <span className="car-window" aria-hidden="true" />
                          {plan.left.map((column) => seatButton(seats.get(column), `${row}${column}`))}
                          <span className="plan-rownum" aria-hidden="true">{row}</span>
                          {plan.right.map((column) => seatButton(seats.get(column), `${row}${column}`))}
                          <span className="car-window" aria-hidden="true" />
                        </div>
                      ))}
                    </div>
                    {shownCoach.standing.capacity > 0 && (
                <div className="standing-card standing-zone">
                  <div>
                    <h3>Standing zone</h3>
                    <p>
                      No seat is assigned. <strong>{shownCoach.standing.remaining}</strong> of {shownCoach.standing.capacity} left in coach {shownCoach.code}.
                    </p>
                  </div>
                  <div role="group" aria-label="Number of standing places" className="stepper">
                    <button type="button" className="stepper-button" aria-label={`Give back a standing place in coach ${shownCoach.code}`}
                      disabled={!sameJourney || timeIsUp || (myStanding.get(shownCoach.id) ?? 0) === 0 || busy.has(`coach:${shownCoach.id}`)}
                      onClick={() => void changeStanding(shownCoach, -1)}>−</button>
                    <output aria-live="polite" aria-label={`Your standing places in coach ${shownCoach.code}`}>{myStanding.get(shownCoach.id) ?? 0}</output>
                    <button type="button" className="stepper-button stepper-plus" aria-label={`Take a standing place in coach ${shownCoach.code}`}
                      disabled={!canPick || shownCoach.standing.remaining === 0 || busy.has(`coach:${shownCoach.id}`)}
                      onClick={() => void changeStanding(shownCoach, 1)}>+</button>
                  </div>
                </div>
              )}
                  </div>
                </div>
              )}
            </div>
          )}
        </section>

        <aside className="selection-column" aria-label="Your selection">
          {holding && (
            <HoldCard left={left} label="Seats held for you" bar timeIsUp={timeIsUp}
              note="The hold started when you picked your first seat. Adding seats does not extend it." />
          )}

          <div className="selection-card">
            <h2>Your selection</h2>
            {holding && hold && hold.items.length > 0 ? (
              <>
                <ul className="selection-list">
                  {hold.items.map((item) => {
                    const seat = item.type === 'SEAT' ? seatById(item.seatId) : undefined;
                    const label = item.type === 'SEAT' ? `Seat ${item.seatNumber}` : `Standing × ${item.qty}`;
                    return (
                      <li key={item.type === 'SEAT' ? `s${item.seatId}` : `c${item.coachId}`}>
                        <div className="selection-what">
                          <p className="selection-title">{label}</p>
                          <p className="selection-sub">{item.type === 'SEAT' ? `${CLASS_LABEL[item.coachClass]} class · Coach ${item.coachCode}` : `No assigned seat · Coach ${item.coachCode}`}</p>
                        </div>
                        <span className="selection-price">{money(item.price, hold.currency)}</span>
                        <button type="button" className="icon-button" aria-label={item.type === 'SEAT' ? `Remove seat ${item.seatNumber}` : `Remove standing places in coach ${item.coachCode}`}
                          disabled={timeIsUp || (item.type === 'SEAT' ? !seat || busy.has(String(item.seatId)) : busy.has(`coach:${item.coachId}`))}
                          onClick={() => void (item.type === 'SEAT' ? (seat && releaseSeat(seat)) : releaseStanding(item.coachId, item.qty))}>
                          <svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round">
                            <path d="M6 6l12 12M18 6L6 18" />
                          </svg>
                        </button>
                      </li>
                    );
                  })}
                </ul>
                <p className="selection-total">
                  <span>Total</span>
                  <strong>{money(hold.amount, hold.currency)}</strong>
                </p>
                <p className="selection-count muted">{itemCount} {itemCount === 1 ? 'place' : 'places'} held</p>
                <div className="pay-desktop">{payButton}</div>
                <button type="button" className="link-button release-all" onClick={() => void releaseAll()}>Release all seats</button>
              </>
            ) : (
              !closed && <p className="muted">Pick a seat on the plan. Your 5-minute hold starts with your first pick.</p>
            )}
          </div>
        </aside>
      </div>

      {holding && hold && (
        <div className="hold-bar-mobile" aria-label="Your hold">
          <div>
            <span className="hold-bar-time">{formatRemaining(left ?? 5 * 60 * 1000)}</span>
            <span className="hold-bar-sub">
              {hold.items.map((item) => (item.type === 'SEAT' ? item.seatNumber : `${item.qty} standing`)).join(', ')} · {money(hold.amount, hold.currency)}
            </span>
          </div>
          {payButton}
        </div>
      )}
    </section>
  );
}
