import { useState, type FormEvent } from 'react';
import type { Station } from '../adminTypes';
import { addDays, dayLabel, todayLocal } from '../time';

export interface SearchValues {
  /** A station code, or (signed out) the name the visitor typed. */
  from: string;
  to: string;
  date: string;
}

interface Props {
  /** The station list. Null while it loads, and for a signed-out visitor (the list needs a token): then the names are typed. */
  stations: Station[] | null;
  /** Where the boxes start. To change them later, give the form a new `key`. */
  initial: SearchValues;
  onSubmit: (values: SearchValues) => void;
  busy?: boolean;
  /** Errors the server reported for a field, shown with the form's own checks. */
  serverErrors?: Partial<Record<keyof SearchValues, string>>;
  /** The large card on the landing page. */
  hero?: boolean;
  /** Prefix of the element ids, so two forms never share one. */
  idPrefix: string;
}

const SearchIcon = () => (
  <svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
    <circle cx="11" cy="11" r="7" />
    <path d="M20 20l-3.5-3.5" />
  </svg>
);

const SwapIcon = () => (
  <svg aria-hidden="true" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M7 4v14" />
    <path d="M3 14l4 4 4-4" />
    <path d="M17 20V6" />
    <path d="M13 10l4-4 4 4" />
  </svg>
);

/** From, to and date, with a swap button and quick-date chips. Used by the landing page and by the search page. */
export function SearchForm({ stations, initial, onSubmit, busy = false, serverErrors = {}, hero = false, idPrefix }: Props) {
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);
  const [date, setDate] = useState(initial.date);
  const [errors, setErrors] = useState<Partial<Record<keyof SearchValues, string>>>({});
  const shown = { ...serverErrors, ...errors };
  const today = todayLocal();
  const quickDays = [0, 1, 2, 3].map((n) => addDays(today, n));

  function submit(event: FormEvent) {
    event.preventDefault();
    const problems: Partial<Record<keyof SearchValues, string>> = {};
    if (from.trim() === '') problems.from = 'Choose where you start.';
    if (to.trim() === '') problems.to = 'Choose where you are going.';
    if (from.trim() !== '' && from.trim().toLowerCase() === to.trim().toLowerCase()) problems.to = 'Choose a different station than where you start.';
    if (date === '') problems.date = 'Choose a date.';
    setErrors(problems);
    if (Object.keys(problems).length > 0) return;
    onSubmit({ from: from.trim(), to: to.trim(), date });
  }

  function stationBox(id: string, label: string, value: string, set: (v: string) => void, error: string | undefined) {
    const common = { id, value, 'aria-invalid': error ? true : undefined, 'aria-describedby': `${id}-hint` } as const;
    return (
      <div className="field">
        <label htmlFor={id}>{label}</label>
        {stations ? (
          <select {...common} onChange={(e) => set(e.target.value)}>
            <option value="">Choose a station…</option>
            {stations.map((s) => (
              <option key={s.id} value={s.code}>
                {s.name}
              </option>
            ))}
          </select>
        ) : (
          <input {...common} type="text" autoComplete="off" placeholder={label === 'From' ? 'For example Cairo' : 'For example Alexandria'} onChange={(e) => set(e.target.value)} />
        )}
        <span id={`${id}-hint`} className="field-error">
          {error}
        </span>
      </div>
    );
  }

  return (
    <form className={hero ? 'search-form search-form-hero' : 'search-form'} aria-label="Search trains" onSubmit={submit} noValidate>
      <div className="search-row">
        {stationBox(`${idPrefix}-from`, 'From', from, setFrom, shown.from)}
        <button
          type="button"
          className="swap"
          aria-label="Swap stations"
          onClick={() => {
            setFrom(to);
            setTo(from);
          }}
        >
          <SwapIcon />
        </button>
        {stationBox(`${idPrefix}-to`, 'To', to, setTo, shown.to)}
        <div className="field">
          <label htmlFor={`${idPrefix}-date`}>Date</label>
          <input id={`${idPrefix}-date`} type="date" value={date} onChange={(e) => setDate(e.target.value)} aria-invalid={shown.date ? true : undefined} aria-describedby={`${idPrefix}-date-hint`} />
          <span id={`${idPrefix}-date-hint`} className="field-error">
            {shown.date}
          </span>
        </div>
        <button type="submit" className="button button-primary search-go" disabled={busy}>
          <SearchIcon />
          {busy ? 'Searching…' : 'Search trains'}
        </button>
      </div>
      <div role="group" aria-label="Quick dates" className="quick-dates">
        {quickDays.map((day, index) => (
          <button key={day} type="button" className="chip" aria-pressed={date === day} onClick={() => setDate(day)}>
            {index === 0 ? 'Today' : index === 1 ? 'Tomorrow' : dayLabel(day)}
          </button>
        ))}
      </div>
    </form>
  );
}
