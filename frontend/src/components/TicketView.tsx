import { Link } from 'react-router-dom';
import { CLASS_LABEL } from '../adminTypes';
import type { Booking } from '../bookingTypes';
import { calendarFile, saveFile } from '../ics';
import { clock, fullDay } from '../time';
import { BrandLogo } from './BrandLogo';

interface Props {
  booking: Booking;
  passenger: string | null;
  resend: { state: 'idle' | 'sending' | 'done' | 'error'; message?: string };
  onResend: () => void;
}

/**
 * The confirmed booking as a boarding pass: the check draws in, then the ticket rises. It shows the real data of the booking. The ticket's QR
 * code and PDF are made by the Ticket service and sent by e-mail: nothing in the Gateway serves them to the browser, so this page points to the
 * e-mail and offers to send it again.
 */
export function TicketView({ booking, passenger, resend, onResend }: Props) {
  const seats = booking.items.filter((item) => item.type === 'SEAT').map((item) => item.seatNumber);
  const standing = booking.items.filter((item) => item.type === 'STANDING').reduce((sum, item) => sum + item.qty, 0);
  const classes = [...new Set(booking.items.map((item) => CLASS_LABEL[item.coachClass] + ' class'))];
  const coaches = [...new Set(booking.items.map((item) => item.coachCode))];
  const places = [seats.join(', '), standing > 0 ? `${standing} standing ${standing === 1 ? 'place' : 'places'}` : ''].filter(Boolean).join(' + ');

  const facts: [string, string][] = [
    ['Date', fullDay(booking.trip.departureDate)],
    ['Train', `${booking.trip.trainNumber} ${booking.trip.trainName}`],
    ['Passenger', passenger ?? 'Passenger'],
    ['Class', classes.join(', ')],
    ['Coach', coaches.join(', ')],
    [seats.length > 0 ? 'Seats' : 'Places', places],
  ];

  return (
    <div className="ticket-page">
      <div className="ticket-confirmation">
        <svg aria-hidden="true" width="84" height="84" viewBox="0 0 64 64" fill="none" className="check-draw">
          <circle className="check-ring" cx="32" cy="32" r="27" strokeWidth="4" strokeLinecap="round" transform="rotate(-90 32 32)" />
          <path className="check-tick" d="M20 33l8 8 16-17" strokeWidth="4.5" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
        <h1>You are booked</h1>
        <p>Your booking is confirmed. We are emailing your ticket (a PDF with its QR code); it can take a moment to arrive.</p>
      </div>

      <article className="ticket" aria-label="Ticket">
        <div className="ticket-top on-dark">
          <BrandLogo height={26} home={false} />
          <span>Train ticket</span>
        </div>
        <div className="ticket-body">
          <div className="ticket-main">
            <div className="ticket-route">
              <div>
                <p className="ticket-clock">{clock(booking.trip.from.departureTime)}</p>
                <p className="ticket-station">{booking.trip.from.stationName}</p>
              </div>
              <div className="ticket-line" aria-hidden="true">
                <span />
                <span className="ticket-line-bar" />
                <span />
              </div>
              <div className="ticket-end">
                <p className="ticket-clock">{clock(booking.trip.to.arrivalTime)}</p>
                <p className="ticket-station">{booking.trip.to.stationName}</p>
              </div>
            </div>
            <dl className="ticket-facts">
              {facts.map(([term, value]) => (
                <div key={term}>
                  <dt>{term}</dt>
                  <dd>{value}</dd>
                </div>
              ))}
            </dl>
          </div>
          <div className="ticket-perforation" aria-hidden="true" />
          <div className="ticket-code">
            <div className="ticket-qr-note">
              <svg aria-hidden="true" width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
                <rect x="3" y="5" width="18" height="14" rx="3" />
                <path d="M4 7l8 6 8-6" />
              </svg>
              <p>Your QR code is in the PDF we emailed you.</p>
            </div>
            <p className="ticket-ref-label">Booking reference</p>
            <p className="ticket-ref">{booking.reference}</p>
          </div>
        </div>
      </article>

      <div className="ticket-actions">
        <button type="button" className="button button-primary" disabled={resend.state === 'sending'} onClick={onResend}>
          <svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
            <rect x="3" y="5" width="18" height="14" rx="3" />
            <path d="M4 7l8 6 8-6" />
          </svg>
          Email my ticket again
        </button>
        <button type="button" className="button" onClick={() => { const ics = calendarFile(booking); if (ics) saveFile(`train-${booking.reference}.ics`, 'text/calendar', ics); }}>
          Add to calendar
        </button>
        <Link className="button" to="/bookings">View my bookings</Link>
        <Link className="button" to="/search">Search another trip</Link>
      </div>
      <div className="ticket-status" aria-live="polite">
        {resend.state === 'done' && <p role="status">The ticket is on its way.</p>}
        {resend.state === 'error' && <p role="alert" className="notice notice-error">{resend.message}</p>}
      </div>
    </div>
  );
}
