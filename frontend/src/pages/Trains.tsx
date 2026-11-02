import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { TRAIN_STATUSES, type Train, type TrainStatus } from '../adminTypes';
import { AdminHeader } from '../components/AdminHeader';
import { SlideOver } from '../components/SlideOver';
import { gatewayRequest, readError } from '../api';
import { label } from '../format';

interface TrainForm {
  number: string;
  name: string;
  type: string;
  status: TrainStatus;
}

interface Filters {
  number: string;
  name: string;
  status: '' | TrainStatus;
}

const EMPTY_FORM: TrainForm = { number: '', name: '', type: '', status: 'ACTIVE' };
const NO_FILTERS: Filters = { number: '', name: '', status: '' };

/** Admin page for trains (Flow 7): list with filters, add, edit and delete, through the API Gateway. */
export function Trains() {
  const [trains, setTrains] = useState<Train[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  // The filter inputs (draft) only take effect when the admin applies them.
  const [draftFilters, setDraftFilters] = useState<Filters>(NO_FILTERS);
  const [filters, setFilters] = useState<Filters>(NO_FILTERS);

  const [form, setForm] = useState<TrainForm>(EMPTY_FORM);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const [confirmingId, setConfirmingId] = useState<number | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    const query = new URLSearchParams();
    if (filters.number.trim()) query.set('number', filters.number.trim());
    if (filters.name.trim()) query.set('name', filters.name.trim());
    if (filters.status) query.set('status', filters.status);
    const suffix = query.size > 0 ? `?${query}` : '';
    const result = await gatewayRequest('GET', `/admin/trains${suffix}`);
    if (result.status === 200) {
      setTrains(result.body as Train[]);
      setLoadError(null);
    } else {
      setLoadError(readError(result).message);
    }
  }, [filters]);

  useEffect(() => {
    void load();
  }, [load]);

  const [panelOpen, setPanelOpen] = useState(false);

  function startAdd() {
    resetForm();
    setPanelOpen(true);
  }

  function resetForm() {
    setPanelOpen(false);
    setForm(EMPTY_FORM);
    setEditingId(null);
    setFieldErrors({});
    setFormError(null);
  }

  function startEdit(train: Train) {
    setPanelOpen(true);
    setForm({ number: train.number, name: train.name, type: train.type, status: train.status });
    setEditingId(train.id);
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
      ? await gatewayRequest('PUT', `/admin/trains/${editingId}`, { body: form })
      : await gatewayRequest('POST', '/admin/trains', { body: form });
    setSaving(false);

    if (result.status === 200 || result.status === 201) {
      const saved = result.body as Train;
      resetForm();
      await load(); // reload first, so the message never appears before the table shows the change
      setNotice(editing ? `Train ${saved.number} updated.` : `Train ${saved.number} added.`);
      return;
    }

    const error = readError(result);
    if (error.code === 'validation_failed') {
      setFieldErrors(Object.fromEntries(error.details.map((d) => [d.field, d.message])));
    } else if (error.code === 'train_number_taken') {
      setFieldErrors({ number: error.message });
    } else {
      setFormError(error.message);
    }
  }

  async function confirmDelete(train: Train) {
    setNotice(null);
    const result = await gatewayRequest('DELETE', `/admin/trains/${train.id}`);
    setConfirmingId(null);
    if (result.status === 204 || result.status === 404) {
      // 404 means another admin already deleted it: the list just needs a refresh.
      if (editingId === train.id) resetForm();
      await load();
      setNotice(result.status === 204 ? `Train ${train.number} deleted.` : `Train ${train.number} was already deleted.`);
    } else {
      setLoadError(readError(result).message);
    }
  }

  const filtered = filters.number !== '' || filters.name !== '' || filters.status !== '';

  return (
    <section>
      <AdminHeader title="Trains" action={<button type="button" className="button button-primary button-compact" onClick={startAdd}>Add train</button>} />

      <p className="notice-slot" role="status" aria-live="polite">
        {notice && <span className="notice notice-ok">{notice}</span>}
      </p>

      <SlideOver open={panelOpen} title={editingId === null ? 'Add train' : 'Edit train'} onClose={resetForm}>
      <form className="form" onSubmit={submit} noValidate>

        <div className="field">
          <label htmlFor="train-number">Number</label>
          <input id="train-number" value={form.number} maxLength={20} autoComplete="off"
            onChange={(e) => setForm({ ...form, number: e.target.value })}
            aria-invalid={fieldErrors.number ? true : undefined} aria-describedby="train-number-hint" />
          <span id="train-number-hint" className={fieldErrors.number ? 'field-error' : 'muted'}>
            {fieldErrors.number ?? 'Unique, for example E901'}
          </span>
        </div>

        <div className="field">
          <label htmlFor="train-name">Name</label>
          <input id="train-name" value={form.name} maxLength={100} autoComplete="off"
            onChange={(e) => setForm({ ...form, name: e.target.value })}
            aria-invalid={fieldErrors.name ? true : undefined} aria-describedby="train-name-hint" />
          <span id="train-name-hint" className="field-error">{fieldErrors.name}</span>
        </div>

        <div className="field">
          <label htmlFor="train-type">Type</label>
          <input id="train-type" value={form.type} maxLength={50} autoComplete="off"
            onChange={(e) => setForm({ ...form, type: e.target.value })}
            aria-invalid={fieldErrors.type ? true : undefined} aria-describedby="train-type-hint" />
          <span id="train-type-hint" className={fieldErrors.type ? 'field-error' : 'muted'}>
            {fieldErrors.type ?? 'For example Express'}
          </span>
        </div>

        <div className="field">
          <label htmlFor="train-status">Status</label>
          <select id="train-status" value={form.status} onChange={(e) => setForm({ ...form, status: e.target.value as TrainStatus })}
            aria-describedby="train-status-hint">
            {TRAIN_STATUSES.map((status) => (
              <option key={status} value={status}>{label(status)}</option>
            ))}
          </select>
          <span id="train-status-hint" className="field-error">{fieldErrors.status}</span>
        </div>

        {formError && <p className="notice notice-error">{formError}</p>}

        <div className="actions">
          <button type="submit" className="button button-primary" disabled={saving}>
            {editingId === null ? 'Add train' : 'Save changes'}
          </button>
          {editingId !== null && (
            <button type="button" className="button" onClick={resetForm}>Cancel</button>
          )}
        </div>
      </form>
      </SlideOver>

      <form className="filters" onSubmit={(e) => { e.preventDefault(); setFilters(draftFilters); }} aria-label="Filter trains">
        <div className="field">
          <label htmlFor="filter-number">Number contains</label>
          <input id="filter-number" value={draftFilters.number} autoComplete="off"
            onChange={(e) => setDraftFilters({ ...draftFilters, number: e.target.value })} />
        </div>
        <div className="field">
          <label htmlFor="filter-name">Name contains</label>
          <input id="filter-name" value={draftFilters.name} autoComplete="off"
            onChange={(e) => setDraftFilters({ ...draftFilters, name: e.target.value })} />
        </div>
        <div className="field">
          <label htmlFor="filter-status">Status</label>
          <select id="filter-status" value={draftFilters.status}
            onChange={(e) => setDraftFilters({ ...draftFilters, status: e.target.value as Filters['status'] })}>
            <option value="">All</option>
            {TRAIN_STATUSES.map((status) => (
              <option key={status} value={status}>{label(status)}</option>
            ))}
          </select>
        </div>
        <div className="actions filters-actions">
          <button type="submit" className="button">Apply filters</button>
          {filtered && (
            <button type="button" className="button" onClick={() => { setDraftFilters(NO_FILTERS); setFilters(NO_FILTERS); }}>
              Clear filters
            </button>
          )}
        </div>
      </form>

      {loadError && <p className="notice notice-error">{loadError}</p>}
      {trains === null && !loadError && <p className="muted">Loading…</p>}
      {trains?.length === 0 && (
        <p className="muted">{filtered ? 'No trains match these filters.' : 'No trains yet. Use "Add train" to add the first one.'}</p>
      )}
      {trains && trains.length > 0 && (
        <div className="table-wrap"><table className="table">
          <thead>
            <tr>
              <th scope="col">Number</th>
              <th scope="col">Name</th>
              <th scope="col">Type</th>
              <th scope="col">Status</th>
              <th scope="col"><span className="visually-hidden">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            {trains.map((train) => (
              <tr key={train.id}>
                <td className="mono">{train.number}</td>
                <td>{train.name}</td>
                <td>{train.type}</td>
                <td><span className={train.status === 'ACTIVE' ? 'badge badge-ok' : 'badge'}>{label(train.status)}</span></td>
                <td className="row-actions">
                  {confirmingId === train.id ? (
                    <>
                      <span>Delete {train.number} and its coaches and seats?</span>
                      <button type="button" className="button button-danger" onClick={() => void confirmDelete(train)}>Delete train</button>
                      <button type="button" className="button" onClick={() => setConfirmingId(null)}>Keep</button>
                    </>
                  ) : (
                    <>
                      <Link className="button" to={`/admin/trains/${train.id}`}>Coaches and seats</Link>
                      <button type="button" className="button" onClick={() => startEdit(train)}>Edit</button>
                      <button type="button" className="button" onClick={() => setConfirmingId(train.id)}>Delete</button>
                    </>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table></div>
      )}
    </section>
  );
}
