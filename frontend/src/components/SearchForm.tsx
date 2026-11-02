import { useState, type FormEvent } from 'react';
import type { Station } from '../adminTypes';
import { addDays, dayLabel, todayLocal } from '../time';
import { StationCombobox } from './StationCombobox';

export interface SearchValues {
  /** A station code, or (signed out) the name the visitor typed. */
  from: string;
  to: string;
  date: string;
}

type Field = keyof SearchValues;

interface Props {
  /** The station list. Null while it loads, and for a signed-out visitor (the list needs a token): then the names are typed. */
  stations: Station[] | null;
  /** Where the boxes start. To change them later, give the form a new `key`. */
  initial: SearchValues;
  onSubmit: (values: SearchValues) => void;
  busy?: boolean;
  /** Errors the server reported for a field, shown with the form's own checks. */
  serverErrors?: Partial<Record<Field, string>>;
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

/**
 * From, to and date, with a swap button and quick-date chips. Used by the landing page and by the search page. Checks run when the user
 * leaves a field, an error disappears as soon as the field is fixed, what was typed is kept, and a failed submit moves the focus to the
 * first field with a problem.
 */
export function SearchForm({ stations, initial, onSubmit, busy = false, serverErrors = {}, hero = false, idPrefix }: Props) {
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);
  const [date, setDate] = useState(initial.date);
  const [errors, setErrors] = useState<Partial<Record<Field, string>>>({});
  const shown = { ...serverErrors, ...errors };
  const today = todayLocal();
  const quickDays = [0, 1, 2, 3].map((n) => addDays(today, n));
  const ids: Record<Field, string> = { from: `${idPrefix}-from`, to: `${idPrefix}-to`, date: `${idPrefix}-date` };

  function check(values: SearchValues): Partial<Record<Field, string>> {
    const problems: Partial<Record<Field, string>> = {};
    if (values.from.trim() === '') problems.from = 'Choose where you start.';
    if (values.to.trim() === '') problems.to = 'Choose where you are going.';
    if (values.from.trim() !== '' && values.from.trim().toLowerCase() === values.to.trim().toLowerCase()) problems.to = 'Choose a different station than where you start.';
    if (values.date === '') problems.date = 'Choose a date.';
    return problems;
  }

  /** Validates one field when the user leaves it (an untouched field is not blamed). */
  function blurCheck(field: Field, values: SearchValues = { from, to, date }) {
    const problem = check(values)[field];
    setErrors((now) => ({ ...now, [field]: problem }));
  }

  function setField(field: Field, value: string) {
    if (field === 'from') setFrom(value);
    else if (field === 'to') setTo(value);
    else setDate(value);
    // The error goes as soon as the field is fixed.
    if (errors[field] && value.trim() !== '') setErrors((now) => ({ ...now, [field]: undefined }));
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    const problems = check({ from, to, date });
    setErrors(problems);
    const firstBad = (['from', 'to', 'date'] as Field[]).find((f) => problems[f]);
    if (firstBad) {
      document.getElementById(ids[firstBad])?.focus();
      return;
    }
    onSubmit({ from: from.trim(), to: to.trim(), date });
  }

  function stationBox(field: 'from' | 'to', label: string, value: string, placeholder: string) {
    if (stations) {
      return (
        <StationCombobox
          id={ids[field]}
          label={label}
          stations={stations}
          value={value}
          placeholder={placeholder}
          error={shown[field]}
          onChange={(code) => setField(field, code)}
          onBlurCheck={(problem) => setErrors((now) => ({ ...now, [field]: problem ?? undefined }))}
        />
      );
    }
    // Signed out: the station list needs a token, so the visitor types the name and Keycloak comes first.
    return (
      <div className="field">
        <label htmlFor={ids[field]}>{label}</label>
        <input id={ids[field]} type="text" autoComplete="off" placeholder={placeholder} value={value}
          aria-invalid={shown[field] ? true : undefined} aria-describedby={`${ids[field]}-hint`}
          onChange={(e) => setField(field, e.target.value)} onBlur={() => blurCheck(field)} />
        <span id={`${ids[field]}-hint`} className="field-error">{shown[field]}</span>
      </div>
    );
  }

  return (
    <form className={hero ? 'search-form search-form-hero' : 'search-form'} aria-label="Search trains" onSubmit={submit} noValidate>
      <div className="search-row">
        {stationBox('from', 'From', from, 'For example Cairo')}
        <button
          type="button"
          className="swap"
          aria-label="Swap stations"
          onClick={() => {
            setFrom(to);
            setTo(from);
            setErrors((now) => ({ ...now, from: undefined, to: undefined }));
          }}
        >
          <SwapIcon />
        </button>
        {stationBox('to', 'To', to, 'For example Alexandria')}
        <div className="field">
          <label htmlFor={ids.date}>Date</label>
          <input id={ids.date} type="date" value={date} onChange={(e) => setField('date', e.target.value)} onBlur={() => blurCheck('date')}
            aria-invalid={shown.date ? true : undefined} aria-describedby={`${ids.date}-hint`} />
          <span id={`${ids.date}-hint`} className="field-error">
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
          <button key={day} type="button" className="chip" aria-pressed={date === day} onClick={() => setField('date', day)}>
            {index === 0 ? 'Today' : index === 1 ? 'Tomorrow' : dayLabel(day)}
          </button>
        ))}
      </div>
    </form>
  );
}
