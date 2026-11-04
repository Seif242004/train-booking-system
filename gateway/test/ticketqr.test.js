import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { after, before, describe, test } from 'node:test';
import express from 'express';
import { createApp } from '../src/app.js';
import { ticketQr } from '../src/ticket.js';

function listen(handler) {
  return new Promise((resolve) => {
    const server = createServer(handler);
    server.listen(0, '127.0.0.1', () => resolve({ server, url: `http://127.0.0.1:${server.address().port}` }));
  });
}
const json = (res, status, body) => { res.writeHead(status, { 'content-type': 'application/json' }).end(JSON.stringify(body)); };
const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3]);

describe('GET /bookings/:id/ticket/qr', () => {
  let booking; let ticket; let gateway;
  let bookingAnswer; let ticketAnswer;
  const calls = [];

  before(async () => {
    booking = await listen((req, res) => { calls.push({ svc: 'booking', url: req.url }); bookingAnswer(req, res); });
    ticket = await listen((req, res) => { calls.push({ svc: 'ticket', url: req.url, key: req.headers['x-internal-api-key'], authorization: req.headers.authorization }); ticketAnswer(req, res); });
    const app = express();
    // Stands in for requireAuth: "Bearer admin" is an admin, "Bearer user" a passenger.
    app.use((req, res, next) => {
      if (!req.headers.authorization) return res.status(401).json({ error: { code: 'unauthenticated' } });
      req.user = { sub: 'u1', roles: req.headers.authorization === 'Bearer admin' ? ['USER', 'ADMIN'] : ['USER'] };
      return next();
    });
    app.get('/bookings/:id/ticket/qr', ticketQr({ bookingSeatUrl: booking.url, ticketUrl: ticket.url, internalApiKey: 'secret-key', upstreamTimeoutMs: 400 }));
    gateway = await listen(app);
  });
  after(() => { for (const s of [booking, ticket, gateway]) s.server.close(); });

  const ask = (path, token = 'user') => fetch(`${gateway.url}${path}`, { headers: token ? { authorization: `Bearer ${token}` } : {} });

  test('without a token: 401 from the real Gateway', async () => {
    const real = createApp().listen(0, '127.0.0.1');
    await new Promise((resolve) => real.once('listening', resolve));
    try {
      const res = await fetch(`http://127.0.0.1:${real.address().port}/bookings/5/ticket/qr`);
      assert.equal(res.status, 401);
    } finally {
      real.close();
    }
  });

  test('the passenger gets the PNG; the Ticket service is asked with the internal key, never the user token', async () => {
    calls.length = 0;
    bookingAnswer = (req, res) => json(res, 200, { id: 5, reference: 'TB-ABC234', status: 'CONFIRMED' });
    ticketAnswer = (req, res) => res.writeHead(200, { 'content-type': 'image/png' }).end(PNG);

    const res = await ask('/bookings/5/ticket/qr');

    assert.equal(res.status, 200);
    assert.equal(res.headers.get('content-type'), 'image/png');
    assert.equal(res.headers.get('cache-control'), 'private, no-store');
    assert.deepEqual(Buffer.from(await res.arrayBuffer()), PNG);
    assert.deepEqual(calls.map((c) => c.url), ['/bookings/5', '/internal/tickets/5/qr']);
    assert.equal(calls[1].key, 'secret-key');
    assert.equal(calls[1].authorization, undefined);
  });

  test('another user\'s booking: 404, and the Ticket service is never asked', async () => {
    calls.length = 0;
    bookingAnswer = (req, res) => json(res, 404, { error: { code: 'booking_not_found', message: 'x' } });
    assert.equal((await ask('/bookings/9/ticket/qr')).status, 404);
    assert.deepEqual(calls.map((c) => c.svc), ['booking']);
  });

  test('a booking that is not confirmed: 409', async () => {
    calls.length = 0;
    for (const status of ['PENDING_PAYMENT', 'CANCELLED']) {
      bookingAnswer = (req, res) => json(res, 200, { id: 5, reference: 'TB-ABC234', status });
      const res = await ask('/bookings/5/ticket/qr');
      assert.equal(res.status, 409);
      assert.equal((await res.json()).error.code, 'ticket_not_available');
    }
    assert.ok(calls.every((c) => c.svc === 'booking'));
  });

  test('an admin may fetch any booking\'s QR code; the ticket not made yet is 409 ticket_not_ready', async () => {
    calls.length = 0;
    bookingAnswer = (req, res) => (req.url === '/bookings/7'
      ? json(res, 404, { error: { code: 'booking_not_found', message: 'x' } })
      : json(res, 200, { booking: { id: 7, reference: 'TB-XYZ789', status: 'CONFIRMED' } }));
    ticketAnswer = (req, res) => res.writeHead(200, { 'content-type': 'image/png' }).end(PNG);
    assert.equal((await ask('/bookings/7/ticket/qr', 'admin')).status, 200);
    assert.deepEqual(calls.map((c) => c.url), ['/bookings/7', '/admin/bookings/7', '/internal/tickets/7/qr']);

    bookingAnswer = (req, res) => json(res, 200, { id: 5, status: 'CONFIRMED' });
    ticketAnswer = (req, res) => json(res, 404, { error: { code: 'ticket_not_found', message: 'x' } });
    const res = await ask('/bookings/5/ticket/qr');
    assert.equal(res.status, 409);
    assert.equal((await res.json()).error.code, 'ticket_not_ready');
  });
});
