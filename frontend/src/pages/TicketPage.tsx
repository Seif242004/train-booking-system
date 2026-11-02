import { useEffect, useState } from 'react';
import { Link, Navigate, useParams } from 'react-router-dom';
import { gatewayGet, gatewayRequest, readError } from '../api';
import { useAuth } from '../auth/AuthContext';
import type { Booking } from '../bookingTypes';
import { BookingSteps } from '../components/BookingSteps';
import { TicketView } from '../components/TicketView';
import { ErrorState } from '../components/ErrorState';
import { useToast } from '../components/Toasts';
import { lastSearchQuery } from '../recentSearches';

/** The confirmed booking: boarding pass and next actions (`/bookings/:id/ticket`). A booking that is not confirmed goes back to where it belongs. */
export function TicketPage() {
  const { id } = useParams();
  const { user } = useAuth();
  const toast = useToast();
  const [booking, setBooking] = useState<Booking | null>(null);
  const [error, setError] = useState<string | null>(null);
  // "Email my ticket again" on the confirmed booking (FR-T4).
  const [resend, setResend] = useState<{ state: 'idle' | 'sending' | 'done' | 'error'; message?: string }>({ state: 'idle' });

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const result = await gatewayGet(`/bookings/${id}`);
      if (cancelled) return;
      if (result.status === 200) setBooking(result.body as Booking);
      else setError(readError(result).message);
    })();
    return () => {
      cancelled = true;
    };
  }, [id]);

  async function resendTicket() {
    setResend({ state: 'sending' });
    const result = await gatewayRequest('POST', `/bookings/${id}/resend-ticket`);
    if (result.status === 202) {
      setResend({ state: 'done' });
      toast.show('Ticket email sent. It is on its way to your inbox.');
    }
    else setResend({ state: 'error', message: readError(result).message });
  }

  if (error) {
    return (
      <section>
        <h1>Your ticket</h1>
        <ErrorState message={error} action={<Link className="button" to="/bookings">Go to my bookings</Link>} />
      </section>
    );
  }
  if (!booking) {
    return (
      <section aria-busy="true">
        <h1 className="sr-only">Your ticket</h1>
        <p className="muted">Loading your ticket…</p>
      </section>
    );
  }
  if (booking.status === 'PENDING_PAYMENT') return <Navigate to={`/bookings/${booking.id}/pay`} replace />;
  if (booking.status !== 'CONFIRMED') return <Navigate to="/bookings" replace />;

  return (
    <section>
      <BookingSteps current="ticket" links={{ search: `/search${lastSearchQuery() ? `?${lastSearchQuery()}` : ''}` }} />
      <TicketView booking={booking} passenger={user?.name ?? user?.email ?? null} resend={resend} onResend={() => void resendTicket()} />
    </section>
  );
}
