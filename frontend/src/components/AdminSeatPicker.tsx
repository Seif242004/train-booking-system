import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { CLASS_LABEL, type CoachClass, type TripStop } from '../adminTypes';
import { gatewayGet, gatewayRequest, readError, type GatewayResult } from '../api';
import type { Booking } from '../bookingTypes';
import { formatRemaining, remainingMs, startCountdown, type Countdown } from '../countdown';
import { money } from '../format';
import { connectLive } from '../live/connectLive';
import type { SeatMap, SeatMapCoach, SeatMapSeat } from '../seatMapTypes';
import { ErrorState } from './ErrorState';
import { ListSkeleton } from './ListSkeleton';
import { SeatShape, type SeatLook } from './SeatShape';

const EXPIRED_MESSAGE = 'Your hold expired. Pick the seats again.';

/** The columns of a coach split around the aisle, as on the passenger seat map. */
function planOf(coach: SeatMapCoach) {
  const columns = [...new Set(coach.seats.map((seat) => seat.column))].sort((a, b) => a.localeCompare(b));
  const leftCount = Math.ceil(columns.length / 2);
  const byRow = new Map<number, Map<string, SeatMapSeat>>();
  for (const seat of coach.seats) {
    const seats = byRow.get(seat.row) ?? new Map<string, SeatMapSeat>();
    seats.set(seat.column, seat);
    byRow.set(seat.row, seats);
  }
  return { left: columns.slice(0, leftCount), right: columns.slice(leftCount), rows: [...byRow.entries()].sort((a, b) => a[0] - b[0]).map(([row, seats]) => ({ row, seats })) };
}

export interface PickedJourney {
  fromStop: number;
  toStop: number;
}

interface Props {
  tripId: number;
  stops: TripStop[];
  /** The hold changed (picked, released, ended): the parent needs it to ask before the panel is closed. */
  onHoldChange: (hold: Booking | null) => void;
  /** "Issue booking": the seats are chosen, go to the form. */
  onContinue: (hold: Booking, map: SeatMap) => void;
  onCancel: () => void;
}

/**
 * Selectable seat view for an admin who books from the board. It is the passenger seat map (same plan, seat shapes, class and coach choice,
 * standing stepper, live updates through the same WebSocket) driven by the same hold endpoints, so the hold rules are the server's: the first pick
 * starts the 5 minutes, further picks join and never extend, deselecting releases, and when the time is up the selection is cleared with a message.
 */
export function AdminSeatPicker({ tripId, stops, onHoldChange, onContinue, onCancel }: Props) {
  const lastStop = stops.length - 1;
  const [journey, setJourney] = useState<PickedJourney>({ fromStop: 0, toStop: lastStop });
  const [map, setMap] = useState<SeatMap | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState<ReadonlySet<string>>(new Set());
  const [chosenClass, setChosenClass] = useState<CoachClass | null>(null);
  const [chosenCoach, setChosenCoach] = useState<number | null>(null);
  const [left, setLeft] = useState<number | null>(null);
  const countdown = useRef<Countdown | null>(null);
  const reloadTimer = useRef<number | undefined>(undefined);

  const load = useCallback(async (from = journey.fromStop, to = journey.toStop) => {
    const result = await gatewayGet(`/trips/${tripId}/seat-map?from=${from}&to=${to}`);
    if (result.status === 200) {
      setMap(result.body as SeatMap);
      setError(null);
    } else {
      setError(readError(result).message);
    }
  }, [tripId, journey.fromStop, journey.toStop]);

  useEffect(() => { void load(); }, [load]);

  const hold = map?.myHold ?? null;
  useEffect(() => { onHoldChange(hold); }, [hold, onHoldChange]);

  // The countdown comes from the server's expiry time corrected with the server's clock; the server decides when the hold is over.
  const expiresAt = hold?.expiresAt;
  const serverTime = hold?.serverTime;
  useEffect(() => {
    if (expiresAt === undefined || serverTime === undefined) {
      countdown.current = null;
      setLeft(null);
      return undefined;
    }
    countdown.current = startCountdown(expiresAt, serverTime, Date.now());
    let done = false;
    const tick = () => {
      const remaining = countdown.current ? remainingMs(countdown.current, Date.now()) : null;
      setLeft(remaining);
      if (remaining === 0 && !done) {
        done = true;
        setNotice(EXPIRED_MESSAGE);
        reloadTimer.current = window.setTimeout(() => void load(), 1500); // the sweeper releases within seconds
      }
    };
    tick();
    const timer = window.setInterval(tick, 250);
    return () => { window.clearInterval(timer); window.clearTimeout(reloadTimer.current); };
  }, [expiresAt, serverTime, load]);

  // Live updates: any change of the trip reloads the map (a few events at once collapse into one load). After a reconnect it loads again.
  useEffect(() => {
    let pending: number | undefined;
    const soon = () => { window.clearTimeout(pending); pending = window.setTimeout(() => void load(), 200); };
    const close = connectLive(tripId, {
      onSeatEvent: soon,
      onUserEvent: (event) => { if (event.reason === 'EXPIRED') setNotice(EXPIRED_MESSAGE); soon(); },
      onConnected: (again) => { if (again) soon(); },
    });
    return () => { window.clearTimeout(pending); close(); };
  }, [tripId, load]);

  const mySeatIds = useMemo(() => new Set((hold?.items ?? []).filter((i) => i.type === 'SEAT').map((i) => i.seatId as number)), [hold]);
  const myStanding = useMemo(() => new Map((hold?.items ?? []).filter((i) => i.type === 'STANDING').map((i) => [i.coachId, i.qty])), [hold]);
  const timeIsUp = left !== null && left <= 0;

  async function act(key: string, request: () => Promise<GatewayResult>) {
    setProblem(null);
    setBusy((now) => new Set(now).add(key));
    const result = await request();
    setBusy((now) => { const next = new Set(now); next.delete(key); return next; });
    if (result.status === 200 || result.status === 201) {
      await load();
      return;
    }
    const failure = readError(result);
    if (failure.code === 'seats_unavailable') setProblem(`${failure.details.map((d) => d.message).join('; ') || failure.message}. Your other picks are still held.`);
    else if (failure.code === 'hold_expired') setNotice(EXPIRED_MESSAGE);
    else setProblem(failure.message);
    await load();
  }

  const base = { tripId, fromStop: journey.fromStop, toStop: journey.toStop };
  const pick = (seat: SeatMapSeat) => act(String(seat.id), () => gatewayRequest('POST', '/bookings/pick', { body: { ...base, seatId: seat.id } }));
  const release = (seat: SeatMapSeat) => act(String(seat.id), () => gatewayRequest('POST', `/bookings/${hold?.id}/release`, { body: { seatId: seat.id } }));
  const standing = (coach: SeatMapCoach, delta: 1 | -1) => act(`coach:${coach.id}`, () => (delta === 1
    ? gatewayRequest('POST', '/bookings/pick', { body: { ...base, coachId: coach.id, qty: 1 } })
    : gatewayRequest('POST', `/bookings/${hold?.id}/release`, { body: { coachId: coach.id, qty: 1 } })));
  const releaseAll = () => act('all', () => gatewayRequest('DELETE', `/bookings/${hold?.id}/hold`));

  function changeJourney(next: PickedJourney) {
    if (next.toStop <= next.fromStop) return;
    setJourney(next);
    setMap(null);
  }

  if (error && !map) return <ErrorState message={error} onRetry={() => void load()} />;
  if (!map) return <ListSkeleton rows={3} label="Loading the seats…" />;

  const classes = [...new Set(map.coaches.map((c) => c.class))];
  const cls = chosenClass && classes.includes(chosenClass) ? chosenClass : classes[0];
  const coaches = map.coaches.filter((c) => c.class === cls);
  const coach = coaches.find((c) => c.id === chosenCoach) ?? coaches.find((c) => c.seats.some((s) => mySeatIds.has(s.id))) ?? coaches.find((c) => c.seats.some((s) => s.state === 'AVAILABLE')) ?? coaches[0];
  const plan = coach ? planOf(coach) : null;
  const price = (c: CoachClass) => map.fares[c] ?? 0;
  const lookOf = (seat: SeatMapSeat): SeatLook => (mySeatIds.has(seat.id) ? 'mine' : seat.state === 'HELD' ? 'held' : seat.state === 'BOOKED' ? 'booked' : 'available');

  function seatButton(seat: SeatMapSeat | undefined, key: string) {
    if (!seat) return <span key={key} className="plan-seat-empty" aria-hidden="true" />;
    const mine = mySeatIds.has(seat.id);
    const look = lookOf(seat);
    const clickable = mine ? !timeIsUp : seat.state === 'AVAILABLE' && !timeIsUp;
    const text = `Seat ${seat.seatNumber}, ${CLASS_LABEL[seat.class]} class, ${money(price(seat.class))}, ${mine ? 'yours, held. Press to release it' : seat.state === 'AVAILABLE' ? 'free' : seat.state === 'HELD' ? 'held by someone else' : 'taken'}`;
    return (
      <button key={key} type="button" className={`plan-seat is-${look}`} disabled={!clickable || busy.has(String(seat.id))} aria-pressed={mine} aria-label={text}
        onClick={() => void (mine ? release(seat) : pick(seat))}>
        <SeatShape look={look} />
        <span className="plan-seat-number" aria-hidden="true">{seat.seatNumber}</span>
        <span className="seat-tip" aria-hidden="true">{text}</span>
      </button>
    );
  }

  const places = (hold?.items ?? []).reduce((sum, i) => sum + i.qty, 0);
  return (
    <div className="board-picker">
      {hold && left !== null && (
        <p className={`board-hold-banner${left < 60_000 ? ' is-low' : ''}`} role="timer" aria-label="Seat hold">
          {timeIsUp ? EXPIRED_MESSAGE : `Seats held for ${formatRemaining(left)}. The time started with the first pick and is not extended.`}
        </p>
      )}
      {notice && !hold && <p className="notice notice-error" role="alert">{notice}</p>}
      {problem && <p className="notice notice-error" role="alert">{problem}</p>}

      <div className="board-journey">
        <label>From
          <select value={journey.fromStop} disabled={Boolean(hold)} onChange={(e) => changeJourney({ ...journey, fromStop: Number(e.target.value) })}>
            {stops.slice(0, lastStop).map((s) => <option key={s.stopIndex} value={s.stopIndex} disabled={s.stopIndex >= journey.toStop}>{s.stationName}</option>)}
          </select>
        </label>
        <label>To
          <select value={journey.toStop} disabled={Boolean(hold)} onChange={(e) => changeJourney({ ...journey, toStop: Number(e.target.value) })}>
            {stops.slice(1).map((s) => <option key={s.stopIndex} value={s.stopIndex} disabled={s.stopIndex <= journey.fromStop}>{s.stationName}</option>)}
          </select>
        </label>
        {hold && <span className="muted">Release the seats to change the journey.</span>}
      </div>

      <div className="chips" role="group" aria-label="Class">
        {classes.map((c) => <button key={c} type="button" className="chip" aria-pressed={c === cls} onClick={() => { setChosenClass(c); setChosenCoach(null); }}>{CLASS_LABEL[c]} · {money(price(c))}</button>)}
      </div>
      <div className="chips" role="group" aria-label="Coach">
        {coaches.map((c) => <button key={c.id} type="button" className="chip" aria-pressed={coach && c.id === coach.id} onClick={() => setChosenCoach(c.id)}>Coach {c.code}</button>)}
      </div>

      <ul className="plan-legend" aria-label="Legend">
        <li><span className="legend-seat"><SeatShape look="available" /></span>Free</li>
        <li><span className="legend-seat"><SeatShape look="mine" /></span>Yours, held</li>
        <li><span className="legend-seat"><SeatShape look="held" /></span>Held by someone</li>
        <li><span className="legend-seat"><SeatShape look="booked" /></span>Taken</li>
      </ul>

      {coach && plan && coach.seats.length > 0 && (
        <div className="car-wrap">
          <p className="car-front">Front of the train</p>
          <div className="car" role="group" aria-label={`Coach ${coach.code}, ${CLASS_LABEL[coach.class]} class`}>
            <div className="car-nose" aria-hidden="true"><span /></div>
            <div className="plan-rows">
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
          </div>
        </div>
      )}

      {coach && coach.standing.capacity > 0 && (
        <div className="standing-card standing-zone">
          <div>
            <h4>Standing zone</h4>
            <p><strong>{coach.standing.remaining}</strong> of {coach.standing.capacity} left in coach {coach.code}.</p>
          </div>
          <div role="group" aria-label="Number of standing places" className="stepper">
            <button type="button" className="stepper-button" aria-label={`Give back a standing place in coach ${coach.code}`}
              disabled={timeIsUp || (myStanding.get(coach.id) ?? 0) === 0 || busy.has(`coach:${coach.id}`)} onClick={() => void standing(coach, -1)}>−</button>
            <output aria-live="polite" aria-label={`Your standing places in coach ${coach.code}`}>{myStanding.get(coach.id) ?? 0}</output>
            <button type="button" className="stepper-button stepper-plus" aria-label={`Take a standing place in coach ${coach.code}`}
              disabled={timeIsUp || coach.standing.remaining === 0 || busy.has(`coach:${coach.id}`)} onClick={() => void standing(coach, 1)}>+</button>
          </div>
        </div>
      )}

      <div className="board-selection">
        <p><strong>{places === 0 ? 'No seats picked yet' : `${places} ${places === 1 ? 'place' : 'places'} picked`}</strong>{hold ? ` · ${money(hold.amount, hold.currency)}` : ''}</p>
        <p className="actions">
          <button type="button" className="button button-primary" disabled={!hold || timeIsUp || places === 0} onClick={() => hold && onContinue(hold, map)}>Issue booking</button>
          {hold && <button type="button" className="button" onClick={() => void releaseAll()}>Release all seats</button>}
          <button type="button" className="button" onClick={onCancel}>Back to the seat view</button>
        </p>
      </div>
    </div>
  );
}
