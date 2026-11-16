#!/usr/bin/env node
// Golden-signals monitoring of the stack, as code: e-mail destination and template, the "tbs-Golden signals" dashboard and the alerts, all
// created through OpenObserve's HTTP API and all named "tbs-...", so that teardown removes exactly what setup made.
//
//   node scripts/observability.mjs setup        create or update everything (idempotent), then print the alerts found in OpenObserve
//   node scripts/observability.mjs test-alert   fire a synthetic alert and check through Mailpit that its e-mail arrives (needs the dev profile)
//   node scripts/observability.mjs teardown     delete every tbs- alert, the destination, the template and the dashboard
//
// Needs the stack running (`docker compose --profile dev up -d`). The OpenObserve login (OPENOBSERVE_USER / OPENOBSERVE_PASSWORD) and the
// optional ALERT_EMAIL_TO come from the root .env file or the environment. Real delivery: see .env.example (MAIL_* and ALERT_EMAIL_*).
//
// How it works: an alert is a SQL query that OpenObserve runs every `frequency` minutes over the last `period` minutes. The query returns a
// row ONLY when the condition is breached (HAVING / WHERE), and the alert fires when at least one row comes back. `silence` is the cool-down
// in minutes: while the problem lasts, one e-mail per silence period. A recovery e-mail is sent when the condition clears.
import { randomBytes } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

// ======================================================================================================================================
// SETTINGS: every threshold lives here. Change a value and run `setup` again.
// ======================================================================================================================================
const SETTINGS = {
  // High error rate (per service): share of 5xx server responses in the window, with a minimum number of requests.
  errorRate: { percent: 5, windowMin: 5, minRequests: 20, silenceMin: 30 },
  // High latency: Gateway p95 in milliseconds over the window (and a minimum number of requests so one slow call is not an incident).
  latency: { p95Ms: 1000, windowMin: 5, minRequests: 10, silenceMin: 30 },
  // No traffic (informational): no Gateway request for this long. In a demo it fires whenever nobody uses the app; set enabled: false to mute.
  noTraffic: { enabled: true, windowMin: 15, silenceMin: 240 },
  // Traffic spike: Gateway requests per minute over the last `recentMin` minutes against the average of the hour before it. Fires above
  // `multiple` times that average (only when at least `minRpm` per minute, so 0 -> 1 is not a spike), or above the fixed `ceilingRpm`.
  trafficSpike: { multiple: 3, minRpm: 20, ceilingRpm: 300, recentMin: 5, baselineMin: 60, silenceMin: 60 },
  // Saturation: each alert needs the value above its limit for the WHOLE window (the minimum of the samples is above it).
  dbPool: { percent: 80, windowMin: 5, silenceMin: 30 },
  jvmHeap: { percent: 85, windowMin: 5, silenceMin: 30, oldGenPool: 'G1 Old Gen' }, // old generation after the last GC, % of the maximum heap
  cpu: { percent: 85, windowMin: 5, silenceMin: 30 },
  outbox: { maxAgeSeconds: 120, maxPending: 100, windowMin: 2, silenceMin: 30 }, // oldest waiting job older than this, or more jobs than this
  eventLoop: { p99Ms: 200, windowMin: 5, silenceMin: 30 }, // Gateway event-loop delay (p99); the idle baseline is about 20 ms
  // Service silent: no metrics heartbeat from a service. (Metrics leave every 30 s whatever the load. Logs would false-alarm: an idle
  // ticket, notification or train-schedule service writes none.)
  silent: { windowMin: 5, silenceMin: 30 },
  // Business failures (counted from the services' own log lines).
  paymentFailures: { count: 5, windowMin: 10, silenceMin: 30 }, // failed payment attempts (declined cards...)
  refundFailure: { windowMin: 5, silenceMin: 60 }, // any refund failure
  deadOutboxJob: { windowMin: 5, silenceMin: 60 }, // any outbox job that gave up
  emailFailures: { count: 3, windowMin: 10, silenceMin: 30 }, // e-mails that could not be sent
};
// The Spring services (traces and metrics are tagged with these names) and the Gateway.
const SPRING = ['train-schedule', 'booking-seat', 'payment', 'ticket', 'notification'];
const ALL_SERVICES = ['gateway', ...SPRING];
// ======================================================================================================================================

const PREFIX = 'tbs-';
const ORG = 'default';
const TEMPLATE = `${PREFIX}alert-email`;
const DESTINATION = `${PREFIX}email`;
const DASHBOARD_TITLE = `${PREFIX}Golden signals`;
const root = join(dirname(fileURLToPath(import.meta.url)), '..');

// ---- environment ----------------------------------------------------------------------------------------------------------------------
function loadEnv() {
  const values = {};
  try {
    for (const line of readFileSync(join(root, '.env'), 'utf8').split(/\r?\n/)) {
      const m = /^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/.exec(line);
      if (m) values[m[1]] = m[2].replace(/^(['"])(.*)\1$/, '$2');
    }
  } catch { /* no .env: the environment must provide the values */ }
  return { ...values, ...process.env };
}
const env = loadEnv();
const OPENOBSERVE = (env.OPENOBSERVE_URL_PUBLIC ?? 'http://localhost:5080').replace(/\/$/, '');
const MAILPIT = (env.MAILPIT_URL ?? 'http://localhost:8025').replace(/\/$/, '');
const RECIPIENTS = (env.ALERT_EMAIL_TO || env.OPENOBSERVE_USER || 'tbs-ops@example.invalid').split(',').map((s) => s.trim()).filter(Boolean);
const authorization = `Basic ${Buffer.from(`${env.OPENOBSERVE_USER ?? ''}:${env.OPENOBSERVE_PASSWORD ?? ''}`).toString('base64')}`;

async function api(method, path, body) {
  const res = await fetch(`${OPENOBSERVE}${path}`, {
    method,
    headers: { authorization, 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(30000),
  });
  const text = await res.text();
  let data = text;
  try { data = text ? JSON.parse(text) : null; } catch { /* plain text answer */ }
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${typeof data === 'string' ? data : JSON.stringify(data)}`);
  return data;
}

// ---- alert definitions ----------------------------------------------------------------------------------------------------------------
// `sql` returns rows only when breached. Every query names its result columns `value` (and `service_name` when one alert covers several).
const heapRatio = (s) => `(SELECT service_name, min(value) AS used FROM jvm_memory_used_after_last_gc WHERE jvm_memory_pool_name = '${s.oldGenPool}' GROUP BY service_name) u
  JOIN (SELECT service_name, max(value) AS maxheap FROM jvm_memory_limit WHERE jvm_memory_pool_name = '${s.oldGenPool}' GROUP BY service_name) l
  ON u.service_name = l.service_name`;

function buildAlerts() {
  const S = SETTINGS;
  const alerts = [];
  const add = (a) => alerts.push({ silence: 30, period: 5, frequency: 1, enabled: true, info: false, stream_type: 'logs', stream_name: 'default', ...a });

  // a. error rate per service (server spans of the traces; the Gateway's too)
  for (const svc of ALL_SERVICES) {
    add({
      name: `error-rate-${svc}`, label: 'HIGH ERROR RATE', service: svc, stream_type: 'traces', period: S.errorRate.windowMin, silence: S.errorRate.silenceMin,
      condition: `5xx > ${S.errorRate.percent}% of requests (at least ${S.errorRate.minRequests} requests)`,
      sql: `SELECT round(sum(CASE WHEN http_response_status_code >= '500' THEN 1 ELSE 0 END) * 100.0 / count(*), 1) AS value, count(*) AS requests
            FROM "default" WHERE service_name = '${svc}' AND span_kind = '2'
            HAVING count(*) >= ${S.errorRate.minRequests} AND sum(CASE WHEN http_response_status_code >= '500' THEN 1 ELSE 0 END) * 100.0 / count(*) > ${S.errorRate.percent}`,
      threshold: `${S.errorRate.percent} %`,
    });
  }
  // b. latency
  add({
    name: 'latency-gateway', label: 'HIGH LATENCY', service: 'gateway', stream_name: 'gateway', period: S.latency.windowMin, silence: S.latency.silenceMin,
    condition: `p95 > ${S.latency.p95Ms} ms (at least ${S.latency.minRequests} requests)`,
    sql: `SELECT round(approx_percentile_cont(ms, 0.95), 0) AS value, count(*) AS requests FROM "gateway" WHERE msg = 'request'
          HAVING count(*) >= ${S.latency.minRequests} AND approx_percentile_cont(ms, 0.95) > ${S.latency.p95Ms}`,
    threshold: `${S.latency.p95Ms} ms`,
  });
  // c. no traffic (informational)
  add({
    name: 'no-traffic-gateway', label: 'NO TRAFFIC', service: 'gateway', stream_name: 'gateway', period: S.noTraffic.windowMin, silence: S.noTraffic.silenceMin,
    enabled: S.noTraffic.enabled, info: true, condition: `no Gateway request for ${S.noTraffic.windowMin} minutes`,
    sql: `SELECT count(*) AS value FROM "gateway" WHERE msg = 'request' HAVING count(*) < 1`, threshold: '0 requests',
  });
  // d. traffic spike: requests per minute now against the hour before, or above a fixed ceiling
  {
    const t = S.trafficSpike;
    const nowMicros = '(to_unixtime(now()) - ' + t.recentMin * 60 + ') * 1000000';
    add({
      name: 'traffic-spike-gateway', label: 'TRAFFIC SPIKE', service: 'gateway', stream_name: 'gateway', period: t.recentMin + t.baselineMin, frequency: 5, silence: t.silenceMin,
      condition: `requests/min > ${t.multiple} x the average of the previous ${t.baselineMin} min (at least ${t.minRpm}/min), or > ${t.ceilingRpm}/min`,
      sql: `SELECT round(rpm_now, 1) AS value, round(rpm_before, 1) AS previous_avg FROM (
              SELECT sum(CASE WHEN _timestamp >= ${nowMicros} THEN 1 ELSE 0 END) / ${t.recentMin}.0 AS rpm_now,
                     sum(CASE WHEN _timestamp < ${nowMicros} THEN 1 ELSE 0 END) / ${t.baselineMin}.0 AS rpm_before
              FROM "gateway" WHERE msg = 'request') x
            WHERE rpm_now > ${t.ceilingRpm} OR (rpm_now >= ${t.minRpm} AND rpm_now > ${t.multiple} * rpm_before)`,
      threshold: `${t.multiple}x average or ${t.ceilingRpm}/min`,
    });
  }
  // e. saturation
  add({
    name: 'db-pool', label: 'DB POOL SATURATION', service: 'spring services', stream_type: 'metrics', stream_name: 'db_client_connections_usage', period: S.dbPool.windowMin, silence: S.dbPool.silenceMin,
    condition: `connection pool use > ${S.dbPool.percent}% for ${S.dbPool.windowMin} min`,
    sql: `SELECT u.service_name, round(u.used * 100.0 / m.maxpool, 0) AS value FROM
            (SELECT service_name, min(value) AS used FROM db_client_connections_usage WHERE state = 'used' GROUP BY service_name) u
            JOIN (SELECT service_name, max(value) AS maxpool FROM db_client_connections_max GROUP BY service_name) m ON u.service_name = m.service_name
          WHERE u.used * 100.0 / m.maxpool > ${S.dbPool.percent}`,
    threshold: `${S.dbPool.percent} %`,
  });
  add({
    name: 'jvm-heap', label: 'JVM HEAP SATURATION', service: 'spring services', stream_type: 'metrics', stream_name: 'jvm_memory_used_after_last_gc', period: S.jvmHeap.windowMin, silence: S.jvmHeap.silenceMin,
    condition: `live heap after GC > ${S.jvmHeap.percent}% of the maximum for ${S.jvmHeap.windowMin} min`,
    sql: `SELECT u.service_name, round(u.used * 100.0 / l.maxheap, 0) AS value FROM ${heapRatio(S.jvmHeap)} WHERE u.used * 100.0 / l.maxheap > ${S.jvmHeap.percent}`,
    threshold: `${S.jvmHeap.percent} %`,
  });
  add({
    name: 'cpu', label: 'HIGH CPU', service: 'spring services', stream_type: 'metrics', stream_name: 'jvm_cpu_recent_utilization', period: S.cpu.windowMin, silence: S.cpu.silenceMin,
    condition: `CPU > ${S.cpu.percent}% for ${S.cpu.windowMin} min`,
    sql: `SELECT service_name, round(min(value) * 100, 0) AS value FROM jvm_cpu_recent_utilization GROUP BY service_name HAVING min(value) * 100 > ${S.cpu.percent}`,
    threshold: `${S.cpu.percent} %`,
  });
  add({
    name: 'cpu-gateway', label: 'HIGH CPU', service: 'gateway', stream_type: 'metrics', stream_name: 'process_cpu_utilization', period: S.cpu.windowMin, silence: S.cpu.silenceMin,
    condition: `CPU > ${S.cpu.percent}% of one core for ${S.cpu.windowMin} min`,
    sql: `SELECT service_name, round(min(value) * 100, 0) AS value FROM process_cpu_utilization GROUP BY service_name HAVING min(value) * 100 > ${S.cpu.percent}`,
    threshold: `${S.cpu.percent} %`,
  });
  add({
    name: 'outbox-backlog', label: 'OUTBOX BACKLOG', service: 'booking-seat', stream_type: 'metrics', stream_name: 'tbs_outbox_pending', period: S.outbox.windowMin, silence: S.outbox.silenceMin,
    condition: `oldest waiting job > ${S.outbox.maxAgeSeconds} s, or more than ${S.outbox.maxPending} pending jobs`,
    sql: `SELECT a.oldest_age_s AS value, p.pending FROM (SELECT max(value) AS oldest_age_s FROM tbs_outbox_oldest_pending_age) a
            CROSS JOIN (SELECT max(value) AS pending FROM tbs_outbox_pending) p
          WHERE a.oldest_age_s > ${S.outbox.maxAgeSeconds} OR p.pending > ${S.outbox.maxPending}`,
    threshold: `${S.outbox.maxAgeSeconds} s or ${S.outbox.maxPending} jobs`,
  });
  add({
    name: 'event-loop-gateway', label: 'EVENT LOOP DELAY', service: 'gateway', stream_type: 'metrics', stream_name: 'nodejs_eventloop_delay_p99', period: S.eventLoop.windowMin, silence: S.eventLoop.silenceMin,
    condition: `event-loop delay p99 > ${S.eventLoop.p99Ms} ms for ${S.eventLoop.windowMin} min`,
    sql: `SELECT round(min(value), 0) AS value FROM nodejs_eventloop_delay_p99 HAVING min(value) > ${S.eventLoop.p99Ms}`, threshold: `${S.eventLoop.p99Ms} ms`,
  });
  // f. service silent: no metrics heartbeat (the gateway exports process_cpu_utilization, the Spring services jvm_cpu_recent_utilization)
  for (const svc of ALL_SERVICES) {
    const stream = svc === 'gateway' ? 'process_cpu_utilization' : 'jvm_cpu_recent_utilization';
    add({
      name: `silent-${svc}`, label: 'SERVICE SILENT', service: svc, stream_type: 'metrics', stream_name: stream, period: S.silent.windowMin, silence: S.silent.silenceMin,
      condition: `no telemetry from the service for ${S.silent.windowMin} min`,
      sql: `SELECT count(*) AS value FROM ${stream} WHERE service_name = '${svc}' HAVING count(*) < 1`, threshold: '0 data points',
    });
  }
  // g. business failures
  add({
    name: 'payment-failures', label: 'PAYMENT FAILURES', service: 'payment', period: S.paymentFailures.windowMin, silence: S.paymentFailures.silenceMin,
    condition: `at least ${S.paymentFailures.count} failed payment attempts in ${S.paymentFailures.windowMin} min`,
    sql: `SELECT count(*) AS value FROM "default" WHERE service_name = 'payment' AND body LIKE 'Payment attempt failed%' HAVING count(*) >= ${S.paymentFailures.count}`,
    threshold: `${S.paymentFailures.count} failures`,
  });
  add({
    name: 'refund-failure', label: 'REFUND FAILURE', service: 'payment, booking-seat', period: S.refundFailure.windowMin, silence: S.refundFailure.silenceMin,
    condition: 'any refund that Stripe refused or that could not be requested',
    sql: `SELECT count(*) AS value FROM "default" WHERE severity = 'ERROR' AND (body LIKE 'Stripe refused refund%' OR body LIKE 'Refund % could not be sent to Stripe%'
            OR body LIKE '%flagged REFUND_FAILED%' OR body LIKE 'Refund for the refused payment%') HAVING count(*) >= 1`,
    threshold: '1 failure',
  });
  add({
    name: 'outbox-dead-job', label: 'DEAD OUTBOX JOB', service: 'booking-seat', period: S.deadOutboxJob.windowMin, silence: S.deadOutboxJob.silenceMin,
    condition: 'any outbox job that exhausted its attempts',
    sql: `SELECT count(*) AS value FROM "default" WHERE severity = 'ERROR' AND body LIKE 'Outbox job%giving up%' HAVING count(*) >= 1`, threshold: '1 job',
  });
  add({
    name: 'email-failures', label: 'EMAIL SEND FAILURES', service: 'notification', period: S.emailFailures.windowMin, silence: S.emailFailures.silenceMin,
    condition: `at least ${S.emailFailures.count} failed e-mail send attempts in ${S.emailFailures.windowMin} min`,
    sql: `SELECT count(*) AS value FROM "default" WHERE service_name = 'notification' AND (body LIKE 'Email %not sent, next try%' OR body LIKE 'Email %giving up%')
          HAVING count(*) >= ${S.emailFailures.count}`,
    threshold: `${S.emailFailures.count} failures`,
  });
  return alerts;
}

// ---- OpenObserve objects --------------------------------------------------------------------------------------------------------------
const oneLine = (sql) => sql.replace(/\s+/g, ' ').trim();

function alertBody(a, dashboardUrl) {
  return {
    name: `${PREFIX}${a.name}`,
    org_id: ORG,
    stream_type: a.stream_type,
    stream_name: a.stream_name,
    is_real_time: false,
    enabled: a.enabled,
    description: `${a.info ? '[informational] ' : ''}${a.label}: ${a.condition}`,
    query_condition: { type: 'sql', sql: oneLine(a.sql), conditions: null, aggregation: null, promql: null, promql_condition: null, vrl_function: null, multi_time_range: [] },
    // The alert fires when the query returns at least one row. frequency = how often it runs; period = the window it looks at;
    // silence = cool-down: no second e-mail for the same incident inside this many minutes.
    trigger_condition: { period: a.period, operator: '>=', threshold: 1, frequency: a.frequency, frequency_type: 'minutes', cron: '', timezone: 'UTC', silence: a.silence },
    destinations: [DESTINATION],
    notify_on_recovery: true,
    recovery_destinations: [DESTINATION],
    template: TEMPLATE,
    // One line per returned row in the e-mail: the service when the query groups by it, else just the value.
    row_template: /^SELECT (u\.)?service_name/.test(oneLine(a.sql)) ? '{service_name}: {value}' : '{value}',
    row_template_type: 'String',
    context_attributes: {
      label: a.label, service: a.service, condition: a.condition, threshold: a.threshold, window: `${a.period} min`,
      cooldown: `${a.silence} min`, kind: a.info ? 'informational' : 'alert', dashboard: dashboardUrl,
    },
    tags: ['tbs'],
  };
}

async function ensureTemplate() {
  const body = {
    name: TEMPLATE, type: 'email', isDefault: false, isPrebuilt: false, kind: 'custom',
    title: '[tbs] {label} on {service}',
    body: `<h2>[tbs] {label} on {service}</h2>
<p><b>Status:</b> {alert_status} ({kind})</p>
<table cellpadding="4">
<tr><td>Service</td><td>{service}</td></tr>
<tr><td>Value</td><td>{rows}</td></tr>
<tr><td>Threshold</td><td>{threshold} ({condition})</td></tr>
<tr><td>Window</td><td>{window}</td></tr>
<tr><td>Cool-down</td><td>{cooldown}</td></tr>
<tr><td>Alert</td><td>{alert_name}</td></tr>
</table>
<p><a href="{dashboard}">Open the Golden signals dashboard</a></p>`,
  };
  await upsert(`/api/${ORG}/alerts/templates`, TEMPLATE, body);
}

// OpenObserve only e-mails members of the organisation, and the open-source edition has no role below "admin". A recipient that is not a
// member yet is therefore added as an admin account named "tbs-recipient" with a random password that is never shown or stored (nobody can
// log in with it; the root user can delete it, and `teardown` does). Without ALERT_EMAIL_TO the alerts go to the root user's own address,
// which is already a member, so no account is created.
async function ensureRecipients() {
  const members = (await api('GET', `/api/${ORG}/users`)).data ?? [];
  const known = new Set(members.map((u) => u.email.toLowerCase()));
  for (const email of RECIPIENTS) {
    if (known.has(email.toLowerCase())) continue;
    await api('POST', `/api/${ORG}/users`, { email, password: randomBytes(18).toString('base64url'), role: 'admin', first_name: `${PREFIX}recipient`, last_name: 'alerts' });
  }
}

async function ensureDestination() {
  await ensureRecipients();
  await upsert(`/api/${ORG}/alerts/destinations`, DESTINATION, {
    name: DESTINATION, type: 'email', emails: RECIPIENTS, template: TEMPLATE, skip_tls_verify: false, method: 'post',
    url: '', headers: null, output_format: null, destination_type_name: null,
  });
}

// POST when missing, PUT when it exists (these two endpoints are keyed by name).
async function upsert(collection, name, body) {
  const existing = await api('GET', collection);
  const list = Array.isArray(existing) ? existing : (existing.list ?? []);
  if (list.some((x) => x.name === name)) await api('PUT', `${collection}/${encodeURIComponent(name)}`, body);
  else await api('POST', collection, body);
}

async function ensureDashboard() {
  const dashboard = JSON.parse(readFileSync(join(root, 'infra/openobserve/dashboards/golden-signals.json'), 'utf8'));
  dashboard.title = DASHBOARD_TITLE;
  const listed = await api('GET', `/api/${ORG}/dashboards`);
  // The dashboard is replaced (delete + create) so the stored JSON is always the one in the repository.
  for (const d of listed.dashboards ?? []) {
    const id = d.dashboard_id ?? d.dashboardId;
    if ((d.title ?? '') === DASHBOARD_TITLE) await api('DELETE', `/api/${ORG}/dashboards/${id}`);
  }
  const created = await api('POST', `/api/${ORG}/dashboards?folder=default`, dashboard);
  const id = created.v8?.dashboardId ?? created.v7?.dashboardId ?? created.dashboardId ?? Object.values(created).find((v) => v?.dashboardId)?.dashboardId;
  if (!id) throw new Error(`Dashboard created but no id in the answer: ${JSON.stringify(created).slice(0, 200)}`);
  return `${OPENOBSERVE}/web/dashboards/view?org_identifier=${ORG}&dashboard=${id}&folder=default`;
}

async function listAlerts() {
  const res = await api('GET', `/api/v2/${ORG}/alerts`);
  return (res.list ?? []).filter((a) => a.name.startsWith(PREFIX));
}

async function putAlert(body) {
  const existing = (await listAlerts()).find((a) => a.name === body.name);
  if (existing) return api('PUT', `/api/v2/${ORG}/alerts/${existing.alert_id ?? existing.id}`, body);
  return api('POST', `/api/v2/${ORG}/alerts`, body);
}

// ---- commands -------------------------------------------------------------------------------------------------------------------------
async function setup() {
  await ensureTemplate();
  await ensureDestination();
  const dashboardUrl = await ensureDashboard();
  const alerts = buildAlerts();
  for (const a of alerts) await putAlert(alertBody(a, dashboardUrl));
  const live = await listAlerts();
  console.log(`Dashboard: ${dashboardUrl}`);
  console.log(`Alerts e-mail: ${RECIPIENTS.join(', ')}  (template ${TEMPLATE}, destination ${DESTINATION})\n`);
  console.log('name'.padEnd(34), 'enabled'.padEnd(8), 'window'.padEnd(7), 'every'.padEnd(6), 'cool-down'.padEnd(10), 'condition');
  for (const a of live.sort((x, y) => x.name.localeCompare(y.name))) {
    const t = a.trigger_condition;
    console.log(a.name.padEnd(34), String(a.enabled).padEnd(8), `${t.period}m`.padEnd(7), `${t.frequency}m`.padEnd(6), `${t.silence}m`.padEnd(10), a.description);
  }
  console.log(`\n${live.length} alerts (expected ${alerts.length}).`);
  if (live.length !== alerts.length) throw new Error('The number of alerts in OpenObserve differs from the definitions');
}

async function teardown() {
  for (const a of await listAlerts()) await api('DELETE', `/api/v2/${ORG}/alerts/${a.alert_id ?? a.id}`);
  const dashboards = await api('GET', `/api/${ORG}/dashboards`);
  for (const d of dashboards.dashboards ?? []) {
    if ((d.title ?? '').startsWith(PREFIX)) await api('DELETE', `/api/${ORG}/dashboards/${d.dashboard_id ?? d.dashboardId}`);
  }
  await api('DELETE', `/api/${ORG}/alerts/destinations/${DESTINATION}`).catch(() => {});
  // Only the recipient accounts this script created (first name "tbs-recipient") are removed.
  for (const u of (await api('GET', `/api/${ORG}/users`)).data ?? []) {
    if (u.first_name === `${PREFIX}recipient`) await api('DELETE', `/api/${ORG}/users/${encodeURIComponent(u.email)}`).catch(() => {});
  }
  await api('DELETE', `/api/${ORG}/alerts/templates/${TEMPLATE}`).catch(() => {});
  console.log('Removed every tbs- alert, the dashboard, the destination and the template.');
}

// Synthetic incident: a burst of ERROR lines on a throwaway stream and service name, one alert on that stream that uses the real template
// and destination, and a wait (at most 2 minutes) for its e-mail in Mailpit. Everything synthetic is removed at the end.
async function testAlert() {
  const stream = 'tbs_test';
  const service = 'tbs-test-service';
  const alertName = `${PREFIX}test-alert`;
  const subject = `[tbs] HIGH ERROR RATE on ${service}`;
  await ensureTemplate();
  await ensureDestination();
  const mailIds = [];
  let found = null;
  try {
    const mailpit = await fetch(`${MAILPIT}/api/v1/info`).catch(() => null);
    if (!mailpit?.ok) throw new Error(`Mailpit is not reachable at ${MAILPIT} (start the dev profile, or set MAILPIT_URL)`);
    const now = Date.now() * 1000;
    const lines = Array.from({ length: 10 }, (_, i) => ({ _timestamp: now + i, level: 'error', severity: 'ERROR', service, tag: 'tbs-test', msg: 'synthetic test error' }));
    await api('POST', `/api/${ORG}/${stream}/_json`, lines);
    await putAlert(alertBody({
      name: 'test-alert', label: 'HIGH ERROR RATE', service, stream_name: stream, stream_type: 'logs', period: 5, frequency: 1, silence: 10, enabled: true, info: false,
      condition: 'synthetic test: at least 5 ERROR lines tagged tbs-test',
      sql: `SELECT count(*) AS value FROM "${stream}" WHERE tag = 'tbs-test' AND severity = 'ERROR' HAVING count(*) >= 5`, threshold: '5 lines',
    }, `${OPENOBSERVE}/web/dashboards`));
    console.log(`Synthetic burst sent, waiting up to 2 minutes for "${subject}" in Mailpit...`);
    const deadline = Date.now() + 120_000;
    while (Date.now() < deadline && !found) {
      await new Promise((r) => setTimeout(r, 5000));
      const res = await (await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`subject:"${subject}"`)}`)).json();
      for (const m of res.messages ?? []) mailIds.push(m.ID);
      found = (res.messages ?? []).find((m) => m.Subject === subject) ?? null;
    }
    if (!found) throw new Error(`NO ALERT E-MAIL ARRIVED within 2 minutes (expected subject "${subject}")`);
    console.log(`OK: alert e-mail arrived in Mailpit. Subject: "${found.Subject}"`);
  } finally {
    // Cleanup runs on success and on failure.
    const alert = (await listAlerts().catch(() => [])).find((a) => a.name === alertName);
    if (alert) await api('DELETE', `/api/v2/${ORG}/alerts/${alert.alert_id ?? alert.id}`).catch(() => {});
    await api('DELETE', `/api/${ORG}/streams/${stream}?type=logs`).catch(() => {});
    if (mailIds.length) {
      await fetch(`${MAILPIT}/api/v1/messages`, { method: 'DELETE', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ IDs: [...new Set(mailIds)] }) }).catch(() => {});
    }
  }
}

const commands = { setup, 'test-alert': testAlert, teardown };
const command = commands[process.argv[2]];
if (!command) {
  console.error('Usage: node scripts/observability.mjs <setup|test-alert|teardown>');
  process.exit(2);
}
command().catch((err) => {
  console.error(`FAILED: ${err.message}`);
  process.exit(1);
});
