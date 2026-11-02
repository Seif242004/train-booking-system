import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { gatewayGet } from '../api';
import { useAuth } from '../auth/AuthContext';
import type { Booking } from '../bookingTypes';
import { formatRemaining, remainingMs, startCountdown, type Countdown } from '../countdown';

const REFRESH_MS = 30_000;

/**
 * A slim bar on every page while the signed-in user has a running hold: "Your seats are held for 3:42. Continue to payment". It uses
 * `GET /bookings` (My bookings), so nothing new on the server. The seat map and the payment page show the hold themselves and hide it.
 */
export function HoldBanner() {
  const { authenticated } = useAuth();
  const { pathname } = useLocation();
  const [hold, setHold] = useState<Booking | null>(null);
  const [left, setLeft] = useState(0);
  const countdown = useRef<Countdown | null>(null);

  const refresh = useCallback(async () => {
    try {
      const result = await gatewayGet('/bookings');
      if (result.status !== 200 || !Array.isArray(result.body)) return;
      const found = (result.body as { booking: Booking }[]).map((x) => x.booking).find((b) => b.status === 'PENDING_PAYMENT') ?? null;
      countdown.current = found ? startCountdown(found.expiresAt, found.serverTime, Date.now()) : null;
      setHold(found);
    } catch {
      // The banner is a convenience: a failure (session ended, offline) is reported elsewhere.
    }
  }, []);

  useEffect(() => {
    if (!authenticated) {
      setHold(null);
      return undefined;
    }
    void refresh();
    const timer = window.setInterval(() => void refresh(), REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [authenticated, pathname, refresh]);

  useEffect(() => {
    if (!hold) return undefined;
    const tick = () => {
      const ms = countdown.current ? remainingMs(countdown.current, Date.now()) : 0;
      setLeft(ms);
      if (ms <= 0) {
        setHold(null); // the server releases it; the next refresh confirms
        void refresh();
      }
    };
    tick();
    const timer = window.setInterval(tick, 500);
    return () => window.clearInterval(timer);
  }, [hold, refresh]);

  if (!hold) return null;
  // These two pages are the hold: they show it in full.
  if (pathname === `/trips/${hold.tripId}/seats` || pathname === `/bookings/${hold.id}/pay`) return null;

  return (
    <div className="hold-banner on-dark" role="region" aria-label="Seats on hold">
      <p>
        Your seats are held for <strong role="timer" aria-live="off" className="hold-banner-time">{formatRemaining(left)}</strong>.
      </p>
      <Link className="hold-banner-link" to={`/bookings/${hold.id}/pay`}>Continue to payment</Link>
    </div>
  );
}
