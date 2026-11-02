import { useAuth } from '../auth/AuthContext';

/** Shown when the session cannot be renewed. The Sign in button returns to this same page. */
export function SessionEnded() {
  const { sessionEnded, login } = useAuth();
  if (!sessionEnded) return null;
  return (
    <div className="session-ended-backdrop">
      <div className="session-ended" role="alertdialog" aria-modal="true" aria-labelledby="session-ended-title" aria-describedby="session-ended-text">
        <h2 id="session-ended-title">Your session ended</h2>
        <p id="session-ended-text">For your safety we signed you out after a while. Sign in again and we will bring you back to this page.</p>
        <button type="button" className="button button-primary" autoFocus onClick={() => login(window.location.href)}>
          Sign in
        </button>
      </div>
    </div>
  );
}
