import { config } from './config.js';

// Structured logging: one JSON object per line on the console, and, when OPENOBSERVE_URL is set, the same records shipped to
// OpenObserve in batches. Callers pass ids (userId, bookingId, tripId) as fields, never tokens, headers, bodies or query strings.

// In-memory buffer of records waiting to be sent to OpenObserve.
const queue = [];
const MAX_QUEUE = 2000; // while OpenObserve is down the oldest records are dropped, never the requests
const BATCH = 200;
const FLUSH_MS = 2000;
let timer = null;
let flushing = false;

// Basic auth header for OpenObserve, built once from the configured user and password (never logged).
const authorization = config.openobserve.user
  ? `Basic ${Buffer.from(`${config.openobserve.user}:${config.openobserve.password}`).toString('base64')}`
  : null;

/**
 * Writes one JSON log line to the console and, if OpenObserve is configured, queues it for shipping.
 * level: 'info', 'warn' or 'error' (error goes to stderr). msg: a short fixed message.
 * fields: extra ids only (userId, bookingId, tripId, status...); never tokens, headers, bodies or query strings.
 */
export function log(level, msg, fields = {}) {
  const record = { level, service: 'gateway', msg, ...fields };
  (level === 'error' ? console.error : console.log)(JSON.stringify(record));
  if (!config.openobserve.url || !authorization) return;
  queue.push({ _timestamp: Date.now() * 1000, ...record }); // OpenObserve wants microseconds
  if (queue.length > MAX_QUEUE) queue.splice(0, queue.length - MAX_QUEUE);
  if (!timer) {
    timer = setInterval(() => void flush(), FLUSH_MS);
    timer.unref(); // the logger never keeps the process alive
  }
  if (queue.length >= BATCH) void flush();
}

/**
 * Sends one batch of at most 200 queued records to OpenObserve (stream "gateway"). On failure the batch goes back to the front of the
 * queue.
 */
export async function flush() {
  // Only one flush at a time, so batches are not sent twice or out of order.
  if (flushing || queue.length === 0) return;
  flushing = true;
  const batch = queue.splice(0, BATCH);
  try {
    const res = await fetch(`${config.openobserve.url}/gateway/_json`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization },
      body: JSON.stringify(batch),
      signal: AbortSignal.timeout(5000),
    });
    if (!res.ok) throw new Error(`status ${res.status}`);
  } catch (err) {
    // Not logged through log() (that would loop): the records are put back once, in order, and tried again at the next tick.
    queue.unshift(...batch);
    if (queue.length > MAX_QUEUE) queue.splice(MAX_QUEUE);
    console.error(JSON.stringify({ level: 'warn', service: 'gateway', msg: 'Cannot ship logs to OpenObserve', cause: String(err?.cause?.code ?? err?.message ?? err) }));
  } finally {
    flushing = false;
  }
}
