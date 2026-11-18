import { useEffect, useState } from 'react';
import { loadSessions, signOutOtherSessions, type AccountSession } from '../accountApi';
import { ErrorState } from './ErrorState';
import { ListSkeleton } from './ListSkeleton';
import { useToast } from './Toasts';

/** "Nov 18, 12:44" in the visitor's own time zone (a session time is a moment, unlike a train time). Keycloak sends seconds. */
function when(seconds: number): string {
  return new Date(seconds * 1000).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

/**
 * "Where you are signed in": the user's active sessions from Keycloak (browser and system, address, when it started, when it was last used,
 * and which one is this device), and one action that ends all the others. The action asks first and says what it will do.
 */
export function SessionsCard() {
  const toast = useToast();
  const [sessions, setSessions] = useState<AccountSession[] | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);

  async function load() {
    setLoadFailed(false);
    const loaded = await loadSessions();
    if (loaded === null) setLoadFailed(true);
    else setSessions(loaded);
  }

  useEffect(() => {
    void load();
  }, []);

  const others = sessions?.filter((s) => !s.current).length ?? 0;

  async function signOutOthers() {
    setBusy(true);
    const done = await signOutOtherSessions();
    setBusy(false);
    setConfirming(false);
    if (done) toast.show(`Signed out of ${others} other ${others === 1 ? 'session' : 'sessions'}`);
    else toast.show('We could not sign out the other sessions. Try again in a moment.', 'error');
    await load();
  }

  return (
    <section className="panel account-section" aria-labelledby="sessions-title">
      <h2 id="sessions-title">Where you are signed in</h2>
      {sessions === null && !loadFailed && <ListSkeleton rows={2} label="Loading your sessions…" />}
      {loadFailed && <ErrorState message="Your sessions could not be loaded from the sign-in service." onRetry={() => void load()} />}
      {sessions && (
        <>
          <ul className="session-list">
            {sessions.map((s) => (
              <li key={s.id}>
                <div>
                  <p className="session-name">
                    {s.label}
                    {s.current && <span className="status-chip is-ok">This device</span>}
                  </p>
                  <p className="muted session-meta">
                    {s.ipAddress && <>{s.ipAddress} · </>}Started {when(s.started)} · Last seen {when(s.lastAccess)}
                  </p>
                </div>
              </li>
            ))}
          </ul>

          {others === 0 && <p className="muted">You are not signed in anywhere else.</p>}
          {others > 0 && !confirming && (
            <div className="actions">
              <button type="button" className="button" onClick={() => setConfirming(true)}>Sign out other sessions</button>
            </div>
          )}
          {confirming && (
            <div className="cancel-confirm" role="alertdialog" aria-label="Confirm signing out other sessions">
              <p className="cancel-confirm-title">Sign out {others} other {others === 1 ? 'session' : 'sessions'}?</p>
              <p>
                Every other browser and device is signed out at once and has to sign in again. This device stays signed in.
              </p>
              <div className="booking-actions">
                <button type="button" className="button button-danger-solid" disabled={busy} onClick={() => void signOutOthers()}>
                  {busy ? 'Signing out…' : 'Sign out other sessions'}
                </button>
                <button type="button" className="button" disabled={busy} onClick={() => setConfirming(false)}>Keep them signed in</button>
              </div>
            </div>
          )}
        </>
      )}
    </section>
  );
}
