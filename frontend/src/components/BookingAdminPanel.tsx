import { useCallback, useEffect, useState } from 'react';
import { CLASS_LABEL } from '../adminTypes';
import { gatewayGet, gatewayRequest, readError } from '../api';
import { METHOD_LABEL, type AdminBooking, type PaymentMethod, type RefundStatus } from '../bookingTypes';
import { label, money } from '../format';
import { clock } from '../time';
import { ErrorState } from './ErrorState';
import { ListSkeleton } from './ListSkeleton';
import { useToast } from './Toasts';

export const REFUND_TEXT: Record<RefundStatus, string> = {
  NONE: 'No refund',
  REFUND_PENDING: 'Refund pending: not finished yet',
  REFUNDED: 'Refunded to the card',
  REFUND_FAILED: 'Refund FAILED: needs follow-up',
  NOT_APPLICABLE: 'No refund: nothing was charged',
  MANUAL_REFUND_DUE: 'Refund due at the counter',
  REFUNDED_AT_COUNTER: 'Refunded at the counter',
};

/** A chip with the payment method as a word (never colour alone). */
export function MethodChip({ method }: { method: PaymentMethod | undefined }) {
  const m = method ?? 'CARD';
  return <span className={`status-chip ${m === 'CARD' ? '' : m === 'COUNTER' ? 'is-warn' : 'is-ok'}`}>{METHOD_LABEL[m]}</span>;
}

/** "3 seats" or "2 seats and 4 standing places": what a booking holds. */
export function describeItems(booking: AdminBooking['booking']): string {
  const seats = booking.items.filter((i) => i.type === 'SEAT').length;
  const standing = booking.items.filter((i) => i.type === 'STANDING').reduce((sum, i) => sum + i.qty, 0);
  const parts = [];
  if (seats > 0) parts.push(`${seats} ${seats === 1 ? 'seat' : 'seats'}`);
  if (standing > 0) parts.push(`${standing} standing ${standing === 1 ? 'place' : 'places'}`);
  return parts.join(' and ');
}

/** The sentence that names exactly what cancelling does, by how the booking was paid. */
export function cancelSentence(row: AdminBooking): string {
  const what = `booking ${row.booking.reference} (${describeItems(row.booking)})`;
  const amount = money(row.booking.amount, row.booking.currency);
  switch (row.booking.paymentMethod ?? 'CARD') {
    case 'COUNTER': return `This cancels ${what}. ${amount} was paid at the counter and must be returned at the counter.`;
    case 'COMPLIMENTARY': return `This cancels ${what}. Nothing was charged.`;
    default: return `This cancels ${what} and refunds ${amount} to the card in full.`;
  }
}

/** What the toast says once the booking is cancelled: the refund is described as it really is, never as a plain success. */
function cancelledToast(row: AdminBooking): string {
  const ref = row.booking.reference;
  switch (row.booking.paymentMethod ?? 'CARD') {
    case 'COUNTER': return `Booking ${ref} cancelled. ${money(row.booking.amount, row.booking.currency)} must be returned at the counter.`;
    case 'COMPLIMENTARY': return `Booking ${ref} cancelled. Nothing was charged.`;
    default: return `Booking ${ref} cancelled. The refund is pending until the card payment provider confirms it.`;
  }
}

interface Props {
  /** The booking reference of a taken seat. */
  reference: string;
  /** Called after an action succeeded, so the board and the seat view refresh at once. */
  onChanged: () => void;
}

/**
 * The booking behind a taken seat (admin only): what it holds, how it was paid, what the refund is, who issued it, and the actions: cancel
 * the whole booking with a reason, and for a counter booking that was cancelled, mark it refunded at the counter. It shows only what the support
 * search already shows an admin.
 */
export function BookingAdminPanel({ reference, onChanged }: Props) {
  const toast = useToast();
  const [row, setRow] = useState<AdminBooking | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [asking, setAsking] = useState<'cancel' | 'refund' | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);

  const load = useCallback(async () => {
    const found = await gatewayGet(`/admin/bookings?ref=${encodeURIComponent(reference)}`);
    if (found.status !== 200) {
      setError(readError(found).message);
      return;
    }
    const first = (found.body as AdminBooking[])[0];
    if (!first) {
      setError(`No booking ${reference} was found.`);
      return;
    }
    const detail = await gatewayGet(`/admin/bookings/${first.booking.id}`);
    setRow(detail.status === 200 ? (detail.body as AdminBooking) : first);
    setError(null);
  }, [reference]);

  useEffect(() => {
    setRow(null);
    setAsking(null);
    setMessage(null);
    setReason('');
    void load();
  }, [load]);

  async function cancel() {
    if (!row) return;
    setBusy(true);
    const result = await gatewayRequest('POST', `/admin/bookings/${row.booking.id}/cancel`, { body: { reason: reason.trim() } });
    setBusy(false);
    if (result.status === 200) {
      toast.show(cancelledToast(row));
      setAsking(null);
      setReason('');
      await load();
      onChanged();
    } else {
      const failure = readError(result);
      setMessage({ ok: false, text: failure.code === 'trip_departed' ? 'The train has already left the boarding stop: this booking can no longer be cancelled.' : failure.message });
    }
  }

  async function markRefunded() {
    if (!row) return;
    setBusy(true);
    const result = await gatewayRequest('POST', `/admin/bookings/${row.booking.id}/refunded-at-counter`);
    setBusy(false);
    if (result.status === 200) {
      toast.show(`Booking ${row.booking.reference} marked as refunded at the counter.`);
      setAsking(null);
      await load();
      onChanged();
    } else {
      setMessage({ ok: false, text: readError(result).message });
    }
  }

  if (error) return <ErrorState message={error} onRetry={() => void load()} />;
  if (!row) return <ListSkeleton rows={2} label="Loading the booking…" />;

  const { booking, issue } = row;
  const seatNumbers = booking.items.map((i) => (i.type === 'SEAT' ? `${i.seatNumber} (coach ${i.coachCode}, ${CLASS_LABEL[i.coachClass]})` : `${i.qty} standing in coach ${i.coachCode}`));
  const refundBad = booking.refundStatus === 'REFUND_FAILED';
  const refundOpen = booking.refundStatus === 'REFUND_PENDING' || booking.refundStatus === 'MANUAL_REFUND_DUE';
  const passenger = issue?.walkInName ? `${issue.walkInName} (walk-in)` : row.userName ?? row.userEmail;

  return (
    <section className="board-booking card" aria-label={`Booking ${booking.reference}`}>
      <h3>
        <span className="mono">{booking.reference}</span> <span className="status-chip">{label(booking.status)}</span> <MethodChip method={booking.paymentMethod} />
      </h3>
      <p>{passenger} · {booking.trip.from.stationName} {clock(booking.trip.from.departureTime)} to {booking.trip.to.stationName} {clock(booking.trip.to.arrivalTime)}</p>
      <p>
        <strong>{describeItems(booking)}</strong>: {seatNumbers.join(', ')}
        {booking.items.length > 1 || (booking.items[0] && booking.items[0].qty > 1) ? <span className="muted"> · cancelling cancels all of them</span> : null}
      </p>
      <p>
        {money(booking.amount, booking.currency)}
        {issue?.method === 'COMPLIMENTARY' ? ' (fare value, nothing charged)' : ''}
        {' · '}
        <span className={refundBad ? 'status-fail' : ''}>{REFUND_TEXT[booking.refundStatus]}</span>
        {refundOpen && booking.status === 'CANCELLED' ? ' (not finished)' : ''}
      </p>
      {issue && (
        <p className="muted board-audit">
          Issued by <span className="mono">{issue.issuedBy}</span> on {issue.issuedAt.slice(0, 10)} {issue.issuedAt.slice(11, 16)}
          {issue.note ? `, ${issue.method === 'COMPLIMENTARY' ? 'reason' : 'note'}: ${issue.note}` : ''}
          {issue.counterRefundedBy ? ` · refunded at the counter by ${issue.counterRefundedBy}` : ''}
        </p>
      )}
      {row.cancelledAt && (
        <p className="muted">
          Cancelled by {row.cancelledByRole === 'ADMIN' ? 'an admin' : 'the passenger'}{row.cancelReason ? ` · reason: ${row.cancelReason}` : ''}
        </p>
      )}

      {row.cancellable && asking === null && <p><button type="button" className="button button-danger" onClick={() => { setAsking('cancel'); setMessage(null); }}>Cancel booking</button></p>}
      {booking.status === 'CONFIRMED' && !row.cancellable && <p className="muted">The train has left the boarding stop: this booking can no longer be cancelled.</p>}
      {booking.status === 'CANCELLED' && booking.refundStatus === 'MANUAL_REFUND_DUE' && asking === null && (
        <p><button type="button" className="button" onClick={() => { setAsking('refund'); setMessage(null); }}>Mark refunded at counter</button></p>
      )}

      {asking === 'cancel' && (
        <div className="notice notice-error" role="alertdialog" aria-label="Cancel this booking">
          <p>{cancelSentence(row)}</p>
          <label>Reason (required) <input value={reason} maxLength={500} onChange={(event) => setReason(event.target.value)} /></label>
          <p className="actions">
            <button type="button" className="button button-danger" disabled={busy || reason.trim() === ''} onClick={() => void cancel()}>{busy ? 'Cancelling…' : 'Cancel booking'}</button>
            <button type="button" className="button" disabled={busy} onClick={() => setAsking(null)}>Keep it</button>
          </p>
        </div>
      )}
      {asking === 'refund' && (
        <div className="notice notice-error" role="alertdialog" aria-label="Mark refunded at the counter">
          <p>Confirm that {money(booking.amount, booking.currency)} was handed back to the passenger at the counter for booking {booking.reference}. Your name and the time are recorded.</p>
          <p className="actions">
            <button type="button" className="button button-primary" disabled={busy} onClick={() => void markRefunded()}>{busy ? 'Saving…' : 'Mark refunded at counter'}</button>
            <button type="button" className="button" disabled={busy} onClick={() => setAsking(null)}>Not yet</button>
          </p>
        </div>
      )}
      {message && <p className={`notice ${message.ok ? 'notice-ok' : 'notice-error'}`} role="alert">{message.text}</p>}
    </section>
  );
}
