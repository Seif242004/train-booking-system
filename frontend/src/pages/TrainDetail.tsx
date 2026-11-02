import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { CLASS_LABEL, COACH_CLASSES, type Coach, type CoachClass, type Train } from '../adminTypes';
import { AdminHeader } from '../components/AdminHeader';
import { SlideOver } from '../components/SlideOver';
import { gatewayRequest, readError } from '../api';
import { CoachSeats } from '../components/CoachSeats';
import { label } from '../format';

interface CoachForm {
  code: string;
  class: CoachClass;
  position: string;
  standingCapacity: string;
}

const EMPTY_FORM: CoachForm = { code: '', class: 'SECOND', position: '', standingCapacity: '0' };

/** One train with its coaches (in train order), their standing capacity, and the seats of the selected coach. */
export function TrainDetail() {
  const { id } = useParams();
  const trainId = Number(id);

  const [train, setTrain] = useState<Train | null>(null);
  const [coaches, setCoaches] = useState<Coach[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);

  const [form, setForm] = useState<CoachForm>(EMPTY_FORM);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const [confirmingId, setConfirmingId] = useState<number | null>(null);
  const [seatsOfId, setSeatsOfId] = useState<number | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const loadCoaches = useCallback(async () => {
    const result = await gatewayRequest('GET', `/admin/trains/${trainId}/coaches`);
    if (result.status === 200) {
      // The server already orders by position; sorting here keeps the page correct whatever the order.
      setCoaches([...(result.body as Coach[])].sort((a, b) => a.position - b.position));
      setLoadError(null);
    } else {
      setLoadError(readError(result).message);
    }
  }, [trainId]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const result = await gatewayRequest('GET', `/admin/trains/${trainId}`);
      if (cancelled) return;
      if (result.status === 200) {
        setTrain(result.body as Train);
        await loadCoaches();
      } else if (result.status === 404) {
        setNotFound(true);
      } else {
        setLoadError(readError(result).message);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [trainId, loadCoaches]);

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

  function startEdit(coach: Coach) {
    setPanelOpen(true);
    setForm({ code: coach.code, class: coach.class, position: String(coach.position), standingCapacity: String(coach.standingCapacity) });
    setEditingId(coach.id);
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

    // An empty number box is sent as null, so the server answers "is required" instead of the page guessing.
    const body = {
      code: form.code,
      class: form.class,
      position: form.position === '' ? null : Number(form.position),
      standingCapacity: form.standingCapacity === '' ? null : Number(form.standingCapacity),
    };
    const editing = editingId !== null;
    const result = editing
      ? await gatewayRequest('PUT', `/admin/coaches/${editingId}`, { body })
      : await gatewayRequest('POST', `/admin/trains/${trainId}/coaches`, { body });
    setSaving(false);

    if (result.status === 200 || result.status === 201) {
      const saved = result.body as Coach;
      resetForm();
      await loadCoaches();
      setNotice(editing ? `Coach ${saved.code} updated.` : `Coach ${saved.code} added.`);
      return;
    }

    const error = readError(result);
    if (error.code === 'validation_failed') {
      setFieldErrors(Object.fromEntries(error.details.map((d) => [d.field, d.message])));
    } else if (error.code === 'coach_code_taken') {
      setFieldErrors({ code: error.message });
    } else if (error.code === 'coach_position_taken') {
      setFieldErrors({ position: error.message });
    } else {
      setFormError(error.message);
    }
  }

  async function confirmDelete(coach: Coach) {
    setNotice(null);
    const result = await gatewayRequest('DELETE', `/admin/coaches/${coach.id}`);
    setConfirmingId(null);
    if (result.status === 204 || result.status === 404) {
      if (editingId === coach.id) resetForm();
      if (seatsOfId === coach.id) setSeatsOfId(null);
      await loadCoaches();
      setNotice(result.status === 204 ? `Coach ${coach.code} deleted.` : `Coach ${coach.code} was already deleted.`);
    } else {
      setLoadError(readError(result).message);
    }
  }

  if (notFound) {
    return (
      <section>
        <p><Link to="/admin/trains">← Trains</Link></p>
        <h1>Train not found</h1>
        <p className="muted">This train does not exist. It may have been deleted.</p>
      </section>
    );
  }

  const seatsCoach = coaches?.find((c) => c.id === seatsOfId) ?? null;
  const totalStanding = coaches?.reduce((sum, c) => sum + c.standingCapacity, 0) ?? 0;

  return (
    <section>
      <AdminHeader title={train ? `Train ${train.number}` : 'Train'} back={{ to: '/admin/trains', label: 'Trains' }}
        action={<button type="button" className="button button-primary button-compact" onClick={startAdd}>Add coach</button>} />
      {train && (
        <p className="muted">
          {train.name} · {train.type} · <span className={train.status === 'ACTIVE' ? 'badge badge-ok' : 'badge'}>{label(train.status)}</span>
        </p>
      )}

      <p className="notice-slot" role="status" aria-live="polite">
        {notice && <span className="notice notice-ok">{notice}</span>}
      </p>

      <SlideOver open={panelOpen} title={editingId === null ? 'Add coach' : 'Edit coach'} onClose={resetForm}>
      <form className="form" onSubmit={submit} noValidate>
        <div className="inline-fields">
          <div className="field">
            <label htmlFor="coach-code">Code</label>
            <input id="coach-code" value={form.code} maxLength={10} autoComplete="off"
              onChange={(e) => setForm({ ...form, code: e.target.value })}
              aria-invalid={fieldErrors.code ? true : undefined} aria-describedby="coach-code-hint" />
            <span id="coach-code-hint" className="field-error">{fieldErrors.code}</span>
          </div>
          <div className="field">
            <label htmlFor="coach-class">Class</label>
            <select id="coach-class" value={form.class} onChange={(e) => setForm({ ...form, class: e.target.value as CoachClass })}
              aria-describedby="coach-class-hint">
              {COACH_CLASSES.map((c) => (
                <option key={c} value={c}>{CLASS_LABEL[c]}</option>
              ))}
            </select>
            <span id="coach-class-hint" className="field-error">{fieldErrors.class}</span>
          </div>
          <div className="field">
            <label htmlFor="coach-position">Position in the train</label>
            <input id="coach-position" type="number" min={1} max={99} value={form.position}
              onChange={(e) => setForm({ ...form, position: e.target.value })}
              aria-invalid={fieldErrors.position ? true : undefined} aria-describedby="coach-position-hint" />
            <span id="coach-position-hint" className={fieldErrors.position ? 'field-error' : 'muted'}>
              {fieldErrors.position ?? '1 is the first coach'}
            </span>
          </div>
          <div className="field">
            <label htmlFor="coach-standing">Standing capacity</label>
            <input id="coach-standing" type="number" min={0} max={1000} value={form.standingCapacity}
              onChange={(e) => setForm({ ...form, standingCapacity: e.target.value })}
              aria-invalid={fieldErrors.standingCapacity ? true : undefined} aria-describedby="coach-standing-hint" />
            <span id="coach-standing-hint" className={fieldErrors.standingCapacity ? 'field-error' : 'muted'}>
              {fieldErrors.standingCapacity ?? 'Standing places, 0 or more'}
            </span>
          </div>
        </div>
        {formError && <p className="notice notice-error">{formError}</p>}
        <div className="actions">
          <button type="submit" className="button button-primary" disabled={saving}>
            {editingId === null ? 'Add coach' : 'Save changes'}
          </button>
          {editingId !== null && (
            <button type="button" className="button" onClick={resetForm}>Cancel</button>
          )}
        </div>
      </form>
      </SlideOver>

      <h2>Coaches</h2>
      {loadError && <p className="notice notice-error">{loadError}</p>}
      {coaches === null && !loadError && <p className="muted">Loading…</p>}
      {coaches?.length === 0 && <p className="muted">This train has no coaches yet. Use "Add coach" to add the first one.</p>}
      {coaches && coaches.length > 0 && (
        <>
          <p className="muted">In train order. Total standing capacity: {totalStanding}.</p>
          <div className="table-wrap"><table className="table">
            <thead>
              <tr>
                <th scope="col">Position</th>
                <th scope="col">Code</th>
                <th scope="col">Class</th>
                <th scope="col">Standing</th>
                <th scope="col"><span className="visually-hidden">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {coaches.map((coach) => (
                <tr key={coach.id} className={coach.id === seatsOfId ? 'row-selected' : undefined}>
                  <td>{coach.position}</td>
                  <td className="mono">{coach.code}</td>
                  <td>{CLASS_LABEL[coach.class]}</td>
                  <td>{coach.standingCapacity}</td>
                  <td className="row-actions">
                    {confirmingId === coach.id ? (
                      <>
                        <span>Delete {coach.code} and its seats?</span>
                        <button type="button" className="button button-danger" onClick={() => void confirmDelete(coach)}>Delete coach</button>
                        <button type="button" className="button" onClick={() => setConfirmingId(null)}>Keep</button>
                      </>
                    ) : (
                      <>
                        <button type="button" className="button" aria-pressed={coach.id === seatsOfId}
                          onClick={() => setSeatsOfId(coach.id === seatsOfId ? null : coach.id)}>
                          {coach.id === seatsOfId ? 'Hide seats' : 'Seats'}
                        </button>
                        <button type="button" className="button" onClick={() => startEdit(coach)}>Edit</button>
                        <button type="button" className="button" onClick={() => setConfirmingId(coach.id)}>Delete</button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table></div>
        </>
      )}

      {/* key includes the class: when the coach's class changes the panel reloads and shows the re-classed seats. */}
      {seatsCoach && <CoachSeats key={`${seatsCoach.id}-${seatsCoach.class}`} coach={seatsCoach} />}
    </section>
  );
}
