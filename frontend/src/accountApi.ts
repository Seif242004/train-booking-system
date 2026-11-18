import { getFreshToken } from './auth/keycloak';
import { config } from './config';

// Keycloak's own Account REST API, called straight from the browser with the signed-in user's access token (the token carries the
// audience "account" and the role manage-account; the client's web origin allows the call). It is not routed through the Gateway.
// Nothing here ever logs or stores a token or a value the user typed.
const ACCOUNT_URL = `${config.keycloakUrl}/realms/${config.keycloakRealm}/account`;

/** What every call returns: the HTTP status (0 when Keycloak could not be reached) and the parsed JSON body, if any. */
export interface AccountResult {
  status: number;
  body: unknown;
}

async function accountRequest(method: string, path: string, body?: unknown): Promise<AccountResult> {
  const headers: Record<string, string> = { Authorization: `Bearer ${await getFreshToken()}`, Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  try {
    const response = await fetch(`${ACCOUNT_URL}${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    const text = await response.text();
    let parsed: unknown = null;
    try {
      if (text !== '') parsed = JSON.parse(text);
    } catch {
      // Not JSON: nothing useful to show.
    }
    return { status: response.status, body: parsed };
  } catch {
    return { status: 0, body: null };
  }
}

// ---- Profile ----------------------------------------------------------------------------------------------------------------------

export interface AccountProfile {
  username: string;
  email: string | null;
  firstName: string;
  lastName: string;
  /** Attribute names the realm does not let the user change (here username and email). */
  readOnly: string[];
}

interface ProfileAnswer {
  username?: string;
  email?: string;
  firstName?: string;
  lastName?: string;
  userProfileMetadata?: { attributes?: { name: string; readOnly?: boolean }[] };
}

/** Reads the signed-in user's profile (GET /account). null when Keycloak did not answer with a profile. */
export async function loadProfile(): Promise<AccountProfile | null> {
  const result = await accountRequest('GET', '');
  if (result.status !== 200) return null;
  const data = result.body as ProfileAnswer;
  return {
    username: data.username ?? '',
    email: data.email ?? null,
    firstName: data.firstName ?? '',
    lastName: data.lastName ?? '',
    readOnly: (data.userProfileMetadata?.attributes ?? []).filter((a) => a.readOnly).map((a) => a.name),
  };
}

export type ProfileSave = { ok: true } | { ok: false; fieldErrors: Record<string, string>; message: string | null };

// Keycloak answers a refused value with a code such as "error-user-attribute-required"; these are the words the passenger sees.
const PROFILE_ERROR_WORDS: Record<string, (label: string) => string> = {
  'error-user-attribute-required': (label) => `Enter your ${label}.`,
  'error-invalid-length-too-long': () => 'Use at most 255 characters.',
  'error-invalid-length-too-short': () => 'This is too short.',
  'error-person-name-invalid-character': () => 'Use letters, spaces, apostrophes and hyphens only.',
};
const FIELD_LABELS: Record<string, string> = { firstName: 'first name', lastName: 'last name' };

/** Saves the first and last name (POST /account). The username and email are sent unchanged: the realm treats them as read-only. */
export async function saveProfile(current: AccountProfile, firstName: string, lastName: string): Promise<ProfileSave> {
  const result = await accountRequest('POST', '', { username: current.username, email: current.email, firstName, lastName });
  if (result.status === 204 || result.status === 200) return { ok: true };
  const errors = (result.body as { errors?: { field: string; errorMessage: string }[] } | null)?.errors ?? [];
  if (result.status === 400 && errors.length > 0) {
    const fieldErrors: Record<string, string> = {};
    for (const e of errors) {
      const words = PROFILE_ERROR_WORDS[e.errorMessage];
      fieldErrors[e.field] = words ? words(FIELD_LABELS[e.field] ?? 'value') : 'The sign-in service did not accept this value.';
    }
    return { ok: false, fieldErrors, message: null };
  }
  return {
    ok: false,
    fieldErrors: {},
    message: result.status === 0 ? 'Cannot reach the sign-in service. Check your connection and try again.' : 'We could not save your details. Try again in a moment.',
  };
}
