import { useEffect, useState, type FormEvent } from 'react';
import { loadProfile, saveProfile, type AccountProfile } from '../accountApi';
import { useAuth } from '../auth/AuthContext';
import { ErrorState } from './ErrorState';
import { ListSkeleton } from './ListSkeleton';
import { useToast } from './Toasts';

const MAX_NAME = 255;

/** The words under a name box when it is empty or too long; '' when it is fine. */
function nameProblem(value: string, label: string): string {
  const text = value.trim();
  if (text === '') return `Enter your ${label}.`;
  if (text.length > MAX_NAME) return `Use at most ${MAX_NAME} characters.`;
  return '';
}

/**
 * "Your details": first and last name can be changed, the email and username are shown as text (the realm uses the email as the sign-in name).
 * The profile is read from and saved to Keycloak's Account API (see accountApi.ts). Each name box is checked when the user leaves it and again
 * on Save; the words Keycloak sends back for a refused value are shown under the same box.
 */
// After a save the access token is renewed (refreshUser) so the name in the header and the account card changes at once.
export function ProfileCard() {
  const { refreshUser } = useAuth();
  const toast = useToast();
  const [profile, setProfile] = useState<AccountProfile | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  async function load() {
    setLoadFailed(false);
    const loaded = await loadProfile();
    if (!loaded) {
      setLoadFailed(true);
      return;
    }
    setProfile(loaded);
    setFirstName(loaded.firstName);
    setLastName(loaded.lastName);
  }

  useEffect(() => {
    void load();
  }, []);

  const dirty = profile !== null && (firstName.trim() !== profile.firstName || lastName.trim() !== profile.lastName);

  function check(field: 'firstName' | 'lastName') {
    const problem = field === 'firstName' ? nameProblem(firstName, 'first name') : nameProblem(lastName, 'last name');
    setErrors((now) => ({ ...now, [field]: problem }));
    return problem === '';
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!profile) return;
    setFormError(null);
    const valid = [check('firstName'), check('lastName')].every(Boolean);
    if (!valid) return;
    setSaving(true);
    const result = await saveProfile(profile, firstName.trim(), lastName.trim());
    setSaving(false);
    if (!result.ok) {
      setErrors(result.fieldErrors);
      setFormError(result.message);
      return;
    }
    setErrors({});
    toast.show('Your details are saved');
    await refreshUser().catch(() => undefined);
    await load();
  }

  return (
    <section className="panel account-section" aria-labelledby="profile-title">
      <h2 id="profile-title">Your details</h2>
      {profile === null && !loadFailed && <ListSkeleton rows={3} label="Loading your details…" />}
      {loadFailed && <ErrorState message="Your details could not be loaded from the sign-in service." onRetry={() => void load()} />}
      {profile && (
        <form onSubmit={submit} noValidate>
          <div className="field">
            <label htmlFor="profile-first">First name</label>
            <input
              id="profile-first"
              value={firstName}
              autoComplete="given-name"
              aria-invalid={errors.firstName ? true : undefined}
              aria-describedby="profile-first-error"
              onChange={(e) => setFirstName(e.target.value)}
              onBlur={() => check('firstName')}
            />
            <span id="profile-first-error" className="field-error">{errors.firstName}</span>
          </div>
          <div className="field">
            <label htmlFor="profile-last">Last name</label>
            <input
              id="profile-last"
              value={lastName}
              autoComplete="family-name"
              aria-invalid={errors.lastName ? true : undefined}
              aria-describedby="profile-last-error"
              onChange={(e) => setLastName(e.target.value)}
              onBlur={() => check('lastName')}
            />
            <span id="profile-last-error" className="field-error">{errors.lastName}</span>
          </div>

          <dl className="account-readonly">
            <div>
              <dt>Email</dt>
              <dd>{profile.email ?? 'Not set'}</dd>
            </div>
            <div>
              <dt>Username</dt>
              <dd>{profile.username}</dd>
            </div>
          </dl>
          <p className="muted account-note">Your email is your sign-in name and can't be changed here.</p>

          {formError && <p className="notice notice-error" role="alert">{formError}</p>}
          <div className="actions">
            <button type="submit" className="button button-primary" disabled={saving || !dirty}>{saving ? 'Saving…' : 'Save details'}</button>
            {dirty && !saving && (
              <button type="button" className="button" onClick={() => { setFirstName(profile.firstName); setLastName(profile.lastName); setErrors({}); setFormError(null); }}>
                Undo changes
              </button>
            )}
          </div>
        </form>
      )}
    </section>
  );
}
