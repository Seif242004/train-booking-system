import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { gatewayRequest, readError } from '../api';

interface Station {
  id: number;
  code: string;
  name: string;
  city: string;
}

interface StationForm {
  code: string;
  name: string;
  city: string;
}

const EMPTY_FORM: StationForm = { code: '', name: '', city: '' };

/** Admin page for stations (Flow 7): list, add, edit and delete, all through the API Gateway. */
export function Stations() {
  const [stations, setStations] = useState<Station[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [form, setForm] = useState<StationForm>(EMPTY_FORM);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const [confirmingId, setConfirmingId] = useState<number | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    const result = await gatewayRequest('GET', '/admin/stations');
    if (result.status === 200) {
      setStations(result.body as Station[]);
      setLoadError(null);
    } else {
      setLoadError(readError(result).message);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  function resetForm() {
    setForm(EMPTY_FORM);
    setEditingId(null);
    setFieldErrors({});
    setFormError(null);
  }

  function startEdit(station: Station) {
    setForm({ code: station.code, name: station.name, city: station.city });
    setEditingId(station.id);
    setFieldErrors({});
    setFormError(null);
    setConfirmingId(null);
    setNotice(null);
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSaving(true);
    setFieldErrors({});
    setFormError(null);
    setNotice(null);

    const editing = editingId !== null;
    const result = editing
      ? await gatewayRequest('PUT', `/admin/stations/${editingId}`, { body: form })
      : await gatewayRequest('POST', '/admin/stations', { body: form });
    setSaving(false);

    if (result.status === 200 || result.status === 201) {
      const saved = result.body as Station;
      resetForm();
      await load(); // reload first, so the message never appears before the table shows the change
      setNotice(editing ? `Station ${saved.code} updated.` : `Station ${saved.code} added.`);
      return;
    }

    const error = readError(result);
    if (error.code === 'validation_failed') {
      setFieldErrors(Object.fromEntries(error.details.map((d) => [d.field, d.message])));
    } else if (error.code === 'station_code_taken') {
      setFieldErrors({ code: error.message });
    } else {
      setFormError(error.message);
    }
  }

  async function confirmDelete(station: Station) {
    setNotice(null);
    const result = await gatewayRequest('DELETE', `/admin/stations/${station.id}`);
    setConfirmingId(null);
    if (result.status === 204 || result.status === 404) {
      // 404 means another admin already deleted it: the list just needs a refresh.
      if (editingId === station.id) resetForm();
      await load();
      setNotice(result.status === 204 ? `Station ${station.code} deleted.` : `Station ${station.code} was already deleted.`);
    } else {
      setNotice(null);
      setLoadError(readError(result).message);
    }
  }

  return (
    <section>
      <p>
        <Link to="/admin">← Admin</Link>
      </p>
      <h1>Stations</h1>

      <p className="notice-slot" role="status" aria-live="polite">
        {notice && <span className="notice notice-ok">{notice}</span>}
      </p>

      <form className="form" onSubmit={submit} noValidate>
        <h2>{editingId === null ? 'Add station' : 'Edit station'}</h2>

        <div className="field">
          <label htmlFor="station-code">Code</label>
          <input
            id="station-code"
            value={form.code}
            onChange={(e) => setForm({ ...form, code: e.target.value })}
            maxLength={10}
            autoComplete="off"
            aria-invalid={fieldErrors.code ? true : undefined}
            aria-describedby="station-code-hint"
          />
          <span id="station-code-hint" className={fieldErrors.code ? 'field-error' : 'muted'}>
            {fieldErrors.code ?? '2 to 10 letters or digits, for example CAI'}
          </span>
        </div>

        <div className="field">
          <label htmlFor="station-name">Name</label>
          <input
            id="station-name"
            value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}
            maxLength={100}
            autoComplete="off"
            aria-invalid={fieldErrors.name ? true : undefined}
            aria-describedby="station-name-hint"
          />
          <span id="station-name-hint" className="field-error">
            {fieldErrors.name}
          </span>
        </div>

        <div className="field">
          <label htmlFor="station-city">City</label>
          <input
            id="station-city"
            value={form.city}
            onChange={(e) => setForm({ ...form, city: e.target.value })}
            maxLength={100}
            autoComplete="off"
            aria-invalid={fieldErrors.city ? true : undefined}
            aria-describedby="station-city-hint"
          />
          <span id="station-city-hint" className="field-error">
            {fieldErrors.city}
          </span>
        </div>

        {formError && <p className="notice notice-error">{formError}</p>}

        <div className="actions">
          <button type="submit" className="button button-primary" disabled={saving}>
            {editingId === null ? 'Add station' : 'Save changes'}
          </button>
          {editingId !== null && (
            <button type="button" className="button" onClick={resetForm}>
              Cancel
            </button>
          )}
        </div>
      </form>

      <h2>All stations</h2>
      {loadError && <p className="notice notice-error">{loadError}</p>}
      {stations === null && !loadError && <p className="muted">Loading…</p>}
      {stations?.length === 0 && <p className="muted">No stations yet. Add the first one with the form above.</p>}
      {stations && stations.length > 0 && (
        <table className="table">
          <thead>
            <tr>
              <th scope="col">Code</th>
              <th scope="col">Name</th>
              <th scope="col">City</th>
              <th scope="col">
                <span className="visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {stations.map((station) => (
              <tr key={station.id}>
                <td className="mono">{station.code}</td>
                <td>{station.name}</td>
                <td>{station.city}</td>
                <td className="row-actions">
                  {confirmingId === station.id ? (
                    <>
                      <span>
                        Delete {station.code}?
                      </span>
                      <button type="button" className="button button-danger" onClick={() => void confirmDelete(station)}>
                        Delete station
                      </button>
                      <button type="button" className="button" onClick={() => setConfirmingId(null)}>
                        Keep
                      </button>
                    </>
                  ) : (
                    <>
                      <button type="button" className="button" onClick={() => startEdit(station)}>
                        Edit
                      </button>
                      <button type="button" className="button" onClick={() => setConfirmingId(station.id)}>
                        Delete
                      </button>
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
