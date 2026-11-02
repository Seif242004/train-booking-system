import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { CLASS_LABEL, COACH_CLASSES, type CoachClass, type Fare, type Route } from '../adminTypes';
import { AdminHeader } from '../components/AdminHeader';
import { gatewayRequest, readError } from '../api';
import { ListSkeleton } from '../components/ListSkeleton';

/** The grid is keyed by segment and class, for example "0/FIRST". The cell holds the text of the amount box. */
type Cells = Record<string, string>;
const key = (segment: number, coachClass: CoachClass) => `${segment}/${coachClass}`;

function cellsFrom(fares: Fare[]): Cells {
  return Object.fromEntries(fares.map((f) => [key(f.segmentIndex, f.class), f.amount.toFixed(2)]));
}

/** Fares of one route: the price of each segment (the leg between two stops) for each class, in EGP (FR-A8, 9.2). */
export function RouteFares() {
  const { id } = useParams();
  const routeId = Number(id);

  const [route, setRoute] = useState<Route | null>(null);
  const [cells, setCells] = useState<Cells>({});
  const [saved, setSaved] = useState<Cells>({});
  const [notFound, setNotFound] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [cellErrors, setCellErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    const [routeResult, fareResult] = await Promise.all([
      gatewayRequest('GET', `/admin/routes/${routeId}`),
      gatewayRequest('GET', `/admin/routes/${routeId}/fares`),
    ]);
    if (routeResult.status === 404) {
      setNotFound(true);
    } else if (routeResult.status === 200 && fareResult.status === 200) {
      const loaded = cellsFrom(fareResult.body as Fare[]);
      setRoute(routeResult.body as Route);
      setCells(loaded);
      setSaved(loaded);
      setLoadError(null);
    } else {
      setLoadError(readError(routeResult.status !== 200 ? routeResult : fareResult).message);
    }
  }, [routeId]);

  useEffect(() => {
    void load();
  }, [load]);

  const segments = useMemo(
    () => (route ? route.stops.slice(0, -1).map((stop, i) => ({ index: i, from: stop, to: route.stops[i + 1] })) : []),
    [route],
  );

  /** The total for a class is shown only when every segment has a valid amount for it. */
  function totalFor(coachClass: CoachClass): string | null {
    let total = 0;
    for (const segment of segments) {
      const value = Number(cells[key(segment.index, coachClass)]);
      if (!Number.isFinite(value) || value <= 0 || (cells[key(segment.index, coachClass)] ?? '') === '') return null;
      total += value;
    }
    return segments.length === 0 ? null : total.toFixed(2);
  }

  const dirty = JSON.stringify(cells) !== JSON.stringify(saved);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSaving(true);
    setCellErrors({});
    setFormError(null);
    setNotice(null);

    // Only filled boxes are sent. The list replaces the route's whole fare set, so an emptied box removes that fare.
    const entries = segments.flatMap((segment) =>
      COACH_CLASSES.filter((c) => (cells[key(segment.index, c)] ?? '').trim() !== '').map((c) => ({
        cell: key(segment.index, c),
        body: { segmentIndex: segment.index, class: c, amount: Number(cells[key(segment.index, c)]) },
      })),
    );
    if (entries.length === 0) {
      setSaving(false);
      setFormError('Enter at least one fare.');
      return;
    }

    const result = await gatewayRequest('PUT', `/admin/routes/${routeId}/fares`, { body: entries.map((e) => e.body) });
    setSaving(false);

    if (result.status === 200) {
      const count = (result.body as Fare[]).length;
      await load();
      setNotice(`Fares saved: ${count} ${count === 1 ? 'fare' : 'fares'}.`);
      return;
    }

    const error = readError(result);
    if (error.code === 'validation_failed') {
      const byCell: Record<string, string> = {};
      for (const detail of error.details) {
        const index = /^\[(\d+)\]/.exec(detail.field)?.[1];
        if (index !== undefined && entries[Number(index)]) byCell[entries[Number(index)].cell] = detail.message;
      }
      setCellErrors(byCell);
      if (Object.keys(byCell).length === 0) setFormError(error.details.map((d) => d.message).join(' ') || error.message);
    } else {
      setFormError(error.message);
    }
  }

  if (notFound) {
    return (
      <section>
        <p><Link to="/admin/routes">← Routes</Link></p>
        <h1>Route not found</h1>
        <p className="muted">This route does not exist. It may have been deleted.</p>
      </section>
    );
  }

  return (
    <section>
      <AdminHeader title={route ? `Fares: ${route.name}` : 'Fares'} back={{ to: '/admin/routes', label: 'Routes' }} />
      {route && <p className="muted mono">{route.stops.map((s) => s.stationCode).join(' → ')}</p>}

      <p className="notice-slot" role="status" aria-live="polite">
        {notice && <span className="notice notice-ok">{notice}</span>}
      </p>

      {loadError && <p className="notice notice-error">{loadError}</p>}
      {route === null && !loadError && <ListSkeleton />}

      {route && (
        <form className="form" onSubmit={submit} noValidate>
          <p className="muted">
            One price per segment (the leg between two stops) and class, in EGP. A journey costs the sum of the segments it
            uses. Saving replaces all the fares of this route; an empty box means no fare.
          </p>
          <div className="table-wrap"><table className="table fare-grid">
            <caption className="visually-hidden">Fares per segment and class in EGP</caption>
            <thead>
              <tr>
                <th scope="col">Segment</th>
                {COACH_CLASSES.map((c) => (
                  <th key={c} scope="col">{CLASS_LABEL[c]} (EGP)</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {segments.map((segment) => (
                <tr key={segment.index}>
                  <th scope="row">
                    <span className="muted">{segment.index}</span> {segment.from.stationName} → {segment.to.stationName}
                  </th>
                  {COACH_CLASSES.map((c) => {
                    const k = key(segment.index, c);
                    const inputId = `fare-${segment.index}-${c}`;
                    return (
                      <td key={c}>
                        <label className="visually-hidden" htmlFor={inputId}>
                          {CLASS_LABEL[c]} fare for {segment.from.stationName} to {segment.to.stationName}, EGP
                        </label>
                        <input id={inputId} inputMode="decimal" value={cells[k] ?? ''} autoComplete="off"
                          onChange={(e) => setCells({ ...cells, [k]: e.target.value })}
                          aria-invalid={cellErrors[k] ? true : undefined} aria-describedby={cellErrors[k] ? `${inputId}-error` : undefined} />
                        {cellErrors[k] && <span id={`${inputId}-error`} className="field-error">{cellErrors[k]}</span>}
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr>
                <th scope="row">Whole route</th>
                {COACH_CLASSES.map((c) => {
                  const total = totalFor(c);
                  return <td key={c} className="mono">{total === null ? '—' : `${total} EGP`}</td>;
                })}
              </tr>
            </tfoot>
          </table></div>

          {formError && <p className="notice notice-error">{formError}</p>}
          <div className="actions">
            <button type="submit" className="button button-primary" disabled={saving || !dirty}>Save fares</button>
            {dirty && <button type="button" className="button" onClick={() => { setCells(saved); setCellErrors({}); setFormError(null); }}>Undo changes</button>}
          </div>
        </form>
      )}
    </section>
  );
}
