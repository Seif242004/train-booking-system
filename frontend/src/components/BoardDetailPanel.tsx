import { useCallback, useEffect, useRef, useState } from 'react';
import { CLASS_LABEL } from '../adminTypes';
import { gatewayDownload, gatewayGet, gatewayRequest, readError } from '../api';
import { STATE_LABEL, type BoardDetail, type BoardDetailCoach, type BoardDetailSeat, type BoardSeatState, type SeatCounts } from '../boardTypes';
import type { AdminBooking, Booking } from '../bookingTypes';
import { formatRemaining } from '../countdown';
import { saveFile } from '../ics';
import type { SeatMap } from '../seatMapTypes';
import { clock } from '../time';
import { AdminSeatPicker } from './AdminSeatPicker';
import { BookingAdminPanel } from './BookingAdminPanel';
import { ErrorState } from './ErrorState';
import { IssueBookingForm, type IssuedResult } from './IssueBookingForm';
import { ListSkeleton } from './ListSkeleton';
import { SeatShape, type SeatLook } from './SeatShape';
import { useToast } from './Toasts';

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

/** Milliseconds left of a hold by the browser's clock (the board's data time is close enough for a label; the real hold uses the server's clock). */
function heldFor(seat: BoardDetailSeat, now: number): string {
  if (!seat.holdExpiresAt) return 'held';
  const ms = Date.parse(seat.holdExpiresAt) - now;
  return ms > 0 ? `held, expires in ${formatRemaining(ms)}` : 'held, expiring now';
}

function ReadOnlySeat({ seat, now, selectedRef, onSelect }: { seat: BoardDetailSeat | undefined; now: number; selectedRef: string | null; onSelect: (reference: string) => void }) {
  if (!seat) return <span className="plan-seat-empty" aria-hidden="true" />;
  const state = seat.state === 'HELD' ? heldFor(seat, now) : WORD[seat.state];
  const text = `Seat ${seat.seatNumber}, ${state}${seat.reference ? `, booking ${seat.reference}` : ''}`;
  const inSelected = selectedRef !== null && seat.reference === selectedRef;
  // A taken seat opens its booking; a held seat only says when it ends (releasing somebody else's hold is not offered).
  if (seat.state === 'TAKEN' && seat.reference) {
    return (
      <button type="button" className={`plan-seat plan-seat-readonly is-booked${inSelected ? ' is-ref-selected' : ''}`} aria-label={`${text}. Open the booking`} aria-pressed={inSelected}
        onClick={() => onSelect(seat.reference as string)}>
        <SeatShape look="booked" />
        <span className="plan-seat-number" aria-hidden="true">{seat.seatNumber}</span>
        <span className="seat-tip" aria-hidden="true">{text}</span>
      </button>
    );
  }
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

/** Cancels the whole trip (the existing admin flow): first says how many confirmed bookings it will cancel, by how they were paid. */
function TripCancelSection({ detail, onChanged }: { detail: BoardDetail; onChanged: () => void }) {
  const toast = useToast();
  const { trip } = detail;
  const [open, setOpen] = useState(false);
  const [counts, setCounts] = useState<{ card: number; counter: number; free: number; atLeast: boolean } | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  async function ask() {
    setOpen(true);
    setMessage(null);
    const result = await gatewayGet(`/admin/bookings?tripId=${trip.tripId}&status=CONFIRMED`);
    if (result.status === 200) {
      const rows = result.body as AdminBooking[];
      const by = (m: string) => rows.filter((r) => (r.booking.paymentMethod ?? 'CARD') === m).length;
      setCounts({ card: by('CARD'), counter: by('COUNTER'), free: by('COMPLIMENTARY'), atLeast: rows.length >= 100 });
    } else {
      setMessage(readError(result).message);
    }
  }

  async function confirm() {
    setBusy(true);
    const result = await gatewayRequest('POST', `/admin/trips/${trip.tripId}/cancel`, { body: { reason: reason.trim() } });
    setBusy(false);
    if (result.status === 200 || result.status === 202) {
      toast.show('Trip cancellation started. Bookings are cancelled one by one; card refunds stay pending until the card provider confirms them.');
      setOpen(false);
      setReason('');
      onChanged();
    } else {
      setMessage(readError(result).message);
    }
  }

  if (trip.state === 'CANCELLED') return <p className="muted">This trip is cancelled.</p>;
  const total = counts ? counts.card + counts.counter + counts.free : 0;
  return (
    <div className="board-trip-cancel">
      {!open && <button type="button" className="button button-danger" onClick={() => void ask()}>Cancel trip</button>}
      {open && (
        <div className="notice notice-error" role="alertdialog" aria-label="Cancel the whole trip">
          {counts ? (
            <p>
              This cancels the whole trip and {counts.atLeast ? 'at least ' : ''}<strong>{total}</strong> confirmed {total === 1 ? 'booking' : 'bookings'}:{' '}
              {counts.card} by card (refunded in full to the card), {counts.counter} paid at the counter (returned at the counter), {counts.free} complimentary (nothing to return).
              Held seats are released.
            </p>
          ) : <p>Counting the confirmed bookings…</p>}
          <label>Reason (required) <input value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} /></label>
          {message && <p role="alert">{message}</p>}
          <p className="actions">
            <button type="button" className="button button-danger" disabled={busy || reason.trim() === '' || !counts} onClick={() => void confirm()}>{busy ? 'Cancelling…' : 'Cancel trip'}</button>
            <button type="button" className="button" disabled={busy} onClick={() => setOpen(false)}>Keep the trip</button>
          </p>
        </div>
      )}
    </div>
  );
}

interface Props {
  tripId: number;
  refreshKey: number | null;
  /** Something changed (a booking, a hold, the trip): the board refreshes at once. */
  onChanged: () => void;
  /** The admin has a hold running in this panel (its booking), or null. The board asks before the panel is closed. */
  onHoldChange: (hold: Booking | null) => void;
  /** The panel is being closed while a hold runs: the board shows the question here. */
  askClose: boolean;
  onAnswerClose: (answer: 'stay' | 'keep' | 'release') => void;
}

type Step = 'view' | 'pick' | 'issue' | 'done';

/**
 * The slide-over of one trip. View: counts per class and a coach plan; a taken seat opens its booking (cancel, mark refunded at the counter), a held
 * seat says when it ends. Book seats: select seats, then issue the booking (counter or complimentary) without Stripe. Cancel trip.
 */
export function BoardDetailPanel({ tripId, refreshKey, onChanged, onHoldChange, askClose, onAnswerClose }: Props) {
  const toast = useToast();
  const [detail, setDetail] = useState<BoardDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [coachId, setCoachId] = useState<number | null>(null);
  const [step, setStep] = useState<Step>('view');
  const [selectedRef, setSelectedRef] = useState<string | null>(null);
  const [hold, setHold] = useState<Booking | null>(null);
  const [issueMap, setIssueMap] = useState<SeatMap | null>(null);
  const [issued, setIssued] = useState<IssuedResult | null>(null);
  const [pickMessage, setPickMessage] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const loadedFor = useRef<number | null>(null);
  const holdRef = useRef<Booking | null>(null);
  holdRef.current = hold;

  const load = useCallback(async () => {
    const result = await gatewayGet(`/admin/board/trips/${tripId}`);
    if (result.status === 200) {
      setDetail(result.body as BoardDetail);
      setError(null);
    } else {
      setError(readError(result).message);
    }
  }, [tripId]);

  useEffect(() => {
    if (loadedFor.current !== tripId) {
      loadedFor.current = tripId;
      setDetail(null);
      setCoachId(null);
      setStep('view');
      setSelectedRef(null);
    }
    void load();
  }, [tripId, refreshKey, load]);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  const holdChanged = useCallback((next: Booking | null) => {
    setHold(next);
    onHoldChange(next);
  }, [onHoldChange]);

  /** After any action: the board and this view refresh now, not on the next poll. */
  const refreshNow = useCallback(() => {
    onChanged();
    void load();
  }, [onChanged, load]);

  async function release(andClose: boolean) {
    const current = holdRef.current;
    if (current) await gatewayRequest('DELETE', `/bookings/${current.id}/hold`);
    holdChanged(null);
    refreshNow();
    if (andClose) onAnswerClose('release');
  }

  async function downloadTicket(booking: Booking) {
    const result = await gatewayDownload(`/bookings/${booking.id}/ticket`);
    if (result.ok) {
      saveFile(`ticket-${booking.reference}.pdf`, 'application/pdf', result.blob);
      toast.show('Ticket downloaded');
    } else {
      toast.show(result.message, 'error');
    }
  }

  async function printTicket(booking: Booking) {
    const result = await gatewayDownload(`/bookings/${booking.id}/ticket`);
    if (result.ok) window.open(URL.createObjectURL(result.blob), '_blank', 'noopener');
    else toast.show(result.message, 'error');
  }

  if (!detail) {
    return error ? <ErrorState message={error} onRetry={() => void load()} /> : <ListSkeleton rows={3} label="Loading the trip…" />;
  }

  const { trip } = detail;
  const coach = detail.coaches.find((c) => c.id === coachId) ?? detail.coaches[0];
  const plan = coach ? planOf(coach) : null;
  const unavailable = trip.state === 'DEPARTED' ? 'This train has left.' : trip.state === 'ARRIVED' ? 'This trip is over.' : trip.state === 'CANCELLED' ? 'This trip is cancelled.' : trip.full ? 'No seats left.' : null;

  const closeQuestion = askClose && (
    <div className="notice notice-error" role="alertdialog" aria-label="Seat hold still running">
      <p>Your seat hold is still running. Keep it or release the seats?</p>
      <p className="actions">
        <button type="button" className="button button-primary" onClick={() => onAnswerClose('keep')}>Keep the hold and close</button>
        <button type="button" className="button" onClick={() => void release(true)}>Release the seats and close</button>
        <button type="button" className="button" onClick={() => onAnswerClose('stay')}>Stay here</button>
      </p>
    </div>
  );

  if (step === 'pick' || step === 'issue') {
    return (
      <div className="board-detail">
        {closeQuestion}
        <p className="muted">{trip.origin} {clock(trip.departureTime)} to {trip.destination} {clock(trip.arrivalTime)} · <span className="mono">{trip.trainNumber}</span> {trip.trainName}</p>
        {step === 'pick' && (
          <AdminSeatPicker tripId={tripId} stops={detail.stops} onHoldChange={holdChanged}
            onContinue={(_hold, map) => { setIssueMap(map); setStep('issue'); }}
            onCancel={() => { if (holdRef.current) setPickMessage('Release the seats first, or keep picking.'); else setStep('view'); }} />
        )}
        {step === 'pick' && pickMessage && holdRef.current && <p className="notice notice-error" role="status">{pickMessage} <button type="button" className="button" onClick={() => { setPickMessage(null); void release(false).then(() => setStep('view')); }}>Release and go back</button></p>}
        {step === 'issue' && hold && issueMap && (
          <IssueBookingForm hold={hold} map={issueMap} onBack={() => setStep('pick')}
            onHoldGone={(message) => { holdChanged(null); setPickMessage(message); setStep('pick'); refreshNow(); }}
            onIssued={(result) => {
              holdChanged(null);
              setIssued(result);
              setStep('done');
              const seats = result.booking.items.reduce((sum, i) => sum + i.qty, 0);
              toast.show(`Booked ${seats} ${seats === 1 ? 'seat' : 'seats'} on ${trip.trainName} for ${result.passenger}`);
              refreshNow();
            }} />
        )}
      </div>
    );
  }

  if (step === 'done' && issued) {
    const b = issued.booking;
    return (
      <div className="board-detail">
        {closeQuestion}
        <div className="notice notice-ok" role="status">
          <p><strong>Booked.</strong> Booking <span className="mono">{b.reference}</span> for {issued.passenger} is confirmed
            ({issued.method === 'COUNTER' ? 'paid at the counter' : 'complimentary'}). The seats show as taken.</p>
        </div>
        <p className="actions">
          <button type="button" className="button" onClick={() => { setSelectedRef(b.reference); setStep('view'); }}>View booking</button>
          <button type="button" className="button button-primary" onClick={() => void downloadTicket(b)}>Download ticket PDF</button>
          <button type="button" className="button" onClick={() => void printTicket(b)}>Open to print</button>
          <button type="button" className="button" onClick={() => { setIssued(null); setStep('pick'); }}>Book more seats</button>
        </p>
      </div>
    );
  }

  return (
    <div className="board-detail">
      {closeQuestion}
      <p className="muted">
        {trip.origin} {clock(trip.departureTime)} → {trip.destination} {clock(trip.arrivalTime)} · <span className="mono">{trip.trainNumber}</span> {trip.trainName} ·{' '}
        <strong>{STATE_LABEL[trip.state]}</strong>{trip.full ? ' · Full' : ''}
      </p>
      {error && <p className="notice notice-error" role="status">We could not refresh this trip. Showing the data from {detail.generatedAt.slice(11, 19)}.</p>}

      <p className="actions">
        <button type="button" className="button button-primary" disabled={unavailable !== null} onClick={() => { setPickMessage(null); setStep('pick'); }}>Book seats</button>
        {unavailable && <span className="muted">{unavailable}</span>}
      </p>

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
            <li><span className="legend-seat"><SeatShape look="held" /></span>Held (not cancellable here)</li>
            <li><span className="legend-seat"><SeatShape look="booked" /></span>Taken: press for the booking</li>
          </ul>
          {coach.seats.length === 0 ? (
            <p className="muted">This coach has no seats.</p>
          ) : (
            <div className="car-wrap">
              <p className="car-front">Front of the train</p>
              <div className="car" role="group" aria-label={`Coach ${coach.code}, ${CLASS_LABEL[coach.class]} class`}>
                <div className="car-nose" aria-hidden="true"><span /></div>
                <div className="plan-rows">
                  {plan.rows.map(({ row, seats }) => (
                    <div key={row} className="plan-row" style={{ gridTemplateColumns: `10px repeat(${plan.left.length}, minmax(44px, 48px)) 28px repeat(${plan.right.length}, minmax(44px, 48px)) 10px` }}>
                      <span className="car-window" aria-hidden="true" />
                      {plan.left.map((column) => <ReadOnlySeat key={column} seat={seats.get(column)} now={now} selectedRef={selectedRef} onSelect={setSelectedRef} />)}
                      <span className="plan-rownum" aria-hidden="true">{row}</span>
                      {plan.right.map((column) => <ReadOnlySeat key={column} seat={seats.get(column)} now={now} selectedRef={selectedRef} onSelect={setSelectedRef} />)}
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
        </>
      )}

      {selectedRef && <BookingAdminPanel reference={selectedRef} onChanged={refreshNow} />}

      <h3>Trip</h3>
      <TripCancelSection detail={detail} onChanged={refreshNow} />
      <p className="muted board-stamp">Data from {detail.generatedAt.slice(0, 10)} {detail.generatedAt.slice(11, 19)}. A seat counts as free only if it is free for the whole journey.</p>
    </div>
  );
}
