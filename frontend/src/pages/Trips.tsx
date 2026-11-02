import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import type { Route, Train, Trip } from '../adminTypes';
import { AdminHeader } from '../components/AdminHeader';
import { SlideOver } from '../components/SlideOver';
import { gatewayRequest, readError, type ApiErrorDetail } from '../api';
import { TripCancellation } from '../components/TripCancellation';
import { clock, daysLater, todayLocal } from '../time';
import { label } from '../format';

interface StopTimes {
  arrival: string; // "HH:mm" or ""
  departure: string;
}

/** The compact one-line form of a trip's times: CAI 08:00 · GIZ 08:40/08:45 · ALX 10:45 (+1 marks the next day). */
function timesLine(trip: Trip): string {
  const first = trip.stops[0]?.departureTime;
  return trip.stops
    .map((stop) => {
      const mark = (t: string | null) => (t && first ? `${clock(t)}${daysLater(first, t) > 0 ? '+' + daysLater(first, t) : ''}` : '');
      const times = [mark(stop.arrivalTime), mark(stop.departureTime)].filter(Boolean);
      return `${stop.stationCode} ${times.length === 2 && times[0] === times[1] ? times[0] : times.join('/')}`;
    })
    .join(' · ');
}

/** Admin page for trips (FR-A8): a train on a route, a departure date and the time at every stop. */
export function Trips() {
  const [trips, setTrips] = useState<Trip[] | null>(null);
  const [trains, setTrains] = useState<Train[]>([]);
  const [routes, setRoutes] = useState<Route[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [trainId, setTrainId] = useState('');
  const [routeId, setRouteId] = useState('');
  const [date, setDate] = useState(todayLocal());
  const [times, setTimes] = useState<StopTimes[]>([]);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [missingFares, setMissingFares] = useState<ApiErrorDetail[]>([]);
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const [confirmingId, setConfirmingId] = useState<number | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    const [tripResult, trainResult, routeResult] = await Promise.all([
      gatewayRequest('GET', '/admin/trips'),
      gatewayRequest('GET', '/admin/trains'),
      gatewayRequest('GET', '/admin/routes'),
    ]);
    const failed = [tripResult, trainResult, routeResult].find((r) => r.status !== 200);
    if (failed) {
      setLoadError(readError(failed).message);
      return;
    }
    setTrips(tripResult.body as Trip[]);
    setTrains(trainResult.body as Train[]);
    setRoutes(routeResult.body as Route[]);
    setLoadError(null);
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const route = useMemo(() => routes.find((r) => r.id === Number(routeId)) ?? null, [routes, routeId]);

  function blankTimes(stopCount: number): StopTimes[] {
    return Array.from({ length: stopCount }, () => ({ arrival: '', departure: '' }));
  }

  const [panelOpen, setPanelOpen] = useState(false);

  function startAdd() {
    resetForm();
    setPanelOpen(true);
  }

  function resetForm() {
    setPanelOpen(false);
    setTrainId('');
    setRouteId('');
    setDate(todayLocal());
    setTimes([]);
    setEditingId(null);
    setFieldErrors({});
    setMissingFares([]);
    setFormError(null);
  }

  function chooseRoute(value: string) {
    setRouteId(value);
    const chosen = routes.find((r) => r.id === Number(value));
    setTimes(chosen ? blankTimes(chosen.stops.length) : []);
    setFieldErrors({});
    setMissingFares([]);
  }

  function setTime(index: number, field: keyof StopTimes, value: string) {
    setTimes(times.map((t, i) => (i === index ? { ...t, [field]: value } : t)));
    setFieldErrors((errors) => {
      const { [`stops[${index}].${field}Time`]: _removed, ...rest } = errors;
      return rest;
    });
  }

  function startEdit(trip: Trip) {
    setPanelOpen(true);
    setTrainId(String(trip.trainId));
    setRouteId(String(trip.routeId));
    setDate(trip.departureDate);
    setTimes(trip.stops.map((s) => ({ arrival: clock(s.arrivalTime), departure: clock(s.departureTime) })));
    setEditingId(trip.id);
    setFieldErrors({});
    setMissingFares([]);
    setFormError(null);
    setConfirmingId(null);
    setNotice(null);
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSaving(true);
    setFieldErrors({});
    setMissingFares([]);
    setFormError(null);
    setNotice(null);

    const last = times.length - 1;
    const body = {
      trainId: trainId === '' ? null : Number(trainId),
      routeId: routeId === '' ? null : Number(routeId),
      departureDate: date === '' ? null : date,
      // The first stop has no arrival and the last no departure: those boxes are disabled and never sent.
      stops: times.map((t, i) => ({
        arrivalTime: i === 0 || t.arrival === '' ? null : t.arrival,
        departureTime: i === last || t.departure === '' ? null : t.departure,
      })),
    };
    const editing = editingId !== null;
    const result = editing
      ? await gatewayRequest('PUT', `/admin/trips/${editingId}`, { body })
      : await gatewayRequest('POST', '/admin/trips', { body });
    setSaving(false);

    if (result.status === 200 || result.status === 201) {
      const saved = result.body as Trip;
      resetForm();
      await load();
      setNotice(editing ? `Trip ${saved.trainNumber} on ${saved.departureDate} updated.` : `Trip ${saved.trainNumber} on ${saved.departureDate} added.`);
      return;
    }

    const error = readError(result);
    if (error.code === 'validation_failed') {
      const byField: Record<string, string> = {};
      for (const d of error.details) byField[d.field] = byField[d.field] ?? d.message;
      setFieldErrors(byField);
    } else if (error.code === 'route_fares_incomplete') {
      setMissingFares(error.details);
    } else {
      setFormError(error.message);
    }
  }

  async function confirmDelete(trip: Trip) {
    setNotice(null);
    const result = await gatewayRequest('DELETE', `/admin/trips/${trip.id}`);
    setConfirmingId(null);
    if (result.status === 204 || result.status === 404) {
      if (editingId === trip.id) resetForm();
      await load();
      setNotice(result.status === 204 ? `Trip ${trip.trainNumber} on ${trip.departureDate} deleted.` : 'That trip was already deleted.');
    } else {
      setLoadError(readError(result).message);
    }
  }

  const trainFor = (id: string) => trains.find((t) => t.id === Number(id));
  const noRoutes = routes.length === 0;
  const noTrains = trains.length === 0;

  return (
    <section>
      <AdminHeader title="Trips" action={<button type="button" className="button button-primary button-compact" onClick={startAdd}>Add trip</button>} />

      <p className="notice-slot" role="status" aria-live="polite">
        {notice && <span className="notice notice-ok">{notice}</span>}
      </p>

      {(noRoutes || noTrains) && trips !== null && (
        <p className="notice">
          A trip needs a train and a route. {noTrains && <Link to="/admin/trains">Add a train</Link>}
          {noTrains && noRoutes && ' and '}
          {noRoutes && <Link to="/admin/routes">add a route</Link>} first.
        </p>
      )}

      <SlideOver open={panelOpen} title={editingId === null ? 'Add trip' : 'Edit trip'} onClose={resetForm} wide>
      <form className="form" onSubmit={submit} noValidate>

        <div className="inline-fields">
          <div className="field">
            <label htmlFor="trip-train">Train</label>
            <select id="trip-train" value={trainId} onChange={(e) => { setTrainId(e.target.value); setFieldErrors({}); }}
              aria-invalid={fieldErrors.trainId ? true : undefined} aria-describedby="trip-train-hint">
              <option value="">Choose a train…</option>
              {trains.map((t) => (
                <option key={t.id} value={t.id} disabled={t.status !== 'ACTIVE' && String(t.id) !== trainId}>
                  {t.number} · {t.name}{t.status !== 'ACTIVE' ? ' (inactive)' : ''}
                </option>
              ))}
            </select>
            <span id="trip-train-hint" className="field-error">{fieldErrors.trainId}</span>
          </div>
          <div className="field">
            <label htmlFor="trip-route">Route</label>
            <select id="trip-route" value={routeId} onChange={(e) => chooseRoute(e.target.value)}
              aria-invalid={fieldErrors.routeId ? true : undefined} aria-describedby="trip-route-hint">
              <option value="">Choose a route…</option>
              {routes.map((r) => (
                <option key={r.id} value={r.id}>{r.name} ({r.stops.map((s) => s.stationCode).join(' → ')})</option>
              ))}
            </select>
            <span id="trip-route-hint" className="field-error">{fieldErrors.routeId}</span>
          </div>
          <div className="field">
            <label htmlFor="trip-date">Departure date</label>
            <input id="trip-date" type="date" value={date} onChange={(e) => setDate(e.target.value)}
              aria-invalid={fieldErrors.departureDate ? true : undefined} aria-describedby="trip-date-hint" />
            <span id="trip-date-hint" className="field-error">{fieldErrors.departureDate}</span>
          </div>
        </div>

        {route && (
          <fieldset className="stops">
            <legend>Time at each stop</legend>
            <p className="muted">
              Clock times, 24 hours. A time earlier than the one before it means the next day, so a trip can run past midnight
              (a trip lasts at most 24 hours).
            </p>
            <div className="table-wrap"><table className="table stop-times">
              <thead>
                <tr>
                  <th scope="col">Stop</th>
                  <th scope="col">Arrives</th>
                  <th scope="col">Departs</th>
                </tr>
              </thead>
              <tbody>
                {route.stops.map((stop, i) => {
                  const isFirst = i === 0;
                  const isLast = i === route.stops.length - 1;
                  const arrivalKey = `stops[${i}].arrivalTime`;
                  const departureKey = `stops[${i}].departureTime`;
                  return (
                    <tr key={stop.stopIndex}>
                      <th scope="row">
                        <span className="mono">{stop.stationCode}</span> {stop.stationName}
                      </th>
                      <td>
                        <label className="visually-hidden" htmlFor={`arr-${i}`}>Arrival time at {stop.stationName}</label>
                        <input id={`arr-${i}`} type="time" value={times[i]?.arrival ?? ''} disabled={isFirst}
                          onChange={(e) => setTime(i, 'arrival', e.target.value)}
                          aria-invalid={fieldErrors[arrivalKey] ? true : undefined} aria-describedby={fieldErrors[arrivalKey] ? `arr-${i}-error` : undefined} />
                        {isFirst && <span className="muted"> starts here</span>}
                        {fieldErrors[arrivalKey] && <span id={`arr-${i}-error`} className="field-error">{fieldErrors[arrivalKey]}</span>}
                      </td>
                      <td>
                        <label className="visually-hidden" htmlFor={`dep-${i}`}>Departure time from {stop.stationName}</label>
                        <input id={`dep-${i}`} type="time" value={times[i]?.departure ?? ''} disabled={isLast}
                          onChange={(e) => setTime(i, 'departure', e.target.value)}
                          aria-invalid={fieldErrors[departureKey] ? true : undefined} aria-describedby={fieldErrors[departureKey] ? `dep-${i}-error` : undefined} />
                        {isLast && <span className="muted"> ends here</span>}
                        {fieldErrors[departureKey] && <span id={`dep-${i}-error`} className="field-error">{fieldErrors[departureKey]}</span>}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table></div>
            {fieldErrors.stops && <p className="field-error">{fieldErrors.stops}</p>}
          </fieldset>
        )}

        {missingFares.length > 0 && route && (
          <div className="notice notice-error" role="alert">
            <p>The fares of this route are not complete for this train, so the trip cannot be created yet:</p>
            <ul>
              {missingFares.map((d) => (
                <li key={d.message}>{d.message}</li>
              ))}
            </ul>
            <p>
              <Link to={`/admin/routes/${route.id}/fares`}>Set the fares of {route.name}</Link>, then try again.
            </p>
          </div>
        )}
        {formError && <p className="notice notice-error">{formError}</p>}

        <div className="actions">
          <button type="submit" className="button button-primary" disabled={saving || (noRoutes && noTrains)}>
            {editingId === null ? 'Add trip' : 'Save changes'}
          </button>
          {editingId !== null && (
            <button type="button" className="button" onClick={resetForm}>Cancel</button>
          )}
        </div>
        {trainFor(trainId)?.status === 'INACTIVE' && editingId === null && (
          <p className="muted">This train is inactive, so no new trip can be scheduled for it.</p>
        )}
      </form>
      </SlideOver>

      {loadError && <p className="notice notice-error">{loadError}</p>}
      {trips === null && !loadError && <p className="muted">Loading…</p>}
      {trips?.length === 0 && <p className="muted">No trips yet. Use "Add trip" to add the first one.</p>}
      {trips && trips.length > 0 && (
        <div className="table-wrap"><table className="table">
          <thead>
            <tr>
              <th scope="col">Date</th>
              <th scope="col">Train</th>
              <th scope="col">Route and times</th>
              <th scope="col">Status</th>
              <th scope="col"><span className="visually-hidden">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            {trips.map((trip) => (
              <tr key={trip.id}>
                <td className="mono nowrap">{trip.departureDate}</td>
                <td><span className="mono">{trip.trainNumber}</span></td>
                <td>
                  {trip.routeName}
                  <div className="muted mono times-line">{timesLine(trip)}</div>
                </td>
                <td><span className={trip.status === 'ACTIVE' ? 'badge badge-ok' : 'badge'}>{label(trip.status)}</span></td>
                <td className="row-actions">
                  {confirmingId === trip.id ? (
                    <>
                      <span>Delete this trip?</span>
                      <button type="button" className="button button-danger" onClick={() => void confirmDelete(trip)}>Delete trip</button>
                      <button type="button" className="button" onClick={() => setConfirmingId(null)}>Keep</button>
                    </>
                  ) : (
                    <>
                      <button type="button" className="button" onClick={() => startEdit(trip)}>Edit</button>
                      <button type="button" className="button" onClick={() => setConfirmingId(trip.id)}>Delete</button>
                    </>
                  )}
                  <TripCancellation tripId={trip.id} tripStatus={trip.status} onChanged={() => void load()} />
                </td>
              </tr>
            ))}
          </tbody>
        </table></div>
      )}
    </section>
  );
}
