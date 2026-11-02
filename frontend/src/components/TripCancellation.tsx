import { useCallback, useEffect, useState } from 'react';
import { gatewayGet, gatewayRequest, readError } from '../api';

interface TripCancellationStatus {
  tripId: number;
  /** Booking & Seat's copy of the trip: OPEN, CANCELLING ("CANCELLED - bookings pending") or CANCELLED. */
  status: 'OPEN' | 'CANCELLING' | 'CANCELLED';
  reason: string | null;
  trainScheduleNotified: boolean;
  pendingHolds: number;
  pendingConfirmed: number;
  cancelledBookings: number;
  refundsFailed: number;
}

interface Props {
  tripId: number;
  /** The trip's status in Train & Schedule (CANCELLED once Booking & Seat told it). */
  tripStatus: 'ACTIVE' | 'CANCELLED';
  /** Called after a cancellation was requested, so the list reloads the status of the trip. */
  onChanged: () => void;
}

/**
 * Admin (Flow 8): cancel a whole trip with a reason, and see how far the cancellation is. A trip that is only partly done is shown as
 * "CANCELLED - bookings pending" with what is left, and the same button repeats the cancellation (a retry is safe: bookings that are already
 * cancelled are skipped).
 */
export function TripCancellation({ tripId, tripStatus, onChanged }: Props) {
  const [status, setStatus] = useState<TripCancellationStatus | null>(null);
  const [asking, setAsking] = useState(false);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const load = useCallback(async () => {
    const result = await gatewayGet(`/admin/trips/${tripId}/cancellation`);
    if (result.status === 200) setStatus(result.body as TripCancellationStatus);
  }, [tripId]);

  useEffect(() => {
    // Also for a trip Train & Schedule still shows ACTIVE: its cancellation may be pending there (Train & Schedule was not reachable yet).
    void load();
  }, [tripStatus, load]);

  async function cancel() {
    setBusy(true);
    setProblem(null);
    const result = await gatewayRequest('POST', `/admin/trips/${tripId}/cancel`, { body: { reason: reason.trim() } });
    setBusy(false);
    if (result.status === 200) {
      setStatus(result.body as TripCancellationStatus);
      setAsking(false);
      setReason('');
      onChanged();
    } else {
      const failure = readError(result);
      setProblem(failure.code === 'trip_departed' ? 'This trip has already left its first stop, so it can no longer be cancelled.' : failure.details[0]?.message ? `Reason ${failure.details[0].message}.` : failure.message);
    }
  }

  const pending = status?.status === 'CANCELLING';

  return (
    <div className="trip-cancellation">
      {status && status.status !== 'OPEN' && (
        <p role="status">
          <span className={pending ? 'badge' : 'badge badge-ok'}>{pending ? 'CANCELLED - bookings pending' : 'CANCELLED'}</span>{' '}
          <span className="muted">
            {status.cancelledBookings} booking{status.cancelledBookings === 1 ? '' : 's'} cancelled (refunds follow)
            {pending && `, ${status.pendingConfirmed} confirmed and ${status.pendingHolds} hold${status.pendingHolds === 1 ? '' : 's'} left${status.trainScheduleNotified ? '' : ', search not updated yet'}`}
            {status.refundsFailed > 0 && ` · ${status.refundsFailed} refund${status.refundsFailed === 1 ? '' : 's'} failed: see Support`}
            {status.reason && ` · reason: ${status.reason}`}
          </span>
        </p>
      )}

      {((tripStatus === 'ACTIVE' && status?.status !== 'CANCELLED') || pending) && !asking && (
        <button type="button" className="button button-danger" onClick={() => setAsking(true)}>
          {pending ? 'Retry cancellation' : 'Cancel trip'}
        </button>
      )}

      {asking && (
        <div className="notice notice-error" role="alertdialog" aria-label="Cancel this trip">
          <p>
            Cancel this whole trip? Every confirmed booking is cancelled and <strong>fully refunded</strong>, tickets are invalidated, passengers are emailed,
            and people holding seats are sent back to the seat map. This cannot be undone.
          </p>
          <label>
            Reason (shown to the passengers)
            <input type="text" value={reason} maxLength={500} onChange={(event) => setReason(event.target.value)} />
          </label>
          {problem && <p role="alert">{problem}</p>}
          <p className="actions">
            <button type="button" className="button button-danger" disabled={busy || reason.trim() === ''} onClick={() => void cancel()}>
              {busy ? 'Cancelling…' : 'Yes, cancel the trip'}
            </button>
            <button type="button" className="button" disabled={busy} onClick={() => { setAsking(false); setProblem(null); }}>Keep the trip</button>
          </p>
        </div>
      )}
    </div>
  );
}
