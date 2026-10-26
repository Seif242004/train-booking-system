import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import { CLASS_LABEL, type Coach, type Seat } from '../adminTypes';
import { gatewayRequest, readError, type ApiErrorDetail } from '../api';

interface NewSeat {
  seatNumber: string;
  row: number;
  column: string;
}

interface SeatForm {
  seatNumber: string;
  row: string;
  column: string;
}

const MAX_SEATS_PER_REQUEST = 200; // the same limit the service enforces

/** Result of reading the generator inputs: the seats to add, or the reason they cannot be added. */
function plan(startRowText: string, rowCountText: string, columnsText: string): { seats: NewSeat[]; problem: string | null } {
  const startRow = Number(startRowText);
  const rowCount = Number(rowCountText);
  const columns = columnsText.replace(/\s+/g, '').toUpperCase();
  const none = (problem: string) => ({ seats: [], problem });

  if (!Number.isInteger(startRow) || startRow < 1 || startRow > 99) return none('Start row must be a whole number from 1 to 99.');
  if (!Number.isInteger(rowCount) || rowCount < 1 || rowCount > 50) return none('Number of rows must be a whole number from 1 to 50.');
  if (startRow + rowCount - 1 > 99) return none('The last row would be above 99.');
  if (!/^[A-Z]{1,8}$/.test(columns)) return none('Columns must be 1 to 8 letters, for example ABCD.');
  if (new Set(columns).size !== columns.length) return none('Each column letter can appear only once.');
  if (rowCount * columns.length > MAX_SEATS_PER_REQUEST) return none(`That is more than ${MAX_SEATS_PER_REQUEST} seats; add them in two steps.`);

  const seats: NewSeat[] = [];
  for (let row = startRow; row < startRow + rowCount; row += 1) {
    for (const column of columns) seats.push({ seatNumber: `${row}${column}`, row, column });
  }
  return { seats, problem: null };
}

/** Seat layout of one coach: the seat map, a generator for rows by columns, and edit or delete of a single seat. */
export function CoachSeats({ coach }: { coach: Coach }) {
  const [seats, setSeats] = useState<Seat[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [startRowText, setStartRowText] = useState<string | null>(null); // null: continue after the last existing row
  const [rowCountText, setRowCountText] = useState('10');
  const [columnsText, setColumnsText] = useState('ABCD');
  const [addError, setAddError] = useState<{ message: string; details: ApiErrorDetail[] } | null>(null);
  const [adding, setAdding] = useState(false);

  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [seatForm, setSeatForm] = useState<SeatForm>({ seatNumber: '', row: '', column: '' });
  const [seatErrors, setSeatErrors] = useState<Record<string, string>>({});
  const [seatFormError, setSeatFormError] = useState<string | null>(null);
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  const load = useCallback(async () => {
    const result = await gatewayRequest('GET', `/admin/coaches/${coach.id}/seats`);
    if (result.status === 200) {
      setSeats(result.body as Seat[]);
      setLoadError(null);
    } else {
      setLoadError(readError(result).message);
    }
  }, [coach.id]);

  useEffect(() => {
    void load();
  }, [load]);

  const nextRow = seats && seats.length > 0 ? Math.max(...seats.map((s) => s.row)) + 1 : 1;
  const effectiveStartRow = startRowText ?? String(Math.min(nextRow, 99));
  const generated = useMemo(() => plan(effectiveStartRow, rowCountText, columnsText), [effectiveStartRow, rowCountText, columnsText]);

  // The seat map: rows down, columns across, empty cells where a position has no seat.
  const rows = useMemo(() => [...new Set((seats ?? []).map((s) => s.row))].sort((a, b) => a - b), [seats]);
  const columns = useMemo(() => [...new Set((seats ?? []).map((s) => s.column))].sort(), [seats]);
  const bySpot = useMemo(() => new Map((seats ?? []).map((s) => [`${s.row}/${s.column}`, s])), [seats]);

  async function addSeats(event: FormEvent) {
    event.preventDefault();
    if (generated.problem || generated.seats.length === 0) return;
    setAdding(true);
    setAddError(null);
    setNotice(null);
    const result = await gatewayRequest('POST', `/admin/coaches/${coach.id}/seats`, { body: generated.seats });
    setAdding(false);

    if (result.status === 201) {
      await load();
      setStartRowText(null);
      setNotice(`Saved ${generated.seats.length} seats. Their class is ${CLASS_LABEL[coach.class]}, the class of this coach.`);
      return;
    }
    const error = readError(result);
    setAddError({ message: `${error.message} Nothing was saved.`, details: error.details });
  }

  function select(seat: Seat) {
    setSelectedId(seat.id);
    setSeatForm({ seatNumber: seat.seatNumber, row: String(seat.row), column: seat.column });
    setSeatErrors({});
    setSeatFormError(null);
    setConfirmingDelete(false);
    setNotice(null);
  }

  function closeEditor() {
    setSelectedId(null);
    setSeatErrors({});
    setSeatFormError(null);
    setConfirmingDelete(false);
  }

  async function saveSeat(event: FormEvent) {
    event.preventDefault();
    setSeatErrors({});
    setSeatFormError(null);
    const body = { seatNumber: seatForm.seatNumber, row: seatForm.row === '' ? null : Number(seatForm.row), column: seatForm.column };
    const result = await gatewayRequest('PUT', `/admin/seats/${selectedId}`, { body });
    if (result.status === 200) {
      const saved = result.body as Seat;
      closeEditor();
      await load();
      setNotice(`Seat ${saved.seatNumber} updated.`);
      return;
    }
    const error = readError(result);
    if (error.details.length > 0) {
      setSeatErrors(Object.fromEntries(error.details.map((d) => [d.field, d.message])));
    } else {
      setSeatFormError(error.message);
    }
  }

  async function deleteSeat(seat: Seat) {
    const result = await gatewayRequest('DELETE', `/admin/seats/${seat.id}`);
    if (result.status === 204 || result.status === 404) {
      closeEditor();
      await load();
      setNotice(`Seat ${seat.seatNumber} deleted.`);
    } else {
      setSeatFormError(readError(result).message);
    }
  }

  const selected = seats?.find((s) => s.id === selectedId) ?? null;

  return (
    <div className="panel" aria-label={`Seats of coach ${coach.code}`}>
      <h3>
        Seats of coach {coach.code} <span className="badge">{CLASS_LABEL[coach.class]} class</span>
      </h3>
      <p className="muted">Every seat has the class of its coach. It is set by the server and changes when the coach's class changes.</p>

      <p className="notice-slot" role="status" aria-live="polite">
        {notice && <span className="notice notice-ok">{notice}</span>}
      </p>

      {loadError && <p className="notice notice-error">{loadError}</p>}
      {seats === null && !loadError && <p className="muted">Loading…</p>}
      {seats?.length === 0 && <p className="muted">This coach has no seats yet. Generate a layout below.</p>}

      {seats && seats.length > 0 && (
        <>
          <p className="muted">{seats.length} seats. Select a seat to edit or delete it.</p>
          <div className="seat-grid" style={{ gridTemplateColumns: `2.5rem repeat(${columns.length}, minmax(3rem, 4rem))` }} role="group" aria-label="Seat map">
            <span />
            {columns.map((column) => (
              <span key={column} className="seat-label">{column}</span>
            ))}
            {rows.map((row) => (
              <FragmentRow key={row} row={row} columns={columns} bySpot={bySpot} selectedId={selectedId} onSelect={select} />
            ))}
          </div>
        </>
      )}

      {selected && (
        <form className="form seat-editor" onSubmit={saveSeat} noValidate>
          <h4>Edit seat {selected.seatNumber}</h4>
          <div className="inline-fields">
            <div className="field">
              <label htmlFor="seat-number">Seat number</label>
              <input id="seat-number" value={seatForm.seatNumber} maxLength={10} autoComplete="off"
                onChange={(e) => setSeatForm({ ...seatForm, seatNumber: e.target.value })}
                aria-invalid={seatErrors.seatNumber ? true : undefined} aria-describedby="seat-number-hint" />
              <span id="seat-number-hint" className="field-error">{seatErrors.seatNumber}</span>
            </div>
            <div className="field">
              <label htmlFor="seat-row">Row</label>
              <input id="seat-row" type="number" min={1} max={99} value={seatForm.row}
                onChange={(e) => setSeatForm({ ...seatForm, row: e.target.value })}
                aria-invalid={seatErrors.row ? true : undefined} aria-describedby="seat-row-hint" />
              <span id="seat-row-hint" className="field-error">{seatErrors.row}</span>
            </div>
            <div className="field">
              <label htmlFor="seat-column">Column</label>
              <input id="seat-column" value={seatForm.column} maxLength={3} autoComplete="off"
                onChange={(e) => setSeatForm({ ...seatForm, column: e.target.value })}
                aria-invalid={seatErrors.column ? true : undefined} aria-describedby="seat-column-hint" />
              <span id="seat-column-hint" className="field-error">{seatErrors.column}</span>
            </div>
          </div>
          {seatFormError && <p className="notice notice-error">{seatFormError}</p>}
          <div className="actions">
            <button type="submit" className="button button-primary">Save seat</button>
            {confirmingDelete ? (
              <>
                <span>Delete seat {selected.seatNumber}?</span>
                <button type="button" className="button button-danger" onClick={() => void deleteSeat(selected)}>Delete seat</button>
                <button type="button" className="button" onClick={() => setConfirmingDelete(false)}>Keep</button>
              </>
            ) : (
              <button type="button" className="button" onClick={() => setConfirmingDelete(true)}>Delete seat</button>
            )}
            <button type="button" className="button" onClick={closeEditor}>Close</button>
          </div>
        </form>
      )}

      <form className="form" onSubmit={addSeats} noValidate>
        <h4>Generate a seat layout</h4>
        <p className="muted">
          Seats are numbered row then column, for example 1A, 1B. The whole list is saved together, or not at all.
        </p>
        <div className="inline-fields">
          <div className="field">
            <label htmlFor="gen-start">Start at row</label>
            <input id="gen-start" type="number" min={1} max={99} value={effectiveStartRow} onChange={(e) => setStartRowText(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="gen-rows">Number of rows</label>
            <input id="gen-rows" type="number" min={1} max={50} value={rowCountText} onChange={(e) => setRowCountText(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="gen-columns">Columns (letters)</label>
            <input id="gen-columns" value={columnsText} maxLength={8} autoComplete="off" onChange={(e) => setColumnsText(e.target.value)} />
          </div>
        </div>
        <p className={generated.problem ? 'field-error' : 'muted'} aria-live="polite">
          {generated.problem ??
            `Will add ${generated.seats.length} seats: ${generated.seats[0].seatNumber} to ${generated.seats[generated.seats.length - 1].seatNumber}.`}
        </p>

        {addError && (
          <div className="notice notice-error" role="alert">
            <p>{addError.message}</p>
            {addError.details.length > 0 && (
              <ul>
                {addError.details.slice(0, 8).map((d) => (
                  <li key={`${d.field}-${d.message}`}>
                    <span className="mono">{d.field}</span>: {d.message}
                  </li>
                ))}
                {addError.details.length > 8 && <li>…and {addError.details.length - 8} more</li>}
              </ul>
            )}
          </div>
        )}

        <div className="actions">
          <button type="submit" className="button button-primary" disabled={adding || generated.problem !== null}>
            {generated.problem === null ? `Save ${generated.seats.length} seats` : 'Save seats'}
          </button>
        </div>
      </form>
    </div>
  );
}

/** One row of the seat map: the row number, then a cell per column. */
function FragmentRow(props: {
  row: number;
  columns: string[];
  bySpot: Map<string, Seat>;
  selectedId: number | null;
  onSelect: (seat: Seat) => void;
}) {
  return (
    <>
      <span className="seat-label">{props.row}</span>
      {props.columns.map((column) => {
        const seat = props.bySpot.get(`${props.row}/${column}`);
        return seat ? (
          <button key={column} type="button" className={seat.id === props.selectedId ? 'seat seat-selected' : 'seat'}
            onClick={() => props.onSelect(seat)} aria-label={`Seat ${seat.seatNumber}, row ${seat.row} column ${seat.column}`}>
            {seat.seatNumber}
          </button>
        ) : (
          <span key={column} className="seat-empty" />
        );
      })}
    </>
  );
}
