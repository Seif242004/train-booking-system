import assert from 'node:assert/strict';
import { once } from 'node:events';
import { test } from 'node:test';
import express from 'express';
import { accessLog } from '../src/accesslog.js';
import { queryStringRedactor } from '../src/telemetry.js';

// Runs one request through the access log and returns the JSON lines the Gateway wrote to the console.
async function lines(path, { user, headers } = {}) {
  const written = [];
  const original = console.log;
  console.log = (line) => written.push(JSON.parse(line));
  const app = express();
  app.use((req, _res, next) => { if (user) req.user = user; next(); });
  app.use(accessLog);
  app.use((_req, res) => res.status(200).json({ ok: true }));
  const server = app.listen(0, '127.0.0.1');
  await once(server, 'listening');
  try {
    await fetch(`http://127.0.0.1:${server.address().port}${path}`, { headers });
    await new Promise((resolve) => setTimeout(resolve, 50)); // the line is written when the response has finished
  } finally {
    console.log = original;
    server.close();
  }
  return written;
}

test('one structured line per request: method, path, status, user, booking and trip ids', async () => {
  const [line] = await lines('/trips/7/seat-map', { user: { sub: 'user-1' } });
  assert.equal(line.msg, 'request');
  assert.equal(line.service, 'gateway');
  assert.equal(line.method, 'GET');
  assert.equal(line.path, '/trips/7/seat-map');
  assert.equal(line.status, 200);
  assert.equal(line.userId, 'user-1');
  assert.equal(line.tripId, '7');
  const [booking] = await lines('/bookings/42/pay', { user: { sub: 'user-1' } });
  assert.equal(booking.bookingId, '42');
});

test('the query string and the Authorization header never reach the log', async () => {
  const written = await lines('/trips?from=CAI&to=ALX&date=2026-11-20&token=secret-in-query', { headers: { authorization: 'Bearer secret-token-value' } });
  const text = JSON.stringify(written);
  assert.equal(written[0].path, '/trips');
  assert.ok(!text.includes('secret-in-query'), 'query string leaked');
  assert.ok(!text.includes('from=CAI'), 'query string leaked');
  assert.ok(!text.includes('secret-token-value'), 'token leaked');
});

test('a request without a token has no user id, and health checks are not logged', async () => {
  const [line] = await lines('/trips');
  assert.equal(line.userId, undefined);
  assert.deepEqual(await lines('/health'), []);
});

test('the span redactor cuts URL attributes at the question mark before export', () => {
  const attributes = { 'url.full': 'http://x/trips?from=CAI', 'http.target': '/trips?date=1', 'url.query': 'from=CAI', 'http.method': 'GET' };
  const span = { attributes, setAttribute: (key, value) => { attributes[key] = value; } };
  queryStringRedactor.onStart(span);
  assert.equal(attributes['url.full'], 'http://x/trips');
  assert.equal(attributes['http.target'], '/trips');
  assert.equal(attributes['url.query'], '');
  assert.equal(attributes['http.method'], 'GET');
});
