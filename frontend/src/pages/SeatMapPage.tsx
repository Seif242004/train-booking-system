import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { CLASS_LABEL, type CoachClass } from '../adminTypes';
import { gatewayGet, gatewayRequest, readError } from '../api';
import type { Booking } from '../bookingTypes';
import { applySeatEvent } from '../live/applySeatEvent';
import { connectLive } from '../live/connectLive';
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

  const navigate = useNavigate();
  // The payment page sends the user back here with a message when the hold ran out or the trip was cancelled (SRS FR-H5).
  const redirectMessage = (useLocation().state as { message?: string } | null)?.message ?? null;
  const [holding, setHolding] = useState(false);
  const [holdProblem, setHoldProblem] = useState<string | null>(null);
  const [map, setMap] = useState<SeatMap | null>(null);
  // The live events are applied to the map as it is right now, also when several arrive before the page has drawn again.
  const mapRef = useRef<SeatMap | null>(null);
  const [liveNotice, setLiveNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selectedSeats, setSelectedSeats] = useState<Set<number>>(new Set());
  const [standing, setStanding] = useState<Record<number, number>>({});

  const load = useCallback(async () => {
    const result = await gatewayGet(`/trips/${id}/seat-map?from=${from}&to=${to}`);
    if (result.status === 200) {
      mapRef.current = result.body as SeatMap;
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

  const selectedRef = useRef<Set<number>>(selectedSeats);
  selectedRef.current = selectedSeats;
  const tripId = map?.tripId;

  // Live updates (Flow 2): while this page is open, the changes of the trip arrive by WebSocket and the map is updated without a
  // reload. After a reconnect, or when an event was missed, the full map is loaded again (FR-R4).
  useEffect(() => {
    if (tripId === undefined) return undefined;
    return connectLive(tripId, {
      onSeatEvent: (event) => {
        const current = mapRef.current;
        if (!current) return;
        const result = applySeatEvent(current, event, selectedRef.current);
        if (result.kind === 'reload') {
          void load();
        } else if (result.kind === 'applied') {
          mapRef.current = result.map;
          setMap(result.map);
          if (result.lostSeatIds.length > 0) {
            // FR-R3: a seat the user had selected (but not yet held) was taken by someone else.
            const numbers = result.map.coaches.flatMap((c) => c.seats).filter((s) => result.lostSeatIds.includes(s.id)).map((s) => s.seatNumber);
            setSelectedSeats((currentSelection) => new Set([...currentSelection].filter((id) => !result.lostSeatIds.includes(id))));
            setLiveNotice(`Seat ${numbers.join(', ')} was just taken by someone else and was removed from your selection.`);
          }
          // Standing places that were chosen but are not there any more are reduced to what is left.
          setStanding((chosen) => Object.fromEntries(Object.entries(chosen).map(([coachId, qty]) =>
            [coachId, Math.min(qty, result.map.coaches.find((c) => c.id === Number(coachId))?.standing.remaining ?? 0)])));
        }
      },
      onConnected: (again) => {
        if (again) void load();
      },
    });
  }, [tripId, load]);

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

  /** Holds the selection (Flow 3): all or nothing. On success the payment page opens; if something was lost, the map is refreshed. */
  async function hold() {
    if (!map) return;
    setHolding(true);
    setHoldProblem(null);
    const result = await gatewayRequest('POST', '/bookings', {
      body: {
        tripId: map.tripId,
        fromStop: map.journey.fromStop,
        toStop: map.journey.toStop,
        seatIds: [...selectedSeats],
        standing: Object.entries(standing).filter(([, qty]) => qty > 0).map(([coachId, qty]) => ({ coachId: Number(coachId), qty })),
      },
    });
    setHolding(false);
    if (result.status === 201) {
      navigate(`/bookings/${(result.body as Booking).id}/pay`);
      return;
    }
    const problem = readError(result);
    if (problem.code === 'seats_unavailable') {
      // Nothing was held. Say which items were lost, drop them from the selection, and show the map as it is now.
      setHoldProblem(`Nothing was held. ${problem.details.map((d) => d.message).join('; ')}. Please choose again.`);
      const lostSeats = problem.details.filter((d) => d.field.startsWith('seat:')).map((d) => Number(d.field.slice(5)));
      setSelectedSeats((current) => new Set([...current].filter((seatId) => !lostSeats.includes(seatId))));
      setStanding({});
      await load();
    } else {
      setHoldProblem(problem.message);
    }
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
      {redirectMessage && <p className="notice notice-error" role="alert">{redirectMessage}</p>}
      {holdProblem && <p className="notice notice-error" role="alert">{holdProblem}</p>}
      {liveNotice && <p className="notice" role="status">{liveNotice}</p>}
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
        <button type="button" className="button button-primary" disabled={closed || holding || selectedCount === 0} onClick={() => void hold()}>
          {holding ? 'Holding…' : 'Hold for 5 minutes'}
        </button>
      </div>
    </section>
  );
}
