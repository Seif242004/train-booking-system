import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import type { Route, Station } from '../adminTypes';
import { gatewayRequest, readError, type ApiErrorDetail } from '../api';

/** Admin page for routes (FR-A8): a name and an ordered list of stations. Fares are set on their own page. */
export function RouteList() {
  const [routes, setRoutes] = useState<Route[] | null>(null);
  const [stations, setStations] = useState<Station[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [name, setName] = useState('');
  const [stopIds, setStopIds] = useState<number[]>([]); // station ids in travel order: the first is stop 0
  const [pick, setPick] = useState(''); // the station chosen in the "add a stop" box
  const [editingId, setEditingId] = useState<number | null>(null);
  const [nameError, setNameError] = useState<string | null>(null);
  const [stopProblems, setStopProblems] = useState<ApiErrorDetail[]>([]);
  const [formError, setFormError] = useState<string | null>(null);
  const [needsClearConfirm, setNeedsClearConfirm] = useState<string | null>(null); // message from the 409
  const [saving, setSaving] = useState(false);

  const [confirmingId, setConfirmingId] = useState<number | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    const [routeResult, stationResult] = await Promise.all([
      gatewayRequest('GET', '/admin/routes'),
      gatewayRequest('GET', '/admin/stations'),
    ]);
    if (routeResult.status === 200 && stationResult.status === 200) {
      setRoutes(routeResult.body as Route[]);
      setStations(stationResult.body as Station[]);
      setLoadError(null);
    } else {
      setLoadError(readError(routeResult.status !== 200 ? routeResult : stationResult).message);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const stationById = useMemo(() => new Map((stations ?? []).map((s) => [s.id, s])), [stations]);
  const available = (stations ?? []).filter((s) => !stopIds.includes(s.id));

  function resetForm() {
    setName('');
    setStopIds([]);
    setPick('');
    setEditingId(null);
    setNameError(null);
    setStopProblems([]);
    setFormError(null);
    setNeedsClearConfirm(null);
  }

  function startEdit(route: Route) {
    setName(route.name);
    setStopIds(route.stops.map((s) => s.stationId));
    setPick('');
    setEditingId(route.id);
    setNameError(null);
    setStopProblems([]);
    setFormError(null);
    setNeedsClearConfirm(null);
    setConfirmingId(null);
    setNotice(null);
  }

  /** Any change to the stops makes the last error and the clear-fares prompt out of date. */
  function stopsChanged(next: number[]) {
    setStopIds(next);
    setStopProblems([]);
    setNeedsClearConfirm(null);
  }

  function addStop() {
    if (pick === '') return;
    stopsChanged([...stopIds, Number(pick)]);
    setPick('');
  }

  function moveStop(index: number, by: -1 | 1) {
    const target = index + by;
    if (target < 0 || target >= stopIds.length) return;
    const next = [...stopIds];
    [next[index], next[target]] = [next[target], next[index]];
    stopsChanged(next);
  }

  function removeStop(index: number) {
    stopsChanged(stopIds.filter((_, i) => i !== index));
  }

  async function save(clearFares: boolean) {
    setSaving(true);
    setNameError(null);
    setStopProblems([]);
    setFormError(null);
    setNotice(null);

    const body = { name, stops: stopIds };
    const editing = editingId !== null;
    const result = editing
      ? await gatewayRequest('PUT', `/admin/routes/${editingId}${clearFares ? '?clearFares=true' : ''}`, { body })
      : await gatewayRequest('POST', '/admin/routes', { body });
    setSaving(false);

    if (result.status === 200 || result.status === 201) {
      const saved = result.body as Route;
      resetForm();
      await load();
      setNotice(
        editing
          ? `Route ${saved.name} updated.${clearFares ? ' Its fares were removed, set them again on the Fares page.' : ''}`
          : `Route ${saved.name} added. Set its fares next.`,
      );
      return;
    }

    const error = readError(result);
    if (error.code === 'route_has_fares') {
      setNeedsClearConfirm(error.message);
    } else if (error.code === 'validation_failed') {
      setNameError(error.details.find((d) => d.field === 'name')?.message ?? null);
      setStopProblems(error.details.filter((d) => d.field.startsWith('stops')));
    } else {
      setFormError(error.message);
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    void save(false);
  }

  async function confirmDelete(route: Route) {
    setNotice(null);
    const result = await gatewayRequest('DELETE', `/admin/routes/${route.id}`);
    setConfirmingId(null);
    if (result.status === 204 || result.status === 404) {
      if (editingId === route.id) resetForm();
      await load();
      setNotice(result.status === 204 ? `Route ${route.name} deleted.` : `Route ${route.name} was already deleted.`);
    } else {
      setLoadError(readError(result).message);
    }
  }

  const tooFewStations = stations !== null && stations.length < 2;

  return (
    <section>
      <p>
        <Link to="/admin">← Admin</Link>
      </p>
      <h1>Routes</h1>

      <p className="notice-slot" role="status" aria-live="polite">
        {notice && <span className="notice notice-ok">{notice}</span>}
      </p>

      {tooFewStations && (
        <p className="notice">
          A route needs at least two stations. <Link to="/admin/stations">Add stations</Link> first.
        </p>
      )}

      <form className="form" onSubmit={submit} noValidate>
        <h2>{editingId === null ? 'Add route' : 'Edit route'}</h2>

        <div className="field">
          <label htmlFor="route-name">Name</label>
          <input id="route-name" value={name} maxLength={100} autoComplete="off"
            onChange={(e) => { setName(e.target.value); setNameError(null); setNeedsClearConfirm(null); }}
            aria-invalid={nameError ? true : undefined} aria-describedby="route-name-hint" />
          <span id="route-name-hint" className="field-error">{nameError}</span>
        </div>

        <fieldset className="stops">
          <legend>Stops, in travel order</legend>
          {stopIds.length === 0 ? (
            <p className="muted">No stops yet. Add at least two stations.</p>
          ) : (
            <ol className="stop-list">
              {stopIds.map((id, index) => {
                const station = stationById.get(id);
                return (
                  <li key={id}>
                    <span className="stop-label">
                      <span className="mono">{station?.code ?? `#${id}`}</span> {station?.name ?? 'Unknown station'}
                      {station && <span className="muted"> ({station.city})</span>}
                    </span>
                    <span className="stop-buttons">
                      <button type="button" className="button" onClick={() => moveStop(index, -1)} disabled={index === 0}
                        aria-label={`Move ${station?.code ?? id} up`}>Up</button>
                      <button type="button" className="button" onClick={() => moveStop(index, 1)} disabled={index === stopIds.length - 1}
                        aria-label={`Move ${station?.code ?? id} down`}>Down</button>
                      <button type="button" className="button" onClick={() => removeStop(index)}
                        aria-label={`Remove ${station?.code ?? id}`}>Remove</button>
                    </span>
                  </li>
                );
              })}
            </ol>
          )}

          <div className="inline-fields add-stop">
            <div className="field">
              <label htmlFor="route-pick">Add a station as the next stop</label>
              <select id="route-pick" value={pick} onChange={(e) => setPick(e.target.value)} disabled={available.length === 0}>
                <option value="">{available.length === 0 ? 'No more stations' : 'Choose a station…'}</option>
                {available.map((s) => (
                  <option key={s.id} value={s.id}>{s.code} · {s.name} ({s.city})</option>
                ))}
              </select>
            </div>
            <div className="field">
              <span className="field-spacer" aria-hidden="true">&nbsp;</span>
              <button type="button" className="button" onClick={addStop} disabled={pick === ''}>Add stop</button>
            </div>
          </div>

          {stopProblems.length > 0 && (
            <ul className="field-error problem-list" role="alert">
              {stopProblems.map((d) => (
                <li key={`${d.field}-${d.message}`}>{d.message}</li>
              ))}
            </ul>
          )}
        </fieldset>

        {formError && <p className="notice notice-error">{formError}</p>}

        {needsClearConfirm && (
          <div className="notice" role="alert">
            <p>
              This route has {/has (\d+) fares/.exec(needsClearConfirm)?.[1] ?? 'some'} fares, and fares belong to the stops.
              Changing the stops removes them.
            </p>
            <div className="actions">
              <button type="button" className="button button-danger" disabled={saving} onClick={() => void save(true)}>
                Save and remove the fares
              </button>
              <button type="button" className="button" onClick={() => setNeedsClearConfirm(null)}>Keep the fares</button>
            </div>
          </div>
        )}

        <div className="actions">
          <button type="submit" className="button button-primary" disabled={saving}>
            {editingId === null ? 'Add route' : 'Save changes'}
          </button>
          {editingId !== null && (
            <button type="button" className="button" onClick={resetForm}>Cancel</button>
          )}
        </div>
      </form>

      <h2>All routes</h2>
      {loadError && <p className="notice notice-error">{loadError}</p>}
      {routes === null && !loadError && <p className="muted">Loading…</p>}
      {routes?.length === 0 && <p className="muted">No routes yet. Add the first one with the form above.</p>}
      {routes && routes.length > 0 && (
        <table className="table">
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">Stops</th>
              <th scope="col"><span className="visually-hidden">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            {routes.map((route) => (
              <tr key={route.id}>
                <td>{route.name}</td>
                <td className="mono">{route.stops.map((s) => s.stationCode).join(' → ')}</td>
                <td className="row-actions">
                  {confirmingId === route.id ? (
                    <>
                      <span>Delete {route.name} and its fares?</span>
                      <button type="button" className="button button-danger" onClick={() => void confirmDelete(route)}>Delete route</button>
                      <button type="button" className="button" onClick={() => setConfirmingId(null)}>Keep</button>
                    </>
                  ) : (
                    <>
                      <Link className="button" to={`/admin/routes/${route.id}/fares`}>Fares</Link>
                      <button type="button" className="button" onClick={() => startEdit(route)}>Edit</button>
                      <button type="button" className="button" onClick={() => setConfirmingId(route.id)}>Delete</button>
                    </>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
