import { useEffect, useRef, useState } from 'react';
import { CLASS_LABEL } from '../adminTypes';
import { gatewayGet, readError } from '../api';
import { STATE_LABEL, type BoardDetail, type BoardDetailCoach, type BoardDetailSeat, type BoardSeatState, type SeatCounts } from '../boardTypes';
import { clock } from '../time';
import { ErrorState } from './ErrorState';
import { ListSkeleton } from './ListSkeleton';
import { SeatShape, type SeatLook } from './SeatShape';

const LOOK: Record<BoardSeatState, SeatLook> = { FREE: 'available', HELD: 'held', TAKEN: 'booked' };
const WORD: Record<BoardSeatState, string> = { FREE: 'free', HELD: 'held', TAKEN: 'taken' };

/** The columns of a coach split around the aisle (the same rule as the passenger seat map). */
function planOf(coach: BoardDetailCoach) {
  const columns = [...new Set(coach.seats.map((seat) => seat.column))].sort((a, b) => a.localeCompare(b));
  const leftCount = Math.ceil(columns.length / 2);
  const byRow = new Map<number, Map<string, BoardDetailSeat>>();
  for (const seat of coach.seats) {
    const seats = byRow.get(seat.row) ?? new Map<string, BoardDetailSeat>();
    seats.set(seat.column, seat);
    byRow.set(seat.row, seats);
  }
  return {
    left: columns.slice(0, leftCount),
    right: columns.slice(leftCount),
    rows: [...byRow.entries()].sort((a, b) => a[0] - b[0]).map(([row, seats]) => ({ row, seats })),
  };
}

function ReadOnlySeat({ seat }: { seat: BoardDetailSeat | undefined }) {
  if (!seat) return <span className="plan-seat-empty" aria-hidden="true" />;
  const text = `Seat ${seat.seatNumber}, ${WORD[seat.state]}${seat.reference ? `, booking ${seat.reference}` : ''}`;
  // Not a button: nothing here can be selected or held. The state is in the label, the mark and the tip, not in the colour alone.
  return (
    <div className={`plan-seat plan-seat-readonly is-${LOOK[seat.state]}`} role="img" aria-label={text} tabIndex={0}>
      <SeatShape look={LOOK[seat.state]} />
      <span className="plan-seat-number" aria-hidden="true">{seat.seatNumber}</span>
      <span className="seat-tip" aria-hidden="true">{text}</span>
    </div>
  );
}

function CountRow({ name, counts }: { name: string; counts: SeatCounts }) {
  return (
    <tr>
      <th scope="row">{name}</th>
      <td>{counts.capacity}</td>
      <td>{counts.confirmed}</td>
      <td>{counts.held}</td>
      <td>{counts.free}</td>
    </tr>
  );
}

/**
 * The content of the slide-over of one trip (read-only): the counts per class and the seats of each coach for the full journey. It loads
 * the detail once and again whenever the board has refreshed (`refreshKey`), so it follows the board without a connection of its own.
 */
export function BoardDetailPanel({ tripId, refreshKey }: { tripId: number; refreshKey: number | null }) {
  const [detail, setDetail] = useState<BoardDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [coachId, setCoachId] = useState<number | null>(null);
  const loadedFor = useRef<number | null>(null);

  async function load() {
    const result = await gatewayGet(`/admin/board/trips/${tripId}`);
    if (result.status === 200) {
      setDetail(result.body as BoardDetail);
      setError(null);
    } else {
      setError(readError(result).message);
    }
  }

  useEffect(() => {
    if (loadedFor.current !== tripId) {
      loadedFor.current = tripId;
      setDetail(null);
      setCoachId(null);
    }
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tripId, refreshKey]);

  if (!detail) {
    return error ? <ErrorState message={error} onRetry={() => void load()} /> : <ListSkeleton rows={3} label="Loading the trip…" />;
  }

  const { trip } = detail;
  const coach = detail.coaches.find((c) => c.id === coachId) ?? detail.coaches[0];
  const plan = coach ? planOf(coach) : null;
  const taken = coach ? coach.seats.filter((seat) => seat.state === 'TAKEN' && seat.reference) : [];

  return (
    <div className="board-detail">
      <p className="muted">
        {trip.origin} {clock(trip.departureTime)} → {trip.destination} {clock(trip.arrivalTime)} · <span className="mono">{trip.trainNumber}</span> {trip.trainName} ·{' '}
        <strong>{STATE_LABEL[trip.state]}</strong>{trip.full ? ' · Full' : ''}
      </p>
      {error && <p className="notice notice-error" role="status">We could not refresh this trip. Showing the data from {detail.generatedAt.slice(11, 19)}.</p>}

      <div className="table-wrap">
        <table className="table board-counts">
          <caption className="visually-hidden">Seats of the trip for the full journey</caption>
          <thead>
            <tr><th scope="col">Class</th><th scope="col">Seats</th><th scope="col">Confirmed</th><th scope="col">Held</th><th scope="col">Free</th></tr>
          </thead>
          <tbody>
            {trip.first.capacity > 0 && <CountRow name={`${CLASS_LABEL.FIRST}`} counts={trip.first} />}
            {trip.second.capacity > 0 && <CountRow name={`${CLASS_LABEL.SECOND}`} counts={trip.second} />}
            {trip.third.capacity > 0 && <CountRow name={`${CLASS_LABEL.THIRD}`} counts={trip.third} />}
            {trip.standing.capacity > 0 && <CountRow name="Standing" counts={trip.standing} />}
            <CountRow name="Total" counts={trip.totals} />
          </tbody>
        </table>
      </div>

      {detail.coaches.length > 0 && coach && plan && (
        <>
          <h3>Coaches</h3>
          <div className="chips" role="group" aria-label="Coaches">
            {detail.coaches.map((c) => (
              <button key={c.id} type="button" className="chip" aria-pressed={c.id === coach.id} onClick={() => setCoachId(c.id)}>
                {c.code} · {CLASS_LABEL[c.class]}
              </button>
            ))}
          </div>
          <ul className="plan-legend" aria-label="Legend">
            <li><span className="legend-seat"><SeatShape look="available" /></span>Free</li>
            <li><span className="legend-seat"><SeatShape look="held" /></span>Held</li>
            <li><span className="legend-seat"><SeatShape look="booked" /></span>Taken</li>
          </ul>
          {coach.seats.length === 0 ? (
            <p className="muted">This coach has no seats.</p>
          ) : (
            <div className="car-wrap">
              <p className="car-front">Front of the train</p>
              <div className="car" role="group" aria-label={`Coach ${coach.code}, ${CLASS_LABEL[coach.class]} class, read-only`}>
                <div className="car-nose" aria-hidden="true"><span /></div>
                <div className="plan-rows">
                  {plan.rows.map(({ row, seats }) => (
                    <div key={row} className="plan-row" style={{ gridTemplateColumns: `10px repeat(${plan.left.length}, minmax(44px, 48px)) 28px repeat(${plan.right.length}, minmax(44px, 48px)) 10px` }}>
                      <span className="car-window" aria-hidden="true" />
                      {plan.left.map((column) => <ReadOnlySeat key={column} seat={seats.get(column)} />)}
                      <span className="plan-rownum" aria-hidden="true">{row}</span>
                      {plan.right.map((column) => <ReadOnlySeat key={column} seat={seats.get(column)} />)}
                      <span className="car-window" aria-hidden="true" />
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}
          {coach.standing.capacity > 0 && (
            <p className="muted">
              Standing in coach {coach.code}: {coach.standing.confirmed} confirmed · {coach.standing.held} held · {coach.standing.free} free of {coach.standing.capacity}
            </p>
          )}
          {taken.length > 0 && (
            <>
              <h4>Taken seats in coach {coach.code}</h4>
              <p className="board-refs">
                {taken.map((seat) => (
                  <span key={seat.id}><strong>{seat.seatNumber}</strong> <span className="mono">{seat.reference}</span></span>
                ))}
              </p>
            </>
          )}
        </>
      )}
      <p className="muted board-stamp">Data from {detail.generatedAt.slice(0, 10)} {detail.generatedAt.slice(11, 19)}. A seat counts as free only if it is free for the whole journey.</p>
    </div>
  );
}
