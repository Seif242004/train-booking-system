// Trip times arrive from the API as local date-times without an offset, for example "2026-11-02T08:00:00", in the
// railway's own time zone. They are shown exactly as they are: no conversion to the browser's time zone.

/** "2026-11-02T08:00:00" becomes "08:00". */
export function clock(dateTime: string | null): string {
  return dateTime ? dateTime.slice(11, 16) : '';
}

/** "2026-11-02T08:00:00" becomes "2026-11-02". */
export function dayOf(dateTime: string): string {
  return dateTime.slice(0, 10);
}

/** How many calendar days later the second time is than the first (0 for the same day, 1 for after midnight). */
export function daysLater(first: string, second: string): number {
  const ms = Date.parse(`${dayOf(second)}T00:00:00Z`) - Date.parse(`${dayOf(first)}T00:00:00Z`);
  return Math.round(ms / 86_400_000);
}

/** "1 h 25 min" between two local date-times of the same zone (the offset change of a daylight saving switch is ignored). */
export function duration(first: string, second: string): string {
  const minutes = Math.round((Date.parse(`${second.slice(0, 16)}:00Z`) - Date.parse(`${first.slice(0, 16)}:00Z`)) / 60_000);
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return h > 0 ? `${h} h ${String(m).padStart(2, '0')} min` : `${m} min`;
}

/** The date `days` after (or before, if negative) a yyyy-MM-dd date, as yyyy-MM-dd. */
export function addDays(day: string, days: number): string {
  return new Date(Date.parse(`${day}T00:00:00Z`) + days * 86_400_000).toISOString().slice(0, 10);
}

/** Today's date as yyyy-MM-dd in the browser's own calendar, for the default of a date box. */
export function todayLocal(): string {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
}

const dayFormat = new Intl.DateTimeFormat('en-GB', { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' });

/** "2026-11-04" becomes "Wed 4 Nov". */
export function dayLabel(day: string): string {
  return dayFormat.format(new Date(`${day}T00:00:00Z`)).replace(',', '');
}

/** "Today, Mon 2 Nov", "Tomorrow, Tue 3 Nov", or just "Wed 4 Nov" for any other day. */
export function dayHeading(day: string, today: string): string {
  if (day === today) return `Today, ${dayLabel(day)}`;
  if (day === addDays(today, 1)) return `Tomorrow, ${dayLabel(day)}`;
  return dayLabel(day);
}

/** "2026-11-14" becomes "Sat 14 Nov 2026". */
export function fullDay(day: string): string {
  return `${dayLabel(day)} ${day.slice(0, 4)}`;
}
