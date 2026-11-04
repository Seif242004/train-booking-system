// Demo data and test-data cleanup for the dev stack.
//
//   node scripts/demo-data.mjs reset-test-data [--dry-run]   remove what smoke, check and stress runs left behind (never users, never Keycloak)
//   node scripts/demo-data.mjs seed-demo                      stations, lines, trains, trips (today + 14 days), demo bookings; safe to run again
//   node scripts/demo-data.mjs reset-demo [--dry-run]         remove exactly what seed-demo made, and nothing else
//
// The timetable (stations, trains, coaches, seats, routes, fares, trips, the cancelled showcase trip) is made through the Gateway's admin
// endpoints, like the admin pages do. Direct database access is used ONLY for what the API cannot do: confirmed demo bookings on trips that
// have already left or are full, and the removal of test rows that the API refuses to delete because they carry bookings. That path is
// dev-only: it refuses to run unless the stack runs with the dev profile (Mailpit up) and the Stripe key is a test key. It never disables a
// constraint, writes through the same tables and keys as the service (one reservation per seat and segment), and marks every row it makes
// (booking reference DEMO-..., passenger id demo-passenger) so reset-demo removes only those.
//
// Before reset-test-data or reset-demo deletes anything it writes the counts and the ids to .local/ (git-ignored) so they can be audited.
import { spawnSync } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const env = Object.fromEntries(readFileSync(`${ROOT}/.env`, 'utf8').split(/\r?\n/).filter((l) => /^[A-Z_0-9]+=/.test(l)).map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)]));
const GW = process.env.GATEWAY_URL ?? 'http://localhost:8080';
const KC = process.env.KEYCLOAK_URL ?? 'http://localhost:8180';
const ORACLE_CONTAINER = process.env.ORACLE_CONTAINER ?? 'tbs-oracle';
const ZONE = process.env.TIMEZONE ?? 'Africa/Cairo';

// What the scripts and the seed use to mark their rows.
const DEMO_PASSENGER = 'demo-passenger';
const DEMO_EMAIL = 'demo.passenger@example.com';
const REF_PREFIX = 'DEMO-';
const TEST_USERS = ['dev.user@example.com', 'dev.admin@example.com']; // the accounts the smoke and check scripts log in as (the accounts themselves stay)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (...a) => console.log(...a);

// ---------------------------------------------------------------------------------------------------------------------------------
// The Gateway as the admin (a real Keycloak login with PKCE, as the browser does it)
// ---------------------------------------------------------------------------------------------------------------------------------
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

let adminToken = null;
let adminTokenAt = 0;
async function token() {
  if (!adminToken || Date.now() - adminTokenAt > 200_000) { // Keycloak tokens last 5 minutes: renew early
    adminToken = await login('dev.admin@example.com', env.KEYCLOAK_DEV_ADMIN_PASSWORD);
    adminTokenAt = Date.now();
  }
  return adminToken;
}

async function call(method, path, body) {
  const headers = { authorization: `Bearer ${await token()}` };
  if (body !== undefined) headers['content-type'] = 'application/json';
  const res = await fetch(GW + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(60_000) });
  const text = await res.text();
  let parsed = null;
  try { parsed = text ? JSON.parse(text) : null; } catch { parsed = text; }
  return { status: res.status, body: parsed };
}

async function must(label, promise, ok = [200, 201, 204]) {
  const r = await promise;
  if (!ok.includes(r.status)) throw new Error(`${label} failed: HTTP ${r.status} ${JSON.stringify(r.body)?.slice(0, 300)}`);
  return r.body;
}

// ---------------------------------------------------------------------------------------------------------------------------------
// The database, for the dev-only path. Runs sqlplus inside the Oracle container; the password goes in through stdin, never on a command line.
// ---------------------------------------------------------------------------------------------------------------------------------
const SCHEMAS = { train_schedule: 'TRAIN_SCHEDULE_DB_PASSWORD', booking_seat: 'BOOKING_SEAT_DB_PASSWORD', payment: 'PAYMENT_DB_PASSWORD', ticket: 'TICKET_DB_PASSWORD', notification: 'NOTIFICATION_DB_PASSWORD' };

function dockerRunning() {
  const r = spawnSync('docker', ['ps', '--format', '{{.Names}}'], { encoding: 'utf8' });
  return r.status === 0 ? r.stdout.split(/\r?\n/).filter(Boolean) : [];
}

/** The dev-only path is allowed only on the dev stack: Oracle and Mailpit (profile dev) are up and Stripe is in test mode. */
function requireDevStack() {
  const running = dockerRunning();
  if (!running.includes(ORACLE_CONTAINER) || !running.includes('tbs-mailpit')) {
    throw new Error('Refusing the database path: the dev stack is not running (need the containers tbs-oracle and tbs-mailpit: docker compose --profile dev up -d).');
  }
  if (!(env.STRIPE_SECRET_KEY ?? '').startsWith('sk_test_') || (env.STRIPE_PUBLISHABLE_KEY && !env.STRIPE_PUBLISHABLE_KEY.startsWith('pk_test_'))) {
    throw new Error('Refusing the database path: the Stripe keys in .env are not test-mode keys.');
  }
}

function sql(schema, script) {
  requireDevStack();
  const password = env[SCHEMAS[schema]];
  if (!password) throw new Error(`No password for the ${schema} schema in .env`);
  const input = `whenever sqlerror exit failure rollback\nset define off\nset sqlblanklines on\nconnect ${schema}/"${password}"@//localhost:1521/FREEPDB1\n`
    + `set pagesize 0 feedback off heading off linesize 32767 trimspool on\n${script}\nexit\n`;
  const r = spawnSync('docker', ['exec', '-i', ORACLE_CONTAINER, 'sqlplus', '-s', '/nolog'], { input, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 });
  const out = `${r.stdout ?? ''}${r.stderr ?? ''}`;
  if (r.status !== 0 || /ORA-\d{5}|SP2-\d{4}/.test(out)) throw new Error(`SQL failed in ${schema}: ${out.replaceAll(password, '***').slice(0, 800)}`);
  return out;
}
const rows = (schema, select) => sql(schema, `${select};`).split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
const num = (schema, select) => Number(rows(schema, select)[0] ?? 0);
const inList = (ids) => (ids.length ? ids.join(',') : 'NULL');
function chunked(ids, size = 300) { const out = []; for (let i = 0; i < ids.length; i += size) out.push(ids.slice(i, i + size)); return out; }
// SQL*Plus ignores a line longer than 4999 characters, so a long list is split over lines.
const NL = String.fromCharCode(10);
const deleteIn = (table, column, ids) => chunked(ids).map((c) => `DELETE FROM ${table} WHERE ${column} IN (${c.join(`,${NL}`)});`).join(NL);
const quoteList = (values) => (values.length ? values.map((v) => `'${String(v).replaceAll("'", "''")}'`).join(`,${NL}`) : 'NULL');
function writeAudit(kind, data) {
  mkdirSync(`${ROOT}/.local`, { recursive: true });
  const file = `${ROOT}/.local/${kind}-${new Date().toISOString().replace(/[:.]/g, '-')}.json`;
  writeFileSync(file, JSON.stringify(data, null, 2));
  log(`Audit file: ${file.replace(`${ROOT}/`, '')}`);
  return file;
}

// ---------------------------------------------------------------------------------------------------------------------------------
// The network (stations, lines, trains)
// ---------------------------------------------------------------------------------------------------------------------------------
const STATIONS = [
  ['CAI', 'Cairo (Ramses)', 'Cairo'], ['GIZ', 'Giza', 'Giza'], ['BNH', 'Banha', 'Banha'], ['TNT', 'Tanta', 'Tanta'], ['DMH', 'Damanhour', 'Damanhour'],
  ['ALX', 'Alexandria (Misr)', 'Alexandria'], ['SDG', 'Alexandria (Sidi Gaber)', 'Alexandria'], ['ZAG', 'Zagazig', 'Zagazig'], ['MNS', 'Mansoura', 'Mansoura'],
  ['ISM', 'Ismailia', 'Ismailia'], ['PSD', 'Port Said', 'Port Said'], ['SUZ', 'Suez', 'Suez'], ['WST', 'Wasta', 'Wasta'], ['BNS', 'Beni Suef', 'Beni Suef'],
  ['MNY', 'Minya', 'Minya'], ['MLW', 'Mallawi', 'Mallawi'], ['ASY', 'Assiut', 'Assiut'], ['SHG', 'Sohag', 'Sohag'], ['GRG', 'Girga', 'Girga'],
  ['QNA', 'Qena', 'Qena'], ['LXR', 'Luxor', 'Luxor'], ['EDF', 'Edfu', 'Edfu'], ['KOM', 'Kom Ombo', 'Kom Ombo'], ['ASW', 'Aswan', 'Aswan'],
];
// CAI, GIZ and ALX existed before the demo data (the first admin tests); the seed only renames CAI and ALX to the one naming style.
const PRE_EXISTING_STATIONS = new Set(['CAI', 'GIZ', 'ALX']);

// A line: ordered station codes, the distance of each leg (km, sets the fare) and its running time in minutes (sets the timetable).
const LINES = {
  ALEX: { name: 'Cairo - Alexandria via Banha, Tanta, Damanhour', stops: ['CAI', 'BNH', 'TNT', 'DMH', 'ALX'], km: [48, 47, 70, 50], minutes: [35, 32, 50, 38], dwell: 3 },
  ASWAN: { name: 'Cairo - Aswan via Giza, Wasta, Minya, Assiut, Sohag, Qena, Luxor',
    stops: ['CAI', 'GIZ', 'WST', 'BNS', 'MNY', 'MLW', 'ASY', 'SHG', 'GRG', 'QNA', 'LXR', 'EDF', 'KOM', 'ASW'],
    km: [12, 78, 43, 100, 45, 75, 105, 50, 75, 60, 100, 60, 45], minutes: [12, 70, 40, 92, 42, 68, 96, 45, 68, 55, 92, 55, 42], dwell: 4 },
  MANSOURA: { name: 'Cairo - Mansoura via Banha, Tanta', stops: ['CAI', 'BNH', 'TNT', 'MNS'], km: [48, 47, 50], minutes: [35, 32, 38], dwell: 3 },
  PORTSAID: { name: 'Cairo - Port Said via Banha, Zagazig, Ismailia', stops: ['CAI', 'BNH', 'ZAG', 'ISM', 'PSD'], km: [48, 52, 80, 78], minutes: [35, 36, 55, 55], dwell: 3 },
  SUEZ: { name: 'Cairo - Suez via Zagazig, Ismailia', stops: ['CAI', 'ZAG', 'ISM', 'SUZ'], km: [95, 80, 90], minutes: [65, 55, 65], dwell: 3 },
  BANHA: { name: 'Cairo - Banha shuttle', stops: ['CAI', 'BNH'], km: [48], minutes: [40], dwell: 0 },
};

// Coaches: [class, how many, rows, seat columns, standing places in the first of them]. 'ABC' is 2+1, 'ABCD' is 2+2.
const FLEET = [
  { no: '901', name: 'Express 901 Alexandria', line: 'ALEX', coaches: [['FIRST', 1, 6, 'ABC', 0], ['SECOND', 2, 12, 'ABCD', 12]] },
  { no: '903', name: 'Express 903 Alexandria', line: 'ALEX', coaches: [['FIRST', 1, 5, 'ABCD', 0], ['SECOND', 3, 10, 'ABC', 10]] },
  { no: '905', name: 'Express 905 Alexandria', line: 'ALEX', coaches: [['FIRST', 1, 6, 'ABC', 0], ['SECOND', 2, 12, 'ABCD', 12]] },
  { no: '909', name: 'Express 909 Alexandria', line: 'ALEX', coaches: [['FIRST', 1, 5, 'ABCD', 0], ['SECOND', 2, 10, 'ABCD', 10]] },
  { no: '911', name: 'Express 911 Alexandria', line: 'ALEX', coaches: [['FIRST', 1, 6, 'ABC', 0], ['SECOND', 2, 12, 'ABCD', 12]] },
  { no: '913', name: 'Express 913 Alexandria', line: 'ALEX', coaches: [['FIRST', 1, 5, 'ABCD', 0], ['SECOND', 3, 10, 'ABC', 10]] },
  { no: '915', name: 'Express 915 Alexandria', line: 'ALEX', coaches: [['FIRST', 1, 6, 'ABC', 0], ['SECOND', 2, 12, 'ABCD', 12]] },
  { no: '1001', name: 'Express 1001 Aswan', line: 'ASWAN', coaches: [['FIRST', 2, 6, 'ABC', 0], ['SECOND', 3, 10, 'ABCD', 15]] },
  { no: '1005', name: 'Night 1005 Aswan', line: 'ASWAN', coaches: [['FIRST', 2, 8, 'ABC', 0], ['SECOND', 4, 10, 'ABCD', 15]] },
  { no: '1007', name: 'Night 1007 Aswan', line: 'ASWAN', coaches: [['FIRST', 2, 8, 'ABC', 0], ['SECOND', 3, 12, 'ABCD', 15]] },
  { no: '755', name: 'Delta 755 Mansoura', line: 'MANSOURA', coaches: [['FIRST', 1, 5, 'ABC', 0], ['SECOND', 2, 10, 'ABCD', 10]] },
  { no: '757', name: 'Delta 757 Mansoura', line: 'MANSOURA', coaches: [['FIRST', 1, 4, 'ABC', 0], ['SECOND', 2, 9, 'ABC', 8]] },
  { no: '631', name: 'Canal 631 Port Said', line: 'PORTSAID', coaches: [['FIRST', 1, 5, 'ABCD', 0], ['SECOND', 2, 11, 'ABCD', 10]] },
  { no: '633', name: 'Canal 633 Port Said', line: 'PORTSAID', coaches: [['FIRST', 1, 5, 'ABC', 0], ['SECOND', 2, 10, 'ABCD', 10]] },
  { no: '411', name: 'Suez 411', line: 'SUEZ', coaches: [['FIRST', 1, 4, 'ABC', 0], ['SECOND', 1, 12, 'ABCD', 12]] },
  { no: 'M001', name: 'Mini 001 Banha (8 seats)', line: 'BANHA', coaches: [['SECOND', 1, 2, 'ABCD', 0]] },
];
const DEMO_TRAIN_NUMBERS = FLEET.map((t) => t.no);
const DEMO_ROUTE_NAMES = Object.values(LINES).map((l) => l.name);
const DEMO_STATION_CODES = STATIONS.map((s) => s[0]).filter((c) => !PRE_EXISTING_STATIONS.has(c));

// Regular departures per day: [train, line, HH:mm]
const DAILY = [
  ['901', '06:00'], ['903', '12:00'], ['905', '18:00'], ['909', '22:30'], ['911', '09:30'], ['913', '14:30'], ['915', '20:30'],
  ['1001', '08:00'], ['1005', '19:30'], ['1007', '22:30'],
  ['755', '07:00'], ['757', '16:00'],
  ['631', '07:30'], ['633', '15:30'],
  ['411', '08:30'], ['411', '17:30'],
  ['M001', '08:00'], ['M001', '12:00'], ['M001', '16:00'],
];
const DAYS_AHEAD = 14;

const fareOf = (km, cls) => {
  const second = Math.max(30, Math.round((km * 0.42) / 5) * 5); // never below the payment provider's minimum charge
  return cls === 'FIRST' ? Math.round((second * 1.7) / 5) * 5 : second;
};

// ---------------------------------------------------------------------------------------------------------------------------------
// Time helpers (the railway's own calendar and clock)
// ---------------------------------------------------------------------------------------------------------------------------------
const partsFormat = new Intl.DateTimeFormat('en-CA', { timeZone: ZONE, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
function localParts(ms) {
  const p = Object.fromEntries(partsFormat.formatToParts(new Date(ms)).map((x) => [x.type, x.value]));
  return { date: `${p.year}-${p.month}-${p.day}`, hour: Number(p.hour), minute: Number(p.minute) };
}
const addDays = (day, n) => new Date(Date.parse(`${day}T00:00:00Z`) + n * 86_400_000).toISOString().slice(0, 10);
const hhmm = (minutes) => `${String(Math.floor(minutes / 60) % 24).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
const toMinutes = (t) => Number(t.slice(0, 2)) * 60 + Number(t.slice(3, 5));

/** Stop times of a trip leaving the origin at `departure` (HH:mm): arrival and departure of every stop. */
function stopTimes(line, departure) {
  const out = [];
  let t = toMinutes(departure);
  line.stops.forEach((_, i) => {
    if (i === 0) { out.push({ arrivalTime: null, departureTime: hhmm(t) }); return; }
    t += line.minutes[i - 1];
    const last = i === line.stops.length - 1;
    out.push({ arrivalTime: hhmm(t), departureTime: last ? null : hhmm(t + line.dwell) });
    t += line.dwell;
  });
  return out;
}

// ---------------------------------------------------------------------------------------------------------------------------------
// seed-demo
// ---------------------------------------------------------------------------------------------------------------------------------
async function ensureStations() {
  const existing = new Map((await must('list stations', call('GET', '/admin/stations'))).map((s) => [s.code, s]));
  const byCode = new Map();
  for (const [code, name, city] of STATIONS) {
    const found = existing.get(code);
    if (!found) {
      const made = await must(`create station ${code}`, call('POST', '/admin/stations', { code, name, city }));
      byCode.set(code, made);
      log(`  + station ${code} ${name}`);
    } else {
      if (found.name !== name || found.city !== city) {
        await must(`rename station ${code}`, call('PUT', `/admin/stations/${found.id}`, { code, name, city }));
        log(`  ~ station ${code}: "${found.name}" -> "${name}"`);
      }
      byCode.set(code, found);
    }
  }
  return byCode;
}

async function ensureTrains() {
  const existing = new Map((await must('list trains', call('GET', '/admin/trains'))).map((t) => [t.number, t]));
  const byNo = new Map();
  for (const spec of FLEET) {
    let train = existing.get(spec.no);
    if (!train) {
      train = await must(`create train ${spec.no}`, call('POST', '/admin/trains', { number: spec.no, name: spec.name, type: 'Express', status: 'ACTIVE' }));
      log(`  + train ${spec.no} ${spec.name}`);
    }
    const coaches = await must('list coaches', call('GET', `/admin/trains/${train.id}/coaches`));
    if (coaches.length === 0) {
      let position = 1;
      const counters = { FIRST: 0, SECOND: 0 };
      for (const [cls, count, rows, cols, standing] of spec.coaches) {
        for (let c = 0; c < count; c += 1) {
          counters[cls] += 1;
          const code = `${cls === 'FIRST' ? 'F' : 'S'}${counters[cls]}`;
          const coach = await must(`create coach ${spec.no}/${code}`, call('POST', `/admin/trains/${train.id}/coaches`,
            { code, class: cls, position, standingCapacity: c === 0 ? standing : 0 }));
          position += 1;
          const seats = [];
          for (let r = 1; r <= rows; r += 1) for (const col of cols) seats.push({ seatNumber: `${r}${col}`, row: r, column: col });
          await must(`create seats ${spec.no}/${code}`, call('POST', `/admin/coaches/${coach.id}/seats`, seats));
        }
      }
      log(`    coaches and seats for ${spec.no}`);
    }
    byNo.set(spec.no, train);
  }
  return byNo;
}

async function ensureRoutes(stations) {
  const existing = new Map((await must('list routes', call('GET', '/admin/routes'))).map((r) => [r.name, r]));
  const byLine = new Map();
  for (const [key, line] of Object.entries(LINES)) {
    let route = existing.get(line.name);
    if (!route) {
      route = await must(`create route ${key}`, call('POST', '/admin/routes', { name: line.name, stops: line.stops.map((c) => stations.get(c).id) }));
      log(`  + route ${line.name}`);
    }
    const wanted = [];
    line.km.forEach((km, segmentIndex) => { for (const cls of ['FIRST', 'SECOND']) wanted.push({ segmentIndex, class: cls, amount: fareOf(km, cls) }); });
    const have = await must('list fares', call('GET', `/admin/routes/${route.id}/fares`));
    if ((have.length ?? 0) < wanted.length) {
      await must(`fares ${key}`, call('PUT', `/admin/routes/${route.id}/fares`, wanted));
      log(`    fares for ${key} (${line.km.length} segments, First and Second)`);
    }
    byLine.set(key, route);
  }
  return byLine;
}

function fillFraction(spec, trainNo, dayIndex, time, tripId) {
  const r = rng(tripId);
  const key = `${trainNo}|${dayIndex}|${time}`;
  const fixed = { '757|1|16:00': 'FULL', '755|1|07:00': 'NEARLY', '1001|2|08:00': 0.45, '631|1|07:30': 0.25, '411|1|08:30': 0.6, '1005|1|19:30': 0.55, '1007|2|22:30': 0.35 };
  if (key in fixed) return fixed[key];
  if (trainNo === 'M001') return 0; // the small demo train stays empty, to be filled by hand
  if (dayIndex <= 0) return 0.55 + r() * 0.35; // trips that have left or are about to
  if (dayIndex <= 3) return 0.05 + r() * 0.35;
  return 0;
}

function rng(seed) { // small deterministic generator, so a re-run picks the same seats
  let a = (seed * 2654435761) >>> 0;
  return () => { a = (a + 0x6d2b79f5) >>> 0; let t = a; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
}

/** Confirmed demo bookings on one trip, written with the same keys and checks as the service (never a seat twice, never above capacity). */
function fillTrip(tripId, fraction) {
  const already = num('booking_seat', `select count(*) from booking where trip_id = ${tripId} and user_id = '${DEMO_PASSENGER}'`);
  if (already > 0 || fraction === 0) return 0;
  const stopCount = num('booking_seat', `select count(*) from trip_stop where trip_id = ${tripId}`);
  const last = stopCount - 1; // the journey is stop 0 to the last stop: segments 0..last-1
  if (last < 1) return 0;
  const taken = new Set(rows('booking_seat', `select distinct seat_id from seat_reservation where trip_id = ${tripId}`).map(Number));
  const seats = rows('booking_seat', `select seat_id||'|'||class from trip_seat where trip_id = ${tripId} order by seat_id`).map((l) => l.split('|')).filter(([id]) => !taken.has(Number(id)));
  const price = Object.fromEntries(rows('booking_seat', `select class||'|'||sum(amount) from trip_fare where trip_id = ${tripId} group by class`).map((l) => l.split('|')).map(([c, a]) => [c, Number(a)]));
  const standing = rows('booking_seat', `select u.coach_id||'|'||c.class||'|'||min(u.capacity - u.used) from standing_usage u join trip_coach c on c.trip_id = u.trip_id and c.coach_id = u.coach_id `
    + `where u.trip_id = ${tripId} group by u.coach_id, c.class order by u.coach_id`).map((l) => l.split('|')).map(([c, cls, free]) => ({ coachId: Number(c), cls, free: Number(free) }));
  const r = rng(tripId);

  const order = seats.map((s) => [r(), s]).sort((a, b) => a[0] - b[0]).map((x) => x[1]); // seeded shuffle
  const wantSeats = fraction === 'FULL' ? order.length : fraction === 'NEARLY' ? Math.max(order.length - 3, 0) : Math.round(order.length * fraction);
  const chosen = order.slice(0, wantSeats);
  const stand = standing.map((s) => ({ ...s, qty: fraction === 'FULL' || fraction === 'NEARLY' ? s.free : Math.round(s.free * fraction) })).filter((s) => s.qty > 0);

  // Groups of 1 to 4 seats (one booking each), then the standing places of each coach as one booking.
  const bookings = [];
  for (let i = 0; i < chosen.length;) {
    const size = Math.min(1 + Math.floor(r() * 4), chosen.length - i);
    bookings.push({ seats: chosen.slice(i, i + size) });
    i += size;
  }
  for (const s of stand) bookings.push({ standing: s });

  const blocks = [];
  let n = 0;
  for (let b = 0; b < bookings.length; b += 20) {
    let body = '';
    for (const booking of bookings.slice(b, b + 20)) {
      n += 1;
      const items = booking.seats
        ? booking.seats.map(([id, cls]) => ({ type: 'SEAT', seatId: Number(id), cls, price: price[cls] ?? 0 }))
        : [{ type: 'STANDING', coachId: booking.standing.coachId, cls: booking.standing.cls, qty: booking.standing.qty, price: (price[booking.standing.cls] ?? 0) * booking.standing.qty }];
      const amount = items.reduce((sum, i) => sum + i.price, 0);
      const ago = 1 + Math.floor(r() * 70); // booked some hours before now
      body += `  INSERT INTO booking (reference, user_id, user_email, user_name, trip_id, from_stop, to_stop, status, refund_status, amount, currency, expires_at, created_at, confirmed_at)\n`
        + `  VALUES ('${REF_PREFIX}${tripId}-${n}', '${DEMO_PASSENGER}', '${DEMO_EMAIL}', 'Demo Passenger', ${tripId}, 0, ${last}, 'CONFIRMED', 'NONE', ${amount}, 'EGP', `
        + `SYSTIMESTAMP - NUMTODSINTERVAL(${ago}, 'HOUR'), SYSTIMESTAMP - NUMTODSINTERVAL(${ago}, 'HOUR'), SYSTIMESTAMP - NUMTODSINTERVAL(${ago}, 'HOUR')) RETURNING id INTO v;\n`;
      for (const item of items) {
        if (item.type === 'SEAT') {
          body += `  INSERT INTO booking_item (booking_id, item_type, seat_id, qty, price) VALUES (v, 'SEAT', ${item.seatId}, 1, ${item.price});\n`
            + `  FOR s IN 0..${last - 1} LOOP INSERT INTO seat_reservation (trip_id, seat_id, segment_index, booking_id, expires_at) VALUES (${tripId}, ${item.seatId}, s, v, NULL); END LOOP;\n`;
        } else {
          body += `  INSERT INTO booking_item (booking_id, item_type, coach_id, qty, price) VALUES (v, 'STANDING', ${item.coachId}, ${item.qty}, ${item.price});\n`
            + `  INSERT INTO standing_hold (booking_id, trip_id, coach_id, from_segment, to_segment, qty) VALUES (v, ${tripId}, ${item.coachId}, 0, ${last - 1}, ${item.qty});\n`
            + `  UPDATE standing_usage SET used = used + ${item.qty} WHERE trip_id = ${tripId} AND coach_id = ${item.coachId} AND segment_index BETWEEN 0 AND ${last - 1};\n`;
        }
      }
    }
    blocks.push(`DECLARE v NUMBER;\nBEGIN\n${body}END;\n/`);
  }
  sql('booking_seat', `${blocks.join('\n')}\ncommit;`);
  return bookings.length;
}

async function waitForTripCopy(tripId) { // Booking has its copy as soon as the create returned (the snapshot is pushed in the same call)
  for (let i = 0; i < 20; i += 1) { if (num('booking_seat', `select count(*) from trip where id = ${tripId}`) > 0) return true; await sleep(250); }
  return false;
}

async function seedDemo() {
  requireDevStack();
  log('Stations');
  const stations = await ensureStations();
  log('Trains, coaches and seats');
  const trains = await ensureTrains();
  log('Routes and fares');
  const routes = await ensureRoutes(stations);

  log('Trips');
  const existing = await must('list trips', call('GET', '/admin/trips'));
  const have = new Set(existing.map((t) => `${t.trainNumber}|${t.departureDate}|${(t.stops[0].departureTime ?? '').slice(11, 16)}`));
  const trainDays = new Set(existing.map((t) => `${t.trainNumber}|${t.departureDate}`));
  const now = Date.now();
  const today = localParts(now).date;
  const created = []; // { id, train, date, time, dayIndex }
  let skipped = 0;
  let pastRefused = 0;

  async function createTrip(trainNo, lineKey, date, time) {
    const line = LINES[lineKey];
    const body = { trainId: trains.get(trainNo).id, routeId: routes.get(lineKey).id, departureDate: date, stops: stopTimes(line, time) };
    const res = await call('POST', '/admin/trips', body);
    if (res.status === 201) { created.push({ id: res.body.id, train: trainNo, date, time }); return res.body; }
    if (res.status === 400 || res.status === 409 || res.status === 422) { pastRefused += 1; log(`  ! ${trainNo} ${date} ${time}: ${res.status} ${JSON.stringify(res.body)?.slice(0, 160)}`); return null; }
    throw new Error(`create trip ${trainNo} ${date} ${time}: HTTP ${res.status} ${JSON.stringify(res.body)?.slice(0, 300)}`);
  }

  // The showcase trips of today, timed from the current clock: one still running and two that have arrived (so the board shows every state).
  const nowMin = localParts(now).hour * 60 + localParts(now).minute;
  const floor5 = (m) => Math.floor(m / 5) * 5;
  const showcase = [['911', 50], ['913', 270], ['915', 360]]; // [train, minutes ago it left]: one still running, two arrived. These trains' regular slot of today is replaced by these.
  for (const [trainNo, ago] of showcase) {
    const departMs = now - ago * 60_000;
    const p = localParts(departMs);
    const date = p.date;
    const time = hhmm(floor5(p.hour * 60 + p.minute));
    if (trainDays.has(`${trainNo}|${date}`)) { skipped += 1; continue; }
    const trip = await createTrip(trainNo, 'ALEX', date, time);
    if (trip) trainDays.add(`${trainNo}|${date}`);
  }
  void nowMin;

  for (let d = 0; d <= DAYS_AHEAD; d += 1) {
    const date = addDays(today, d);
    for (const [trainNo, time] of DAILY) {
      const lineKey = FLEET.find((f) => f.no === trainNo).line;
      if (have.has(`${trainNo}|${date}|${time}`)) { skipped += 1; continue; }
      if (d === 0 && ['911', '913', '915'].includes(trainNo)) continue; // today's run of these trains is the showcase above
      await createTrip(trainNo, lineKey, date, time);
    }
    if (d % 5 === 0) log(`  ... ${date}: ${created.length} trips created so far`);
  }
  log(`  ${created.length} trips created, ${skipped} already there${pastRefused ? `, ${pastRefused} refused by the service (see above)` : ''}`);

  // The cancelled showcase trip: the real whole-trip cancellation (it has no bookings, so it finishes at once).
  const cancelDate = addDays(today, 2);
  const all = await must('list trips', call('GET', '/admin/trips'));
  const toCancel = all.find((t) => t.trainNumber === '633' && t.departureDate === cancelDate && (t.stops[0].departureTime ?? '').slice(11, 16) === '15:30');
  if (toCancel && toCancel.status !== 'CANCELLED') {
    await must('cancel the showcase trip', call('POST', `/admin/trips/${toCancel.id}/cancel`, { reason: 'Demo: line closed for track works' }));
    for (let i = 0; i < 20; i += 1) {
      const r = await call('GET', `/admin/trips/${toCancel.id}/cancellation`);
      if (r.body?.status === 'CANCELLED') break;
      await sleep(1000);
    }
    log(`  trip ${toCancel.id} (Canal 633, ${cancelDate}) cancelled`);
  }

  log('Demo bookings (confirmed, marked DEMO-...)');
  const trips = await must('list trips', call('GET', '/admin/trips'));
  let filledTrips = 0;
  let bookingCount = 0;
  for (const t of trips) {
    if (!DEMO_TRAIN_NUMBERS.includes(t.trainNumber) || t.status === 'CANCELLED') continue;
    const time = (t.stops[0].departureTime ?? '').slice(11, 16);
    const dayIndex = Math.round((Date.parse(`${t.departureDate}T00:00:00Z`) - Date.parse(`${today}T00:00:00Z`)) / 86_400_000);
    const fraction = fillFraction(null, t.trainNumber, dayIndex, time, t.id);
    if (fraction === 0) continue;
    if (!(await waitForTripCopy(t.id))) continue;
    const made = fillTrip(t.id, fraction);
    if (made > 0) { filledTrips += 1; bookingCount += made; }
  }
  log(`  ${bookingCount} demo bookings on ${filledTrips} trips`);
  log('Done. Verify with: node scripts/demo-data.mjs verify');
}

// ---------------------------------------------------------------------------------------------------------------------------------
// reset-test-data: the rows that test, check and stress runs left behind
// ---------------------------------------------------------------------------------------------------------------------------------
function collectTestData() {
  const demoNumbers = quoteList(DEMO_TRAIN_NUMBERS);
  const trains = rows('train_schedule', `select id from train where regexp_like(train_number, '^SM[0-9A-F]{6}$')`).map(Number);
  const routes = rows('train_schedule', `select id from route where regexp_like(name, '^Smoke [0-9A-F]{6}$')`).map(Number);
  const stations = rows('train_schedule', `select id from station where regexp_like(code, '^S[0-9A-F]{6}[ABC]$')`).map(Number);
  // Trips of test trains and test routes, and every cancelled trip that is not one of the demo trains' (the cancellation scripts made them).
  const trips = rows('train_schedule', `select id from trip where train_id in (${inList(trains)}) or route_id in (${inList(routes)}) `
    + `or (status = 'CANCELLED' and train_id not in (select id from train where train_number in (${demoNumbers})))`).map(Number);

  // Bookings on those trips, and the terminal bookings of the test accounts anywhere (their live ones are left alone).
  let bookingRows = rows('booking_seat', `select id||'|'||trip_id||'|'||reference||'|'||user_email||'|'||status||'|'||user_id from booking `
    + `where trip_id in (${inList(trips)}) or (user_email in (${quoteList(TEST_USERS)}) and status in ('ABANDONED', 'EXPIRED', 'CANCELLED'))`).map((l) => l.split('|'));
  // Safety: a trip that carries a booking of somebody else (not a test account, not the demo passenger) is not touched.
  const foreign = bookingRows.filter(([, tripId, , email, , userId]) => trips.includes(Number(tripId)) && !TEST_USERS.includes(email) && userId !== DEMO_PASSENGER);
  const protectedTrips = new Set(foreign.map((b) => Number(b[1])));
  const safeTrips = trips.filter((t) => !protectedTrips.has(t));
  bookingRows = bookingRows.filter(([, tripId, , email, , userId]) => !protectedTrips.has(Number(tripId)) && (TEST_USERS.includes(email) || userId === DEMO_PASSENGER));
  const bookingIds = bookingRows.map((b) => Number(b[0]));
  const references = bookingRows.map((b) => b[2]);
  return { trains, routes, stations, trips: safeTrips, protectedTrips: [...protectedTrips], bookingIds, references, bookingRows };
}

function countsFor(d) {
  const c = {};
  c['train_schedule.station'] = d.stations.length;
  c['train_schedule.train'] = d.trains.length;
  c['train_schedule.route'] = d.routes.length;
  c['train_schedule.trip'] = d.trips.length;
  const b = d.bookingIds;
  c['booking_seat.booking'] = b.length;
  c['booking_seat.booking_item'] = b.length ? num('booking_seat', `select count(*) from booking_item where booking_id in (${inList(b.slice(0, 900))})`) : 0;
  c['booking_seat.seat_reservation'] = b.length ? num('booking_seat', `select count(*) from seat_reservation where booking_id in (${inList(b.slice(0, 900))})`) : 0;
  c['booking_seat.outbox'] = b.length ? num('booking_seat', `select count(*) from outbox where booking_id in (${inList(b.slice(0, 900))})`) : 0;
  c['booking_seat.trip (copies)'] = d.trips.length ? num('booking_seat', `select count(*) from trip where id in (${inList(d.trips)})`) : 0;
  c['payment.payment'] = b.length ? num('payment', `select count(*) from payment where booking_id in (${inList(b.slice(0, 900))})`) : 0;
  c['payment.refund'] = b.length ? num('payment', `select count(*) from refund where booking_id in (${inList(b.slice(0, 900))})`) : 0;
  c['payment.outbox'] = b.length ? num('payment', `select count(*) from outbox where booking_id in (${inList(b.slice(0, 900))})`) : 0;
  c['ticket.ticket'] = b.length ? num('ticket', `select count(*) from ticket where booking_id in (${inList(b.slice(0, 900))})`) : 0;
  c['notification.email'] = d.references.length ? num('notification', `select count(*) from email where booking_ref in (${quoteList(d.references.slice(0, 900))})`) : 0;
  return c;
}

/** Deletes in dependency order, one transaction per schema: children before parents, and no constraint is ever disabled. */
function deleteBookings(bookingIds, references) {
  if (bookingIds.length === 0) return;
  // Payment, Ticket and Notification first: they only hold the booking id as a plain value. If a later step fails, the bookings are still
  // there and a second run finishes the job.
  sql('payment', [deleteIn('refund', 'booking_id', bookingIds), deleteIn('payment', 'booking_id', bookingIds), deleteIn('outbox', 'booking_id', bookingIds), 'commit;'].join(NL));
  sql('ticket', [deleteIn('ticket', 'booking_id', bookingIds), 'commit;'].join(NL));
  sql('notification', [...chunked(references).map((c) => `DELETE FROM email WHERE booking_ref IN (${quoteList(c)});`), 'commit;'].join(NL));
  // Booking & Seat in ONE transaction: the standing places that live bookings hold are given back to the counters, then the rows go, children
  // before parents. Either all of it happens or none (a failure rolls back, so the counters never drift from the bookings).
  const restore = chunked(bookingIds).map((c) => `UPDATE standing_usage u SET used = used - NVL((SELECT SUM(h.qty) FROM standing_hold h WHERE h.booking_id IN (${c.join(`,${NL}`)}) `
    + 'AND h.trip_id = u.trip_id AND h.coach_id = u.coach_id AND u.segment_index BETWEEN h.from_segment AND h.to_segment '
    + "AND EXISTS (SELECT 1 FROM booking b WHERE b.id = h.booking_id AND b.status IN ('CONFIRMED', 'PENDING_PAYMENT'))), 0);");
  sql('booking_seat', [...restore, deleteIn('seat_reservation', 'booking_id', bookingIds), deleteIn('standing_hold', 'booking_id', bookingIds),
    deleteIn('booking_item', 'booking_id', bookingIds), deleteIn('outbox', 'booking_id', bookingIds), deleteIn('booking', 'id', bookingIds), 'commit;'].join(NL));
}

function resetTestData({ dryRun }) {
  requireDevStack();
  const d = collectTestData();
  const counts = countsFor(d);
  const audit = { when: new Date().toISOString(), command: 'reset-test-data', dryRun, counts, ids: {
    trains: d.trains, routes: d.routes, stations: d.stations, trips: d.trips, protectedTripsKept: d.protectedTrips,
    bookings: d.bookingRows.map(([id, tripId, reference, email, status]) => ({ id: Number(id), tripId: Number(tripId), reference, email, status })),
  }, notTouched: ['Keycloak (users, realm, clients)', 'audit_log tables', 'payment.webhook_event', 'the bookings of every other user'] };
  writeAudit(dryRun ? 'cleanup-dryrun' : 'cleanup', audit);
  for (const [k, v] of Object.entries(counts)) log(`  ${k}: ${v}`);
  if (d.protectedTrips.length) log(`  kept ${d.protectedTrips.length} trip(s) that carry a booking of another user: ${d.protectedTrips.join(', ')}`);
  if (dryRun) { log('Dry run: nothing deleted.'); return; }
  if (d.protectedTrips.length) throw new Error('Stopping: a test trip carries a booking of another user (listed above), so the trains, routes and stations it uses cannot go. Nothing was deleted.');

  deleteBookings(d.bookingIds, d.references);
  if (d.trips.length) sql('booking_seat', `${deleteIn('trip', 'id', d.trips)}\ncommit;`); // trip_stop, coaches, seats, fares, counters and version go with it (ON DELETE CASCADE)
  sql('train_schedule', `${deleteIn('trip', 'id', d.trips)}\n${deleteIn('train', 'id', d.trains)}\n${deleteIn('route', 'id', d.routes)}\n${deleteIn('station', 'id', d.stations)}\ncommit;`);
  log('Test data removed.');
}

// ---------------------------------------------------------------------------------------------------------------------------------
// reset-demo: exactly what seed-demo made
// ---------------------------------------------------------------------------------------------------------------------------------
async function resetDemo({ dryRun }) {
  requireDevStack();
  const bookingRows = rows('booking_seat', `select id||'|'||trip_id||'|'||reference from booking where user_id = '${DEMO_PASSENGER}' and reference like '${REF_PREFIX}%'`).map((l) => l.split('|'));
  const bookingIds = bookingRows.map((b) => Number(b[0]));
  const references = bookingRows.map((b) => b[2]);
  const trips = (await must('list trips', call('GET', '/admin/trips'))).filter((t) => DEMO_TRAIN_NUMBERS.includes(t.trainNumber));
  const audit = { when: new Date().toISOString(), command: 'reset-demo', dryRun, counts: { demoBookings: bookingIds.length, demoTrips: trips.length },
    ids: { bookings: bookingRows.map(([id, tripId, reference]) => ({ id: Number(id), tripId: Number(tripId), reference })), trips: trips.map((t) => t.id) } };
  writeAudit(dryRun ? 'reset-demo-dryrun' : 'reset-demo', audit);
  log(`  demo bookings: ${bookingIds.length}, demo trips: ${trips.length}`);
  if (dryRun) { log('Dry run: nothing deleted.'); return; }

  deleteBookings(bookingIds, references);
  // The timetable goes through the API, so Booking & Seat and Train & Schedule stay in step. A trip that still has a real booking is refused and stays.
  let removed = 0;
  const kept = [];
  for (const t of trips) {
    const r = await call('DELETE', `/admin/trips/${t.id}`);
    if (r.status === 204 || r.status === 404) removed += 1; else kept.push(`trip ${t.id}: ${r.status} ${r.body?.error?.code ?? ''}`);
  }
  const trains = new Map((await must('list trains', call('GET', '/admin/trains'))).map((t) => [t.number, t]));
  for (const no of DEMO_TRAIN_NUMBERS) { const t = trains.get(no); if (t) { const r = await call('DELETE', `/admin/trains/${t.id}`); if (![204, 404].includes(r.status)) kept.push(`train ${no}: ${r.status} ${r.body?.error?.code ?? ''}`); } }
  const routes = new Map((await must('list routes', call('GET', '/admin/routes'))).map((r) => [r.name, r]));
  for (const name of DEMO_ROUTE_NAMES) { const r0 = routes.get(name); if (r0) { const r = await call('DELETE', `/admin/routes/${r0.id}`); if (![204, 404].includes(r.status)) kept.push(`route ${name}: ${r.status} ${r.body?.error?.code ?? ''}`); } }
  const stations = new Map((await must('list stations', call('GET', '/admin/stations'))).map((s) => [s.code, s]));
  for (const code of DEMO_STATION_CODES) { const s0 = stations.get(code); if (s0) { const r = await call('DELETE', `/admin/stations/${s0.id}`); if (![204, 404].includes(r.status)) kept.push(`station ${code}: ${r.status} ${r.body?.error?.code ?? ''}`); } }
  log(`Demo data removed (${removed} trips deleted).${kept.length ? ` Kept because something else still uses it: ${kept.join('; ')}` : ''}`);
}

// ---------------------------------------------------------------------------------------------------------------------------------
// verify (API only)
// ---------------------------------------------------------------------------------------------------------------------------------
async function verify() {
  let bad = 0;
  const check = (label, ok, detail = '') => { if (!ok) bad += 1; log(`${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? `  (${detail})` : ''}`); };
  const stations = await must('stations', call('GET', '/admin/stations'));
  const codes = stations.map((s) => s.code);
  check('no duplicate station codes or names', new Set(codes).size === codes.length && new Set(stations.map((s) => s.name)).size === stations.length, `${stations.length} stations`);
  check('every demo station exists', STATIONS.every(([c]) => codes.includes(c)));
  check('no test stations left', !codes.some((c) => /^S[0-9A-F]{6}[ABC]$/.test(c)));
  const routes = await must('routes', call('GET', '/admin/routes'));
  for (const line of Object.values(LINES)) {
    const route = routes.find((r) => r.name === line.name);
    check(`line "${line.name}" has its stops in order`, route && route.stops.map((s) => s.stationCode).join() === line.stops.join(), route ? route.stops.length + ' stops' : 'missing');
  }
  const trips = await must('trips', call('GET', '/admin/trips'));
  const keys = trips.map((t) => `${t.trainNumber}|${t.departureDate}|${t.stops[0].departureTime}`);
  check('no duplicate trips', new Set(keys).size === keys.length, `${trips.length} trips`);
  const today = localParts(Date.now()).date;
  const searchPairs = [['CAI', 'ALX'], ['CAI', 'ASW'], ['CAI', 'MNS'], ['CAI', 'PSD'], ['CAI', 'SUZ'], ['CAI', 'BNH']];
  for (const [from, to] of searchPairs) {
    for (const date of [today, addDays(today, 2)]) {
      const r = await call('GET', `/trips?from=${from}&to=${to}&date=${date}`);
      check(`search ${from} to ${to} on ${date} returns trips`, r.status === 200 && (r.body?.trips?.length ?? 0) > 0, `${r.body?.trips?.length ?? 0} trips`);
    }
  }
  const board = await must('board', call('GET', `/admin/board/trips?from=${addDays(today, -1)}&to=${addDays(today, 3)}`));
  const s = board.summary;
  check('the board has trips in every state', s.upcoming > 0 && s.departed > 0 && s.arrived > 0 && s.full > 0 && s.cancelled > 0, JSON.stringify(s));
  return bad;
}

// ---------------------------------------------------------------------------------------------------------------------------------
const [command, ...flags] = process.argv.slice(2);
const dryRun = flags.includes('--dry-run');
try {
  if (command === 'reset-test-data') resetTestData({ dryRun });
  else if (command === 'seed-demo') await seedDemo();
  else if (command === 'reset-demo') await resetDemo({ dryRun });
  else if (command === 'verify') process.exitCode = (await verify()) > 0 ? 1 : 0;
  else {
    console.log('Usage: node scripts/demo-data.mjs <reset-test-data | seed-demo | reset-demo | verify> [--dry-run]');
    process.exitCode = 2;
  }
} catch (err) {
  console.error(String(err?.message ?? err));
  process.exitCode = 1;
}
