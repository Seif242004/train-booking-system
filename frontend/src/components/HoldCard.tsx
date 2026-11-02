import type { ReactNode } from 'react';
import { formatRemaining } from '../countdown';

// The hold lasts 5 minutes from the first pick (the server decides; this only draws the bar against it).
const HOLD_MS = 5 * 60 * 1000;
const LOW_MS = 60 * 1000;

interface Props {
  /** Milliseconds left by the server's clock, or null before the first reading. */
  left: number | null;
  label: string;
  /** Draw the remaining-time bar (the seat map does; the payment page only shows the time). */
  bar?: boolean;
  timeIsUp: boolean;
  note?: ReactNode;
}

/** The countdown of the hold: dark card, big time, and a bar that turns coral in the last minute. */
export function HoldCard({ left, label, bar = false, timeIsUp, note }: Props) {
  const ms = left ?? HOLD_MS;
  const percent = Math.max(0, Math.min(100, Math.round((ms / HOLD_MS) * 100)));
  return (
    <div className="hold-card on-dark">
      <div className="hold-row">
        <span className="hold-label">{label}</span>
        <span role="timer" aria-live="off" className="hold-time">
          {formatRemaining(ms)}
        </span>
      </div>
      {bar && (
        <div className="hold-track" aria-hidden="true">
          <div className={ms < LOW_MS ? 'hold-bar is-low' : 'hold-bar'} style={{ width: `${percent}%` }} />
        </div>
      )}
      {timeIsUp && <p className="hold-note">Time is up. Checking with the server…</p>}
      {note && <p className="hold-note">{note}</p>}
    </div>
  );
}
