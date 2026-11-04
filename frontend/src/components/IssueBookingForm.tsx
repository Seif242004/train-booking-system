import { useEffect, useRef, useState, type FormEvent } from 'react';
import { CLASS_LABEL } from '../adminTypes';
import { gatewayGet, gatewayRequest, readError } from '../api';
import type { Booking } from '../bookingTypes';
import { formatRemaining, remainingMs, startCountdown } from '../countdown';
import { money } from '../format';
import type { SeatMap } from '../seatMapTypes';
import { clock } from '../time';

export interface IssuedResult {
  booking: Booking;
  passenger: string;
  method: 'COUNTER' | 'COMPLIMENTARY';
}

interface Props {
  hold: Booking;
  map: SeatMap;
  onBack: () => void;
  onIssued: (result: IssuedResult) => void;
  /** The hold is over (expired or released): go back to picking. */
  onHoldGone: (message: string) => void;
}

type Mode = 'ACCOUNT' | 'WALK_IN';
type Fields = 'lookup' | 'name' | 'email' | 'reason';
const MIN_REASON = 5;

/**
 * The second step of booking from the board: who the passenger is, how it was paid, the order summary with the hold timer, and the button.
 * Errors show on blur and clear as soon as the value is fixed; after a failed submit the first invalid field gets the focus; what was typed stays.
 */
export function IssueBookingForm({ hold, map, onBack, onIssued, onHoldGone }: Props) {
  const [mode, setMode] = useState<Mode>('WALK_IN');
  const [lookup, setLookup] = useState('');
  const [found, setFound] = useState<{ name: string; email: string } | null>(null);
  const [lookupMessage, setLookupMessage] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [phone, setPhone] = useState('');
  const [email, setEmail] = useState('');
  const [method, setMethod] = useState<'COUNTER' | 'COMPLIMENTARY'>('COUNTER');
  const [note, setNote] = useState('');
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState<ReadonlySet<Fields>>(new Set());
  const [submitting, setSubmitting] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [left, setLeft] = useState<number | null>(null);
  const refs = { lookup: useRef<HTMLInputElement>(null), name: useRef<HTMLInputElement>(null), email: useRef<HTMLInputElement>(null), reason: useRef<HTMLInputElement>(null) };

  useEffect(() => {
    const countdown = startCountdown(hold.expiresAt, hold.serverTime, Date.now());
    const tick = () => setLeft(remainingMs(countdown, Date.now()));
    tick();
    const timer = window.setInterval(tick, 500);
    return () => window.clearInterval(timer);
  }, [hold.expiresAt, hold.serverTime]);

  const errors: Partial<Record<Fields, string>> = {};
  if (mode === 'ACCOUNT' && !found) errors.lookup = 'Find the passenger by their exact email first.';
  if (mode === 'WALK_IN' && name.trim() === '') errors.name = 'Enter the passenger\'s full name.';
  if (mode === 'WALK_IN' && email.trim() !== '' && !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email.trim())) errors.email = 'This email address is not valid.';
  if (method === 'COMPLIMENTARY' && reason.trim().length < MIN_REASON) errors.reason = `Enter a reason (at least ${MIN_REASON} characters).`;
  const shown = (field: Fields) => (touched.has(field) ? errors[field] : undefined);
  const touch = (field: Fields) => setTouched((now) => new Set(now).add(field));

  async function find() {
    touch('lookup');
    setFound(null);
    setLookupMessage(null);
    const result = await gatewayGet(`/admin/passengers/lookup?q=${encodeURIComponent(lookup.trim())}`);
    if (result.status === 200) {
      const body = result.body as { name: string; email: string };
      setFound({ name: body.name, email: body.email });
    } else if (result.status === 404) {
      setLookupMessage('No passenger with exactly this email. They may have no account yet: use Walk-in.');
    } else {
      setLookupMessage(readError(result).message);
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setFailure(null);
    setTouched(new Set<Fields>(['lookup', 'name', 'email', 'reason']));
    const first = (['lookup', 'name', 'email', 'reason'] as Fields[]).find((f) => errors[f]);
    if (first) {
      refs[first].current?.focus();
      return;
    }
    setSubmitting(true);
    const passenger = mode === 'ACCOUNT'
      ? { mode, email: found?.email }
      : { mode, name: name.trim(), phone: phone.trim() || undefined, email: email.trim() || undefined };
    const body = method === 'COUNTER' ? { method, note: note.trim() || undefined, passenger } : { method, reason: reason.trim(), passenger };
    const result = await gatewayRequest('POST', `/admin/bookings/${hold.id}/issue`, { body });
    setSubmitting(false);
    if (result.status === 200) {
      onIssued({ booking: result.body as Booking, passenger: mode === 'ACCOUNT' ? found?.name ?? '' : name.trim(), method });
      return;
    }
    const error = readError(result);
    if (error.code === 'hold_expired' || error.code === 'payment_started') {
      onHoldGone(error.code === 'hold_expired' ? 'Your hold expired. Pick the seats again.' : error.message);
    } else if (error.code === 'passenger_not_found') {
      setFound(null);
      setLookupMessage('This passenger could not be found any more. Check the email.');
      refs.lookup.current?.focus();
    } else {
      setFailure(error.details[0]?.message ? `${error.message} ${error.details.map((d) => `${d.field}: ${d.message}`).join('; ')}` : error.message);
    }
  }

  const seatItems = hold.items.filter((i) => i.type === 'SEAT');
  const standing = hold.items.filter((i) => i.type === 'STANDING');
  const from = map.stops.find((s) => s.stopIndex === hold.fromStop);
  const to = map.stops.find((s) => s.stopIndex === hold.toStop);
  const amount = money(hold.amount, hold.currency);

  return (
    <form className="board-issue" onSubmit={(e) => void submit(e)} noValidate>
      <h3>Issue booking</h3>
      {left !== null && <p className={`board-hold-banner${left < 60_000 ? ' is-low' : ''}`} role="timer">{left <= 0 ? 'Your hold expired. Pick the seats again.' : `Hold ends in ${formatRemaining(left)}`}</p>}

      <section aria-label="Order summary" className="card">
        <h4>Order summary</h4>
        <p>
          Train {map.train.number} {map.train.name} · {from?.stationName} {clock(map.journey.departureTime)} to {to?.stationName} {clock(map.journey.arrivalTime)}
        </p>
        <ul>
          {seatItems.map((i) => <li key={`s${i.seatId}`}>Seat {i.seatNumber}, coach {i.coachCode} ({CLASS_LABEL[i.coachClass]}): {money(i.price, hold.currency)}</li>)}
          {standing.map((i) => <li key={`c${i.coachId}`}>{i.qty} standing in coach {i.coachCode} ({CLASS_LABEL[i.coachClass]}): {money(i.price, hold.currency)}</li>)}
        </ul>
        <p><strong>Total {amount}</strong></p>
      </section>

      <fieldset>
        <legend>Passenger</legend>
        <div className="chips" role="group" aria-label="Passenger type">
          <button type="button" className="chip" aria-pressed={mode === 'ACCOUNT'} onClick={() => setMode('ACCOUNT')}>Existing account</button>
          <button type="button" className="chip" aria-pressed={mode === 'WALK_IN'} onClick={() => setMode('WALK_IN')}>Walk-in</button>
        </div>
        {mode === 'ACCOUNT' ? (
          <div className="field">
            <label htmlFor="issue-lookup">Passenger email (exact)</label>
            <div className="board-lookup">
              <input id="issue-lookup" ref={refs.lookup} type="email" value={lookup} autoComplete="off" aria-invalid={shown('lookup') ? true : undefined} aria-describedby="issue-lookup-hint"
                onChange={(e) => { setLookup(e.target.value); setFound(null); setLookupMessage(null); }}
                onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); void find(); } }} onBlur={() => touch('lookup')} />
              <button type="button" className="button" disabled={lookup.trim() === ''} onClick={() => void find()}>Find passenger</button>
            </div>
            <span id="issue-lookup-hint" className="field-error" role="status">
              {lookupMessage ?? (found ? '' : shown('lookup'))}
            </span>
            {found && <p className="notice notice-ok" role="status">{found.name} · {found.email}. The booking will be theirs: it appears in their My bookings and the ticket is emailed to them.</p>}
          </div>
        ) : (
          <>
            <div className="field">
              <label htmlFor="issue-name">Full name (required)</label>
              <input id="issue-name" ref={refs.name} value={name} maxLength={200} autoComplete="off" aria-invalid={shown('name') ? true : undefined} aria-describedby="issue-name-error"
                onChange={(e) => setName(e.target.value)} onBlur={() => touch('name')} />
              <span id="issue-name-error" className="field-error">{shown('name')}</span>
            </div>
            <div className="field">
              <label htmlFor="issue-phone">Phone (optional)</label>
              <input id="issue-phone" value={phone} maxLength={40} inputMode="tel" autoComplete="off" onChange={(e) => setPhone(e.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="issue-email">Email (optional: the ticket is emailed there)</label>
              <input id="issue-email" ref={refs.email} type="email" value={email} maxLength={254} autoComplete="off" aria-invalid={shown('email') ? true : undefined} aria-describedby="issue-email-error"
                onChange={(e) => setEmail(e.target.value)} onBlur={() => touch('email')} />
              <span id="issue-email-error" className="field-error">{shown('email')}</span>
            </div>
          </>
        )}
      </fieldset>

      <fieldset>
        <legend>Payment</legend>
        <label className="board-radio">
          <input type="radio" name="issue-method" checked={method === 'COUNTER'} onChange={() => setMethod('COUNTER')} /> Paid at counter
        </label>
        <label className="board-radio">
          <input type="radio" name="issue-method" checked={method === 'COMPLIMENTARY'} onChange={() => setMethod('COMPLIMENTARY')} /> Complimentary (no charge)
        </label>
        {method === 'COUNTER' ? (
          <>
            <p className="board-collect">Collect <strong>{amount}</strong></p>
            <div className="field">
              <label htmlFor="issue-note">Receipt or note (optional)</label>
              <input id="issue-note" value={note} maxLength={200} autoComplete="off" onChange={(e) => setNote(e.target.value)} />
            </div>
          </>
        ) : (
          <div className="field">
            <label htmlFor="issue-reason">Reason (required)</label>
            <input id="issue-reason" ref={refs.reason} value={reason} maxLength={500} autoComplete="off" aria-invalid={shown('reason') ? true : undefined} aria-describedby="issue-reason-error"
              onChange={(e) => setReason(e.target.value)} onBlur={() => touch('reason')} />
            <span id="issue-reason-error" className="field-error">{shown('reason')}</span>
            <p className="muted">Nothing is charged. The fare value ({amount}) is recorded.</p>
          </div>
        )}
      </fieldset>

      {failure && <p className="notice notice-error" role="alert">{failure}</p>}
      <p className="actions">
        <button type="submit" className="button button-primary" disabled={submitting || (left !== null && left <= 0)}>
          {submitting ? 'Issuing…' : method === 'COUNTER' ? 'Issue booking' : 'Issue complimentary booking'}
        </button>
        <button type="button" className="button" disabled={submitting} onClick={onBack}>Back to the seats</button>
      </p>
    </form>
  );
}
