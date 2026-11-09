import type { Booking } from './bookingTypes';

// utc: ISO time -> iCalendar basic format (20261102T060000Z). esc: escapes the characters that have a meaning in iCalendar text values.
const utc = (iso: string) => new Date(iso).toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');
const esc = (text: string) => text.replace(/\\/g, '\\\\').replace(/;/g, '\\;').replace(/,/g, '\\,').replace(/\n/g, '\\n');

/** A plain calendar file (RFC 5545) for the trip of a booking, or null when the booking has no times. No library: it is a few lines of text. */
export function calendarFile(booking: Booking): string | null {
  const start = booking.trip.from.departureTime;
  const end = booking.trip.to.arrivalTime;
  if (!start || !end) return null;
  const summary = `Train ${booking.trip.trainNumber}: ${booking.trip.from.stationName} to ${booking.trip.to.stationName}`;
  // Lines must end with CRLF in iCalendar, which is why the file is joined with \r\n.
  return [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//Train Booking//EN',
    'BEGIN:VEVENT',
    `UID:${booking.reference}@train-booking`,
    `DTSTAMP:${utc(new Date().toISOString())}`,
    `DTSTART:${utc(start)}`,
    `DTEND:${utc(end)}`,
    `SUMMARY:${esc(summary)}`,
    `DESCRIPTION:${esc(`Booking reference ${booking.reference}`)}`,
    `LOCATION:${esc(booking.trip.from.stationName)}`,
    'END:VEVENT',
    'END:VCALENDAR',
    '',
  ].join('\r\n');
}

/** Hands a text file to the browser as a download. */
// Standard browser trick: make a temporary object URL for the content, click a hidden link with the "download" attribute, then free the URL.
export function saveFile(name: string, type: string, content: string | Blob): void {
  const url = URL.createObjectURL(content instanceof Blob ? content : new Blob([content], { type }));
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}
