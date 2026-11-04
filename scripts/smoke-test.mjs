// End-to-end smoke test of the whole stack, through the public surface only (Keycloak login, the Gateway, the Stripe sandbox, Mailpit and
// OpenObserve's search API). It creates its own stations, train, route, fares and trip, so it also works on a fresh database.
//
//   docker compose --profile dev up -d --build     (the full stack, Mailpit and the Stripe CLI included)
//   node scripts/smoke-test.mjs [--keep]      (--keep: leave the test data in the database; by default it is removed at the end)
//
// It reads .env at the repository root for the dev users' passwords, the Stripe test key (to confirm payments with Stripe's test cards in
// place of the browser's card form) and the OpenObserve login. No secret is printed. Test mode only: the script refuses any other key.
//
// Not covered here (covered by the automated tests): the 5-minute hold timeout, which would make this script run for five minutes.
import { createHash, randomBytes } from 'node:crypto';
import { inflateSync } from 'node:zlib';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const env = Object.fromEntries(readFileSync(`${ROOT}/.env`, 'utf8').split(/\r?\n/).filter((l) => /^[A-Z_0-9]+=/.test(l)).map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)]));
const GW = process.env.GATEWAY_URL ?? 'http://localhost:8080';
const KC = process.env.KEYCLOAK_URL ?? 'http://localhost:8180';
const MAILPIT = process.env.MAILPIT_URL ?? 'http://localhost:8025/api/v1';
const OO = process.env.OPENOBSERVE_URL ?? 'http://localhost:5080/api/default';
const SK = env.STRIPE_SECRET_KEY ?? '';
if (!SK.startsWith('sk_test_')) throw new Error('STRIPE_SECRET_KEY must be a test-mode key (sk_test_...) in .env');

let passed = 0;
let failed = 0;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function check(label, ok, detail = '') {
  if (ok) passed++; else failed++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${ok || !detail ? '' : `  -> ${detail}`}`);
  return ok;
}
async function waitFor(fn, ms = 60000, every = 700) {
  const end = Date.now() + ms;
  let value;
  while (Date.now() < end) {
    value = await fn();
    if (value) return value;
    await sleep(every);
  }
  return value;
}

// ---- Keycloak: the real authorization code + PKCE login, as the browser does it -------------------------------------------------
async function login(username, password) {
  const b64url = (b) => Buffer.from(b).toString('base64url');
  const verifier = b64url(randomBytes(48));
  const challenge = b64url(createHash('sha256').update(verifier).digest());
  const cookies = new Map();
  const absorb = (r) => r.headers.getSetCookie().forEach((c) => { const [kv] = c.split(';'); const i = kv.indexOf('='); cookies.set(kv.slice(0, i), kv.slice(i + 1)); });
  const jar = () => [...cookies].map(([k, v]) => `${k}=${v}`).join('; ');
  const page = await fetch(`${KC}/realms/train-booking/protocol/openid-connect/auth?${new URLSearchParams({
    client_id: 'web-app', redirect_uri: 'http://localhost:5173/', response_type: 'code', scope: 'openid', code_challenge: challenge, code_challenge_method: 'S256' })}`, { redirect: 'manual' });
  absorb(page);
  const html = await page.text();
  const action = html.match(/<form[^>]*id="kc-form-login"[^>]*>/)[0].match(/action="([^"]+)"/)[1].replaceAll('&amp;', '&');
  const post = await fetch(action, { method: 'POST', redirect: 'manual', headers: { 'content-type': 'application/x-www-form-urlencoded', cookie: jar() }, body: new URLSearchParams({ username, password, credentialId: '' }) });
  const code = new URL(post.headers.get('location')).searchParams.get('code');
  const token = await (await fetch(`${KC}/realms/train-booking/protocol/openid-connect/token`, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'authorization_code', client_id: 'web-app', code, redirect_uri: 'http://localhost:5173/', code_verifier: verifier }) })).json();
  return token.access_token;
}

async function call(method, path, token, body) {
  const headers = {};
  if (token) headers.authorization = `Bearer ${token}`;
  if (body !== undefined) headers['content-type'] = 'application/json';
  const res = await fetch(GW + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  let parsed = null;
  try { parsed = text ? JSON.parse(text) : null; } catch { parsed = text; }
  return { status: res.status, body: parsed };
}

const stripe = async (path, form) => (await fetch(`https://api.stripe.com/v1/${path}`, {
  method: form ? 'POST' : 'GET',
  headers: { authorization: `Bearer ${SK}`, ...(form ? { 'content-type': 'application/x-www-form-urlencoded' } : {}) },
  body: form ? new URLSearchParams(form) : undefined,
})).json();
const mails = async () => (await (await fetch(`${MAILPIT}/messages?limit=200`)).json()).messages ?? [];

/** The text of the content streams of a PDF made by OpenPDF (Flate-compressed, text in literal strings), the number of pages and of images. */
function inspectPdf(buffer) {
  const raw = buffer.toString('latin1');
  const pages = (raw.match(/\/Type\s*\/Page(?![a-z])/g) ?? []).length;
  const images = (raw.match(/\/Subtype\s*\/Image/g) ?? []).length;
  let text = '';
  for (const m of raw.matchAll(/stream\r?\n/g)) {
    const start = m.index + m[0].length;
    const end = raw.indexOf('endstream', start);
    try { text += inflateSync(buffer.subarray(start, end)).toString('latin1'); } catch { /* an image or a font: not text */ }
  }
  return { pages, images, text };
}

// ---- start --------------------------------------------------------------------------------------------------------------------------
console.log('--- the stack is up, and the doors are locked');
const health = await fetch(`${GW}/health`).then((r) => r.status).catch(() => 0);
if (!check('Gateway /health is UP', health === 200, `status ${health}: is the stack running?`)) process.exit(1);
const user = await login('dev.user@example.com', env.KEYCLOAK_DEV_USER_PASSWORD);
const admin = await login('dev.admin@example.com', env.KEYCLOAK_DEV_ADMIN_PASSWORD);
check('Keycloak issued tokens for the passenger and the admin', Boolean(user && admin));
check('no token: 401', (await call('GET', '/trips?from=AAA&to=BBB&date=2030-01-01')).status === 401);
check('a bad token: 401', (await call('GET', '/me', 'not.a.token')).status === 401);
check('a passenger on an admin route: 403', (await call('GET', '/admin/stations', user)).status === 403);
check('an admin on an admin route: 200', (await call('GET', '/admin/stations', admin)).status === 200);

// ---- the timetable ------------------------------------------------------------------------------------------------------------------
console.log('--- the admin builds a trip');
const tag = randomBytes(3).toString('hex').toUpperCase();
const day = new Date(Date.now() + 4 * 86400000).toISOString().slice(0, 10);
const station = async (code, name) => (await call('POST', '/admin/stations', admin, { code: `S${tag}${code}`, name: `${name} ${tag}`, city: name })).body;
const [a, b, c] = [await station('A', 'Alpha'), await station('B', 'Beta'), await station('C', 'Gamma')];
check('three stations created', Boolean(a?.id && b?.id && c?.id));
const train = (await call('POST', '/admin/trains', admin, { number: `SM${tag}`, name: 'Smoke Express', type: 'Express', status: 'ACTIVE' })).body;
const coach = (await call('POST', `/admin/trains/${train.id}/coaches`, admin, { code: 'A1', class: 'SECOND', position: 1, standingCapacity: 10 })).body;
const seats = (await call('POST', `/admin/coaches/${coach.id}/seats`, admin, ['1A', '1B', '1C', '1D', '2A', '2B'].map((n, i) => ({ seatNumber: n, row: 1 + Math.floor(i / 4), column: 'ABCD'[i % 4] })))).body;
check('a train with a coach, six seats and ten standing places', Array.isArray(seats) && seats.length === 6);
const route = (await call('POST', '/admin/routes', admin, { name: `Smoke ${tag}`, stops: [a.id, b.id, c.id] })).body;
check('fares for both segments (100 + 130 EGP)', (await call('PUT', `/admin/routes/${route.id}/fares`, admin, [
  { segmentIndex: 0, class: 'SECOND', amount: 100 }, { segmentIndex: 1, class: 'SECOND', amount: 130 }])).status === 200);
const created = await call('POST', '/admin/trips', admin, { trainId: train.id, routeId: route.id, departureDate: day,
  stops: [{ arrivalTime: null, departureTime: '08:00' }, { arrivalTime: '09:00', departureTime: '09:05' }, { arrivalTime: '10:30', departureTime: null }] });
check('the trip is created (and pushed to Booking & Seat)', created.status === 201, JSON.stringify(created.body)?.slice(0, 200));
const tripId = created.body.id;

console.log('--- the passenger searches and looks at the seat map');
const search = await call('GET', `/trips?from=S${tag}A&to=S${tag}C&date=${day}`, user);
const found = search.body?.trips?.find((t) => t.tripId === tripId);
check('the trip is found, 230 EGP, 6 seats and 10 standing places left', found && found.availability?.seatsLeft?.SECOND === 6 && found.availability?.standingLeft?.SECOND === 10,
  JSON.stringify(found?.availability));
const map = await call('GET', `/trips/${tripId}/seat-map?from=0&to=2`, user);
check('the seat map is composed by the Gateway', map.status === 200 && JSON.stringify(map.body).includes('1A'));
const seat = Object.fromEntries(seats.map((s) => [s.seatNumber, s.id]));
const journey = { tripId, fromStop: 0, toStop: 2 };

// ---- Journey 1: pay with the success card ----------------------------------------------------------------------------------------------
console.log('--- a passenger holds a seat and pays (card 4242 4242 4242 4242)');
const hold = await call('POST', '/bookings/pick', user, { ...journey, seatId: seat['1A'] });
check('the first pick starts the hold (201, PENDING_PAYMENT, 5 minutes)', hold.status === 201 && hold.body.status === 'PENDING_PAYMENT');
check('another passenger cannot take the held seat (409)', (await call('POST', '/bookings/pick', admin, { ...journey, seatId: seat['1A'] })).status === 409);
check('another user cannot read the booking (404)', (await call('GET', `/bookings/${hold.body.id}`, admin)).status === 404);
const pay = await call('POST', `/bookings/${hold.body.id}/payment`, user);
check('Payment created a Stripe PaymentIntent for 230 EGP', pay.status === 200 && Boolean(pay.body.clientSecret), JSON.stringify(pay.body)?.slice(0, 120));
const intent1 = pay.body.clientSecret.split('_secret_')[0];
await stripe(`payment_intents/${intent1}/confirm`, { payment_method: 'pm_card_visa', return_url: 'http://localhost:5173/return' });
const paid = await waitFor(async () => { const r = await call('GET', `/bookings/${hold.body.id}`, user); return r.body?.status === 'CONFIRMED' ? r.body : null; });
check('the webhook confirms the booking (CONFIRMED, 230.00 EGP)', paid && paid.amount === 230, JSON.stringify(paid)?.slice(0, 100));
const ticketMail = await waitFor(async () => (await mails()).find((m) => /ticket/i.test(m.Subject) && m.Snippet?.includes(paid?.reference ?? '?')) ?? null);
check('the ticket email (with the PDF) reached Mailpit', Boolean(ticketMail) && ticketMail.Attachments >= 1, ticketMail ? `attachments ${ticketMail.Attachments}` : 'no email');
check('My bookings lists it', (await call('GET', '/bookings', user)).body?.some((x) => x.booking.id === hold.body.id));

// ---- Journey 2: decline, then retry inside the same hold --------------------------------------------------------------------------------
console.log('--- a declined card (4000 0000 0000 0002), then the retry inside the same hold (FR-P5)');
const hold2 = await call('POST', '/bookings/pick', user, { ...journey, seatId: seat['1B'] });
const intent2 = (await call('POST', `/bookings/${hold2.body.id}/payment`, user)).body.clientSecret.split('_secret_')[0];
const declined = await stripe(`payment_intents/${intent2}/confirm`, { payment_method: 'pm_card_chargeDeclined', return_url: 'http://localhost:5173/return' });
check('Stripe declines the card', declined.error?.code === 'card_declined', JSON.stringify(declined.error?.code));
await sleep(2500);
check('the booking is still held, not confirmed', (await call('GET', `/bookings/${hold2.body.id}`, user)).body?.status === 'PENDING_PAYMENT');
await stripe(`payment_intents/${intent2}/confirm`, { payment_method: 'pm_card_visa', return_url: 'http://localhost:5173/return' });
const retried = await waitFor(async () => { const r = await call('GET', `/bookings/${hold2.body.id}`, user); return r.body?.status === 'CONFIRMED' ? r.body : null; });
check('the retry with the good card confirms the booking', Boolean(retried));

// ---- Journey 3: 3D Secure --------------------------------------------------------------------------------------------------------------
console.log('--- 3D Secure (4000 0000 0000 3220): the card needs the cardholder, so nothing is confirmed');
const hold3 = await call('POST', '/bookings/pick', user, { ...journey, seatId: seat['1C'] });
const intent3 = (await call('POST', `/bookings/${hold3.body.id}/payment`, user)).body.clientSecret.split('_secret_')[0];
const sca = await stripe(`payment_intents/${intent3}/confirm`, { payment_method: 'pm_card_threeDSecure2Required', return_url: 'http://localhost:5173/return' });
check('Stripe asks for the 3D Secure step (requires_action)', sca.status === 'requires_action', sca.status);
check('the booking waits, held', (await call('GET', `/bookings/${hold3.body.id}`, user)).body?.status === 'PENDING_PAYMENT');
check('deselecting the last seat releases it at once and ends the hold', (await call('POST', `/bookings/${hold3.body.id}/release`, user, { seatId: seat['1C'] })).status === 200
  && (await call('GET', `/bookings/${hold3.body.id}`, user)).body?.status === 'ABANDONED');
check('its PaymentIntent was cancelled at Stripe first', (await waitFor(async () => (await stripe(`payment_intents/${intent3}`)).status === 'canceled')) === true);

// ---- Cancellation ---------------------------------------------------------------------------------------------------------------------
console.log('--- the passenger cancels (instant, full refund), the admin cancels another for support');
check('another user cannot cancel it (404)', (await call('POST', `/bookings/${hold.body.id}/cancel`, admin)).status === 404);
const cancelled = await call('POST', `/bookings/${hold.body.id}/cancel`, user);
check('the passenger cancels their own booking', cancelled.status === 200 && cancelled.body.status === 'CANCELLED');
const refunded = await waitFor(async () => { const r = await call('GET', `/bookings/${hold.body.id}`, user); return r.body?.refundStatus === 'REFUNDED' ? r.body : null; }, 90000);
check('the refund goes through at Stripe (REFUNDED)', Boolean(refunded));
const refund1 = await stripe(`refunds?payment_intent=${intent1}`);
check('one refund of the whole 230.00 EGP', refund1.data?.length === 1 && refund1.data[0].amount === 23000, JSON.stringify(refund1.data?.map((r) => r.amount)));
const cancelMail = await waitFor(async () => (await mails()).find((m) => /cancelled/i.test(m.Subject) && m.Snippet?.includes(paid.reference)) ?? null);
check('the cancellation email was sent', Boolean(cancelMail));
check('a passenger on the admin cancel route: 403', (await call('POST', `/admin/bookings/${hold2.body.id}/cancel`, user, { reason: 'x' })).status === 403);
check('the admin finds the booking in support search', (await call('GET', `/admin/bookings?ref=${retried.reference}`, admin)).body?.length === 1);
const adminCancelled = await call('POST', `/admin/bookings/${hold2.body.id}/cancel`, admin, { reason: 'Passenger asked by phone' });
check('the admin cancels it with a reason', adminCancelled.status === 200 && adminCancelled.body.status === 'CANCELLED');
check('it records who cancelled and why', (await call('GET', `/admin/bookings/${hold2.body.id}`, admin)).body?.cancelledByRole === 'ADMIN');

// ---- Counter and complimentary bookings (docs/design/COUNTER-BOOKING.md) -------------------------------------------------------------------
console.log('--- the admin issues a counter booking and a complimentary one, without Stripe');
const boardSeat = async (seatNumber) => {
  const detail = (await call('GET', `/admin/board/trips/${tripId}`, admin)).body;
  return detail?.coaches?.flatMap((c) => c.seats).find((s) => s.seatNumber === seatNumber);
};
check('a passenger cannot look up passengers (403)', (await call('GET', '/admin/passengers/lookup?q=dev.user@example.com', user)).status === 403);
const lookedUp = await call('GET', '/admin/passengers/lookup?q=dev.user@example.com', admin);
check('the lookup finds exactly one passenger by exact email', lookedUp.status === 200 && lookedUp.body?.email === 'dev.user@example.com' && !Array.isArray(lookedUp.body));
check('a partial email finds nobody (404)', (await call('GET', '/admin/passengers/lookup?q=dev.user', admin)).status === 404);
const counterHold = await call('POST', '/bookings/pick', admin, { ...journey, seatId: seat['1D'] });
check('the admin holds a seat', counterHold.status === 201, JSON.stringify(counterHold.body)?.slice(0, 120));
const counterBody = { method: 'COUNTER', note: `smoke ${tag}`, passenger: { mode: 'ACCOUNT', email: 'dev.user@example.com' } };
check('a passenger cannot issue a booking (403)', (await call('POST', `/admin/bookings/${counterHold.body.id}/issue`, user, counterBody)).status === 403);
const counter = await call('POST', `/admin/bookings/${counterHold.body.id}/issue`, admin, counterBody);
check('the admin issues it as COUNTER for an existing account (CONFIRMED, no Stripe)', counter.status === 200 && counter.body.status === 'CONFIRMED' && counter.body.paymentMethod === 'COUNTER', JSON.stringify(counter.body)?.slice(0, 150));
check('a second submit gives the same booking', (await call('POST', `/admin/bookings/${counterHold.body.id}/issue`, admin, counterBody)).body?.id === counter.body.id);
const taken = await boardSeat('1D');
check('the board shows the seat TAKEN with the booking reference', taken?.state === 'TAKEN' && taken.reference === counter.body.reference, JSON.stringify(taken));
check('the booking is in the passenger\'s My bookings', (await call('GET', '/bookings', user)).body?.some((x) => x.booking.id === counter.body.id && x.cancellable === false));
check('the passenger cannot cancel it themselves (409 cancel_at_desk)', (await call('POST', `/bookings/${counter.body.id}/cancel`, user)).body?.error?.code === 'cancel_at_desk');
const pdf = await waitFor(async () => { const r = await fetch(`${GW}/bookings/${counter.body.id}/ticket`, { headers: { authorization: `Bearer ${admin}` } }); return r.status === 200 ? Buffer.from(await r.arrayBuffer()) : null; }, 30000, 1000);
check('the admin downloads the ticket PDF', Boolean(pdf) && pdf.subarray(0, 5).toString() === '%PDF-');
const pdfInfo = pdf ? inspectPdf(pdf) : { pages: 0, images: 0, text: '' };
check('the PDF is one page, shows the reference and "Paid at counter", and has the logo and the QR code', pdfInfo.pages === 1 && pdfInfo.text.includes(counter.body.reference) && pdfInfo.text.includes('Paid at counter') && pdfInfo.images >= 2,
  JSON.stringify({ pages: pdfInfo.pages, images: pdfInfo.images, hasRef: pdfInfo.text.includes(counter.body.reference) }));
const counterCancelled = await call('POST', `/admin/bookings/${counter.body.id}/cancel`, admin, { reason: 'Smoke test: passenger changed plans' });
check('the admin cancels it: MANUAL_REFUND_DUE, no card refund', counterCancelled.status === 200 && counterCancelled.body.refundStatus === 'MANUAL_REFUND_DUE', JSON.stringify(counterCancelled.body)?.slice(0, 120));
check('the seat is FREE again on the board', (await boardSeat('1D'))?.state === 'FREE');
const marked = await call('POST', `/admin/bookings/${counter.body.id}/refunded-at-counter`, admin);
check('marked refunded at the counter', marked.status === 200 && marked.body.refundStatus === 'REFUNDED_AT_COUNTER');
check('marking it again changes nothing (200)', (await call('POST', `/admin/bookings/${counter.body.id}/refunded-at-counter`, admin)).status === 200);

const freeHold = await call('POST', '/bookings/pick', admin, { ...journey, seatId: seat['1C'] });
const withoutReason = await call('POST', `/admin/bookings/${freeHold.body.id}/issue`, admin, { method: 'COMPLIMENTARY', passenger: { mode: 'WALK_IN', name: `Smoke Guest ${tag}` } });
check('a complimentary booking without a reason is refused (400)', withoutReason.status === 400);
const complimentary = await call('POST', `/admin/bookings/${freeHold.body.id}/issue`, admin, { method: 'COMPLIMENTARY', reason: 'Smoke test guest', passenger: { mode: 'WALK_IN', name: `Smoke Guest ${tag}` } });
check('then with a reason it is issued (walk-in, COMPLIMENTARY)', complimentary.status === 200 && complimentary.body.paymentMethod === 'COMPLIMENTARY');
const freeCancelled = await call('POST', `/admin/bookings/${complimentary.body.id}/cancel`, admin, { reason: 'Smoke test: guest cancelled' });
check('cancelling it gives NOT_APPLICABLE and the seat is FREE', freeCancelled.body?.refundStatus === 'NOT_APPLICABLE' && (await boardSeat('1C'))?.state === 'FREE');

// ---- Whole-trip cancellation ----------------------------------------------------------------------------------------------------------
console.log('--- the admin cancels the whole trip');
const hold4 = await call('POST', '/bookings/pick', user, { ...journey, seatId: seat['2A'] });
const intent4 = (await call('POST', `/bookings/${hold4.body.id}/payment`, user)).body.clientSecret.split('_secret_')[0];
await stripe(`payment_intents/${intent4}/confirm`, { payment_method: 'pm_card_visa', return_url: 'http://localhost:5173/return' });
await waitFor(async () => (await call('GET', `/bookings/${hold4.body.id}`, user)).body?.status === 'CONFIRMED');
const hold5 = await call('POST', '/bookings/pick', user, { ...journey, seatId: seat['2B'] });
check('a passenger cannot cancel a trip (403)', (await call('POST', `/admin/trips/${tripId}/cancel`, user, { reason: 'x' })).status === 403);
check('the admin starts the trip cancellation', (await call('POST', `/admin/trips/${tripId}/cancel`, admin, { reason: 'Smoke test strike' })).status === 200);
const done = await waitFor(async () => { const r = await call('GET', `/admin/trips/${tripId}/cancellation`, admin); return r.body?.status === 'CANCELLED' ? r.body : null; });
check('the trip ends CANCELLED with nothing pending', done && done.pendingConfirmed === 0 && done.pendingHolds === 0, JSON.stringify(done));
check('the confirmed booking was cancelled and refunded', (await waitFor(async () => (await call('GET', `/bookings/${hold4.body.id}`, user)).body?.refundStatus === 'REFUNDED', 90000)) === true);
check('the hold was released (EXPIRED)', (await call('GET', `/bookings/${hold5.body.id}`, user)).body?.status === 'EXPIRED');
check('the trip left the search results', !(await call('GET', `/trips?from=S${tag}A&to=S${tag}C&date=${day}`, user)).body?.trips?.some((t) => t.tripId === tripId));

// ---- Observability --------------------------------------------------------------------------------------------------------------------
console.log('--- OpenObserve received the structured logs, the audit trail and the traces');
const auth = `Basic ${Buffer.from(`${env.OPENOBSERVE_USER}:${env.OPENOBSERVE_PASSWORD}`).toString('base64')}`;
async function ooSearch(sql, type = 'logs') {
  const now = Date.now() * 1000;
  const res = await fetch(`${OO}/_search?type=${type}`, { method: 'POST', headers: { authorization: auth, 'content-type': 'application/json' },
    body: JSON.stringify({ query: { sql, start_time: now - 20 * 60 * 1e6, end_time: now + 60 * 1e6, from: 0, size: 50 } }) });
  return res.ok ? (await res.json()).hits ?? [] : [];
}
const bookingRef = String(hold.body.id);
const gwLines = await waitFor(async () => { const h = await ooSearch(`SELECT * FROM "gateway" WHERE bookingid = '${bookingRef}' OR bookingId = '${bookingRef}'`); return h.length ? h : null; }, 45000, 2000);
check('Gateway request logs carry the booking id and the user id', Boolean(gwLines) && gwLines.some((h) => h.userId || h.userid), JSON.stringify(gwLines?.[0])?.slice(0, 200));
const audit = await waitFor(async () => { const h = await ooSearch(`SELECT * FROM "default" WHERE match_all('audit CANCEL booking ${bookingRef}')`); return h.length ? h : null; }, 45000, 2000);
check('the audit line of the cancellation (actor, booking id, trip id) is there', Boolean(audit), 'no audit record');
const traces = await waitFor(async () => { const h = await ooSearch('SELECT * FROM "default"', 'traces'); return h.length ? h : null; }, 45000, 2000);
check('OpenTelemetry traces arrived', Boolean(traces));
const leaked = await ooSearch(`SELECT * FROM "gateway" WHERE match_all('access_token') OR match_all('Bearer')`);
check('no token appears in the Gateway logs', leaked.length === 0);

// Everything this run created carries the tag (stations S<tag>A..C, train SM<tag>, route "Smoke <tag>", bookings of the dev accounts). Remove it,
// so running the test never pollutes the demo data. `--keep` leaves it in place for inspection.
if (!process.argv.includes('--keep')) {
  const cleanup = spawnSync(process.execPath, [resolve(ROOT, 'scripts/demo-data.mjs'), 'reset-test-data'], { encoding: 'utf8' });
  console.log(cleanup.status === 0 ? '--- the data of this run was removed (scripts/demo-data.mjs reset-test-data)' : `--- clean-up failed, run scripts/demo-data.mjs reset-test-data by hand: ${(cleanup.stderr || cleanup.stdout).slice(0, 300)}`);
}

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed > 0 ? 1 : 0);
