import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { CLASS_LABEL, type CoachClass } from '../adminTypes';
import { gatewayGet, readError } from '../api';
import type { SeatMap, SeatMapCoach, SeatMapSeat, SeatState } from '../seatMapTypes';
import { clock } from '../time';

const STATE_LABEL: Record<SeatState, string> = { AVAILABLE: 'Available', HELD: 'Held by someone', BOOKED: 'Booked' };
// A glyph next to the seat number, so the state never depends on colour alone (NFR-9).
const STATE_MARK: Record<SeatState, string> = { AVAILABLE: '', HELD: '◐', BOOKED: '✕' };

/** The seats of a coach as rows: seats with the same row number side by side, in column order. */
function rowsOf(coach: SeatMapCoach): SeatMapSeat[][] {
  const byRow = new Map<number, SeatMapSeat[]>();
  for (const seat of coach.seats) byRow.set(seat.row, [...(byRow.get(seat.row) ?? []), seat]);
  return [...byRow.entries()].sort((a, b) => a[0] - b[0]).map(([, seats]) => seats.sort((a, b) => a.column.localeCompare(b.column)));
}

/** Passenger page (Flow 2): the seat map of a trip for the journey the passenger searched. */
export function SeatMapPage() {
  const { id } = useParams();
  const [query] = useSearchParams();
  const from = query.get('from') ?? '';
  const to = query.get('to') ?? '';

  const [map, setMap] = useState<SeatMap | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selectedSeats, setSelectedSeats] = useState<Set<number>>(new Set());
  const [standing, setStanding] = useState<Record<number, number>>({});

  const load = useCallback(async () => {
    const result = await gatewayGet(`/trips/${id}/seat-map?from=${from}&to=${to}`);
    if (result.status === 200) {
      setMap(result.body as SeatMap);
      setError(null);
    } else {
      setError(readError(result).message);
    }
  }, [id, from, to]);

  useEffect(() => {
    void load();
  }, [load]);

  const priceOf = useCallback((coachClass: CoachClass) => map?.fares[coachClass] ?? 0, [map]);

  const total = useMemo(() => {
    if (!map) return 0;
    let sum = 0;
    for (const coach of map.coaches) {
      for (const seat of coach.seats) if (selectedSeats.has(seat.id)) sum += priceOf(seat.class);
      sum += (standing[coach.id] ?? 0) * priceOf(coach.class);
    }
    return sum;
  }, [map, selectedSeats, standing, priceOf]);

  const selectedCount = selectedSeats.size + Object.values(standing).reduce((a, b) => a + b, 0);

  function toggle(seat: SeatMapSeat) {
    if (seat.state !== 'AVAILABLE') return;
    setSelectedSeats((current) => {
      const next = new Set(current);
      if (next.has(seat.id)) next.delete(seat.id);
      else next.add(seat.id);
      return next;
    });
  }

  function changeStanding(coach: SeatMapCoach, delta: number) {
    setStanding((current) => {
      const value = Math.max(0, Math.min(coach.standing.remaining, (current[coach.id] ?? 0) + delta));
      return { ...current, [coach.id]: value };
    });
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

  return (
    <section>
      <p><Link to="/search">← Back to the search</Link></p>
      <h1>Choose your seats</h1>
      <p className="seatmap-journey">
        <strong>{map.journey.from?.stationName}</strong> {clock(map.journey.departureTime)} → <strong>{map.journey.to?.stationName}</strong>{' '}
        {clock(map.journey.arrivalTime)} · {map.departureDate} · <span className="mono">{map.train.number}</span> {map.train.name}
      </p>
      {closed && <p className="notice notice-error">This trip is closed: it is cancelled or being cancelled, so seats cannot be held.</p>}

      <ul className="seatmap-legend" aria-label="Legend">
        <li><span className="seat seat-legend">1A</span> Available</li>
        <li><span className="seat seat-legend seat-selected">1A ✓</span> Selected by you</li>
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
                    const selected = selectedSeats.has(seat.id);
                    const state: SeatState = seat.state;
                    return (
                      <button key={seat.id} type="button"
                        className={`seat ${selected ? 'seat-selected' : state === 'HELD' ? 'seat-held' : state === 'BOOKED' ? 'seat-booked' : ''}`}
                        disabled={closed || state !== 'AVAILABLE'}
                        aria-pressed={selected}
                        aria-label={`Seat ${seat.seatNumber}, ${selected ? 'selected by you' : STATE_LABEL[state]}`}
                        onClick={() => toggle(seat)}>
                        {seat.seatNumber} {selected ? '✓' : STATE_MARK[state]}
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
                <button type="button" className="button" aria-label={`Fewer standing places in coach ${coach.code}`}
                  disabled={closed || (standing[coach.id] ?? 0) === 0} onClick={() => changeStanding(coach, -1)}>−</button>
                <output aria-label={`Standing places chosen in coach ${coach.code}`}>{standing[coach.id] ?? 0}</output>
                <button type="button" className="button" aria-label={`More standing places in coach ${coach.code}`}
                  disabled={closed || (standing[coach.id] ?? 0) >= coach.standing.remaining} onClick={() => changeStanding(coach, 1)}>+</button>
              </span>
            </div>
          )}
        </div>
      ))}

      <div className="selection-bar">
        <span>
          {selectedCount === 0 ? 'Nothing selected yet.' : `${selectedCount} selected · about ${total.toFixed(2)} EGP`}
        </span>
      </div>
    </section>
  );
}
