import { useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { CLASS_LABEL } from '../adminTypes';
import { AdminHeader } from '../components/AdminHeader';
import { gatewayGet, gatewayRequest, readError } from '../api';
import type { AdminBooking, BookingStatus } from '../bookingTypes';
import { MethodChip, REFUND_TEXT as REFUND_LABEL } from '../components/BookingAdminPanel';
import { clock } from '../time';
import { label } from '../format';
import { EmptyState } from '../components/EmptyState';
import { TripsBoard } from './TripsBoard';

const STATUSES: BookingStatus[] = ['PENDING_PAYMENT', 'CONFIRMED', 'CANCELLED', 'EXPIRED', 'ABANDONED'];

/**
 * Admin support (Flow 7, FR-C6, FR-C7): find a booking by reference, passenger email, trip or status, see the payment and refund status, and cancel
 * a confirmed booking on the passenger's request with a reason (the same cancellation as the passenger's own, which records which admin did it).
 * "Failed refunds" lists the bookings whose refund needs a follow-up.
 */
function BookingSearch() {
  const [reference, setReference] = useState('');
  const [email, setEmail] = useState('');
  const [tripId, setTripId] = useState('');
  const [status, setStatus] = useState('');
  const [method, setMethod] = useState('');
  const [results, setResults] = useState<AdminBooking[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<AdminBooking | null>(null);
  const [asking, setAsking] = useState(false);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);

  async function search(params: Record<string, string>) {
    const query = new URLSearchParams(Object.entries(params).filter(([, value]) => value.trim() !== '')).toString();
    const result = await gatewayGet(`/admin/bookings${query ? `?${query}` : ''}`);
    setSelected(null);
    setMessage(null);
    if (result.status === 200) {
      setResults(result.body as AdminBooking[]);
      setError(null);
    } else {
      setResults(null);
      setError(readError(result).details[0]?.message ?? readError(result).message);
    }
  }

  function onSearch(event: FormEvent) {
    event.preventDefault();
    void search({ ref: reference, email, tripId, status, method });
  }

  async function open(row: AdminBooking) {
    setAsking(false);
    setReason('');
    setMessage(null);
    const result = await gatewayGet(`/admin/bookings/${row.booking.id}`);
    setSelected(result.status === 200 ? (result.body as AdminBooking) : row);
  }

  async function cancel() {
    if (!selected) return;
    setBusy(true);
    const result = await gatewayRequest('POST', `/admin/bookings/${selected.booking.id}/cancel`, { body: { reason: reason.trim() } });
    setBusy(false);
    if (result.status === 200) {
      setMessage({ ok: true, text: 'The booking is cancelled and the refund is on its way. The passenger is emailed.' });
      setAsking(false);
      setReason('');
      const fresh = await gatewayGet(`/admin/bookings/${selected.booking.id}`);
      if (fresh.status === 200) setSelected(fresh.body as AdminBooking);
    } else {
      const failure = readError(result);
      setMessage({ ok: false, text: failure.code === 'trip_departed' ? 'The train has already left the booking\'s boarding stop: it can no longer be cancelled.' : failure.message });
    }
  }

  return (
    <section aria-label="Bookings">
      <form className="filters filters-card" onSubmit={onSearch}>
        <label>Reference <input value={reference} onChange={(event) => setReference(event.target.value)} placeholder="TB-ABC234" /></label>
        <label>Passenger email <input value={email} onChange={(event) => setEmail(event.target.value)} placeholder="part of the address" /></label>
        <label>Trip id <input value={tripId} inputMode="numeric" onChange={(event) => setTripId(event.target.value.replace(/\D/g, ''))} /></label>
        <label>Status
          <select value={status} onChange={(event) => setStatus(event.target.value)}>
            <option value="">Any</option>
            {STATUSES.map((s) => <option key={s} value={s}>{label(s)}</option>)}
          </select>
        </label>
        <label>Paid by
          <select value={method} onChange={(event) => setMethod(event.target.value)}>
            <option value="">Any</option>
            <option value="CARD">Card</option>
            <option value="COUNTER">Counter</option>
            <option value="COMPLIMENTARY">Complimentary</option>
          </select>
        </label>
        <span className="actions filters-actions">
          <button type="submit" className="button button-primary">Search</button>
          <button type="button" className="button" onClick={() => void search({ refundStatus: 'REFUND_FAILED' })}>Failed refunds</button>
        </span>
      </form>

      {error && <p className="notice notice-error" role="alert">{error}</p>}
      {results && results.length === 0 && <EmptyState title="No booking matches" text='Check the reference or the email, or clear a filter and search again.' />}
      {results && results.length > 0 && (
        <div className="table-wrap"><table className="table">
          <thead>
            <tr><th scope="col">Reference</th><th scope="col">Passenger</th><th scope="col">Journey</th><th scope="col">Status</th><th scope="col">Paid by</th><th scope="col">Refund</th><th scope="col"><span className="visually-hidden">Open</span></th></tr>
          </thead>
          <tbody>
            {results.map((row) => (
              <tr key={row.booking.id}>
                <td className="mono">{row.booking.reference}</td>
                <td>{row.userEmail}</td>
                <td>{row.booking.trip.from.stationName} → {row.booking.trip.to.stationName}<div className="muted">{row.booking.trip.departureDate} · trip {row.booking.tripId}</div></td>
                <td><span className={`status-chip ${row.booking.status === 'CONFIRMED' ? 'is-ok' : ''}`}>{label(row.booking.status)}</span></td>
                <td><MethodChip method={row.booking.paymentMethod} /></td>
                <td className={row.booking.refundStatus === 'REFUND_FAILED' ? 'status-fail' : ''}>{row.booking.refundStatus === 'NONE' ? '' : REFUND_LABEL[row.booking.refundStatus]}</td>
                <td><button type="button" className="button" onClick={() => void open(row)}>Open</button></td>
              </tr>
            ))}
          </tbody>
        </table></div>
      )}
      {results && results.length === 100 && <p className="muted">Only the 100 newest are shown: narrow the search.</p>}

      {selected && (
        <div className="card support-details" aria-label="Booking details">
          <h2>{selected.booking.reference} · {label(selected.booking.status)}</h2>
          <p>
            <strong>{selected.userName ?? selected.userEmail}</strong> · {selected.userEmail} <span className="muted mono">({selected.userId})</span>
          </p>
          <p className="muted">
            {selected.booking.trip.from.stationName} {clock(selected.booking.trip.from.departureTime)} → {selected.booking.trip.to.stationName} {clock(selected.booking.trip.to.arrivalTime)} ·{' '}
            {selected.booking.trip.departureDate} · <span className="mono">{selected.booking.trip.trainNumber}</span> · trip {selected.booking.tripId}
          </p>
          <ul className="booking-items">
            {selected.booking.items.map((item, index) => (
              <li key={index}>
                {item.type === 'SEAT' ? `Seat ${item.seatNumber} · coach ${item.coachCode}` : `${item.qty} standing ${item.qty === 1 ? 'place' : 'places'} · coach ${item.coachCode}`}
                {' '}<span className="muted">({CLASS_LABEL[item.coachClass]})</span> {item.price.toFixed(2)} {selected.booking.currency}
              </li>
            ))}
          </ul>
          <p className="booking-total">
            Total <strong>{selected.booking.amount.toFixed(2)}</strong> {selected.booking.currency} · payment <strong>{selected.paymentStatus === 'PAID' ? 'paid' : 'not paid'}</strong> ·{' '}
            refund <strong className={selected.booking.refundStatus === 'REFUND_FAILED' ? 'status-fail' : ''}>{REFUND_LABEL[selected.booking.refundStatus]}</strong>
          </p>
          {selected.issue && (
            <p className="muted">
              Paid by <MethodChip method={selected.issue.method} /> · issued by <span className="mono">{selected.issue.issuedBy}</span> on {selected.issue.issuedAt.slice(0, 10)}
              {selected.issue.note ? ` · ${selected.issue.method === 'COMPLIMENTARY' ? 'reason' : 'note'}: ${selected.issue.note}` : ''}
              {selected.issue.walkInName ? ` · walk-in: ${selected.issue.walkInName}${selected.issue.walkInPhone ? ', ' + selected.issue.walkInPhone : ''}` : ''}
            </p>
          )}
          {selected.cancelledAt && (
            <p className="muted">
              Cancelled by {selected.cancelledByRole === 'ADMIN' ? 'an admin' : 'the passenger'} <span className="mono">({selected.cancelledBy})</span>
              {selected.cancelReason && ` · reason: ${selected.cancelReason}`}
            </p>
          )}

          {selected.cancellable && !asking && (
            <p><button type="button" className="button button-danger" onClick={() => setAsking(true)}>Cancel booking</button></p>
          )}
          {selected.booking.status === 'CONFIRMED' && !selected.cancellable && <p className="muted">The train has left the boarding stop: this booking can no longer be cancelled.</p>}
          {asking && (
            <div className="notice notice-error" role="alertdialog" aria-label="Cancel this booking for the passenger">
              <p>Cancel this booking at the passenger's request? It is cancelled at once and <strong>refunded in full</strong> ({selected.booking.amount.toFixed(2)} {selected.booking.currency}).</p>
              <label>Reason <input value={reason} maxLength={500} onChange={(event) => setReason(event.target.value)} /></label>
              <p className="actions">
                <button type="button" className="button button-danger" disabled={busy || reason.trim() === ''} onClick={() => void cancel()}>{busy ? 'Cancelling…' : 'Yes, cancel and refund'}</button>
                <button type="button" className="button" disabled={busy} onClick={() => setAsking(false)}>Keep it</button>
              </p>
            </div>
          )}
          {message && <p className={`notice ${message.ok ? 'notice-ok' : 'notice-error'}`} role="status">{message.text}</p>}
        </div>
      )}
    </section>
  );
}

/**
 * The admin support area has two views under one heading: the live trips board (what is full, held or gone, right now) and the booking search
 * (the original support page, unchanged). The view is in the address (?view=bookings) so a refresh or Back keeps it.
 */
export function SupportPage() {
  const [params, setParams] = useSearchParams();
  const view = params.get('view') === 'bookings' ? 'bookings' : 'board';

  function choose(next: 'board' | 'bookings') {
    // Each view has its own filters in the address, so they start clean.
    setParams(next === 'board' ? new URLSearchParams() : new URLSearchParams({ view: next }), { replace: true });
  }

  return (
    <section>
      <AdminHeader title="Support" />
      <div className="chips support-tabs" role="group" aria-label="Support views">
        <button type="button" className="chip" aria-pressed={view === 'board'} onClick={() => choose('board')}>Trips board</button>
        <button type="button" className="chip" aria-pressed={view === 'bookings'} onClick={() => choose('bookings')}>Find a booking</button>
      </div>
      {view === 'board' ? <TripsBoard /> : <BookingSearch />}
    </section>
  );
}
