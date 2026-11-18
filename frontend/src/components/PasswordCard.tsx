import { keycloak } from '../auth/keycloak';

/**
 * "Password": no form of our own. The button sends the browser to Keycloak's secure sign-in page with the required action UPDATE_PASSWORD
 * (the new password and its confirmation are typed there, and Keycloak's rules for a good password are shown there). Afterwards Keycloak
 * returns the user to /account, and AccountPage shows the result as a message (see takeActionResult in auth/keycloak.ts).
 */
export function PasswordCard() {
  return (
    <section className="panel account-section" aria-labelledby="password-title">
      <h2 id="password-title">Password</h2>
      <p>Your password is changed on the secure sign-in page. You come back here when you are done.</p>
      <div className="actions">
        <button
          type="button"
          className="button"
          onClick={() => void keycloak.login({ action: 'UPDATE_PASSWORD', redirectUri: `${window.location.origin}/account` })}
        >
          Change password
        </button>
      </div>
    </section>
  );
}
