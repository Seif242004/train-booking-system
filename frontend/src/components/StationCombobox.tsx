import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import type { Station } from '../adminTypes';

interface Props {
  id: string;
  label: string;
  stations: Station[];
  /** The code of the chosen station, or ''. */
  value: string;
  onChange: (code: string) => void;
  onBlurCheck?: (problem: string | null) => void;
  error?: string;
  placeholder?: string;
}

function matches(station: Station, text: string): boolean {
  const wanted = text.trim().toLowerCase();
  return wanted === '' || station.name.toLowerCase().includes(wanted) || station.code.toLowerCase().includes(wanted) || station.city.toLowerCase().includes(wanted);
}

/**
 * A station field you can type in: the list narrows as you type, arrow keys move through it, Enter picks, Escape closes, and the x clears
 * the field. It is the ARIA combobox pattern with a list box. Only a station of the list can be chosen.
 */
export function StationCombobox({ id, label, stations, value, onChange, onBlurCheck, error, placeholder }: Props) {
  const listId = useId();
  const chosen = stations.find((s) => s.code === value);
  const [text, setText] = useState(chosen?.name ?? '');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const input = useRef<HTMLInputElement>(null);

  // The value can change from outside (swap, the form being filled): show the station's name.
  // Typing clears the chosen station, and that must not wipe what is being typed.
  const typing = useRef(false);
  useEffect(() => {
    if (value === '' && typing.current) {
      typing.current = false;
      return;
    }
    setText(chosen?.name ?? '');
  }, [chosen?.name, value]);

  const options = useMemo(() => stations.filter((s) => matches(s, text === chosen?.name ? '' : text)), [stations, text, chosen?.name]);

  function pick(station: Station) {
    onChange(station.code);
    setText(station.name);
    setOpen(false);
    onBlurCheck?.(null);
  }

  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      if (!open) setOpen(true);
      else setActive((i) => Math.min(i + 1, options.length - 1));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      setActive((i) => Math.max(i - 1, 0));
    } else if (event.key === 'Enter' && open && options[active]) {
      event.preventDefault();
      pick(options[active]);
    } else if (event.key === 'Escape' && open) {
      event.preventDefault();
      setOpen(false);
    }
  }

  function onBlur() {
    setOpen(false);
    const typed = text.trim();
    if (typed === '') {
      if (value !== '') onChange('');
      onBlurCheck?.(null);
      return;
    }
    // Typing a whole name or code and leaving the field is enough; anything else must be picked from the list.
    const exact = stations.find((s) => s.name.toLowerCase() === typed.toLowerCase() || s.code.toLowerCase() === typed.toLowerCase());
    if (exact) {
      pick(exact);
      return;
    }
    if (chosen && typed === chosen.name) return;
    onChange('');
    onBlurCheck?.('Choose a station from the list.');
  }

  function clear() {
    onChange('');
    setText('');
    setOpen(false);
    input.current?.focus();
  }

  const showList = open && options.length > 0;
  const hintId = `${id}-hint`;

  return (
    <div className="field combobox">
      <label htmlFor={id}>{label}</label>
      <div className="combobox-box">
        <input
          ref={input}
          id={id}
          type="text"
          role="combobox"
          autoComplete="off"
          aria-expanded={showList}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={showList ? `${id}-opt-${active}` : undefined}
          aria-invalid={error ? true : undefined}
          aria-describedby={hintId}
          placeholder={placeholder}
          value={text}
          onChange={(event) => {
            setText(event.target.value);
            setOpen(true);
            setActive(0);
            if (value !== '') {
              typing.current = true;
              onChange('');
            }
            if (error) onBlurCheck?.(null);
          }}
          onFocus={() => setOpen(true)}
          onBlur={onBlur}
          onKeyDown={onKeyDown}
        />
        {text !== '' && (
          <button type="button" className="combobox-clear" aria-label={`Clear ${label}`} onMouseDown={(event) => event.preventDefault()} onClick={clear}>
            <svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round">
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        )}
        {showList && (
          <ul id={listId} className="combobox-list" role="listbox" aria-label={`${label} stations`}>
            {options.map((station, index) => (
              <li
                key={station.id}
                id={`${id}-opt-${index}`}
                role="option"
                aria-selected={index === active}
                className={index === active ? 'is-active' : undefined}
                // mousedown keeps the focus in the input, so the click is not lost to the blur that closes the list
                onMouseDown={(event) => {
                  event.preventDefault();
                  pick(station);
                }}
                onMouseEnter={() => setActive(index)}
              >
                <span>{station.name}</span>
                <span className="combobox-code">{station.code}</span>
              </li>
            ))}
          </ul>
        )}
      </div>
      <span id={hintId} className="field-error">{error}</span>
      <span className="sr-only" role="status">{open && text !== '' && options.length === 0 ? 'No station matches.' : ''}</span>
    </div>
  );
}
