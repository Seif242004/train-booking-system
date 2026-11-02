import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { after, before, describe, test } from 'node:test';
import express from 'express';
import { createApp } from '../src/app.js';
import { ticketPdf } from '../src/ticket.js';

function listen(handler) {
  return new Promise((resolve) => {
    const server = createServer(handler);
    server.listen(0, '127.0.0.1', () => resolve({ server, url: `http://127.0.0.1:${server.address().port}` }));
  });
}
const json = (res, status, body) => { res.writeHead(status, { 'content-type': 'application/json' }).end(JSON.stringify(body)); };
const PDF = Buffer.from('%PDF-1.4 test ticket');

describe('GET /bookings/:id/ticket', () => {
  let booking; let ticket; let gateway;
  let bookingAnswer; let ticketAnswer;
  const calls = [];

  before(async () => {
    booking = await listen((req, res) => { calls.push({ svc: 'booking', url: req.url, authorization: req.headers.authorization }); bookingAnswer(req, res); });
    ticket = await listen((req, res) => { calls.push({ svc: 'ticket', url: req.url, key: req.headers['x-internal-api-key'], authorization: req.headers.authorization }); ticketAnswer(req, res); });
    const app = express();
    // Stands in for requireAuth (verifying a token needs Keycloak's keys): "Bearer admin" is an admin, "Bearer user" a passenger.
    app.use((req, res, next) => {
      if (!req.headers.authorization) return res.status(401).json({ error: { code: 'unauthenticated' } });
      req.user = { sub: 'u1', roles: req.headers.authorization === 'Bearer admin' ? ['USER', 'ADMIN'] : ['USER'] };
      return next();
    });
    app.get('/bookings/:id/ticket', ticketPdf({ bookingSeatUrl: booking.url, ticketUrl: ticket.url, internalApiKey: 'secret-key', upstreamTimeoutMs: 400 }));
    gateway = await listen(app);
  });
  after(() => { for (const s of [booking, ticket, gateway]) s.server.close(); });

  const ask = (path, token = 'user') => fetch(`${gateway.url}${path}`, { headers: token ? { authorization: `Bearer ${token}` } : {} });

  test('without a token: 401 from the real Gateway, nothing is forwarded', async () => {
    const real = createApp().listen(0, '127.0.0.1');
    await new Promise((resolve) => real.once('listening', resolve));
    try {
      const res = await fetch(`http://127.0.0.1:${real.address().port}/bookings/5/ticket`);
      assert.equal(res.status, 401);
      assert.equal((await res.json()).error.code, 'unauthenticated');
    } finally {
      real.close();
    }
  });

  test('the passenger gets the PDF; Booking is asked with their token, Ticket with the internal key', async () => {
    calls.length = 0;
    bookingAnswer = (req, res) => json(res, 200, { id: 5, reference: 'TB-ABC234', status: 'CONFIRMED' });
    ticketAnswer = (req, res) => res.writeHead(200, { 'content-type': 'application/pdf' }).end(PDF);

    const res = await ask('/bookings/5/ticket');

    assert.equal(res.status, 200);
    assert.equal(res.headers.get('content-type'), 'application/pdf');
    assert.match(res.headers.get('content-disposition'), /attachment; filename="ticket-TB-ABC234\.pdf"/);
    assert.equal(res.headers.get('cache-control'), 'private, no-store');
    assert.deepEqual(Buffer.from(await res.arrayBuffer()), PDF);
    assert.deepEqual(calls.map((c) => [c.svc, c.url]), [['booking', '/bookings/5'], ['ticket', '/internal/tickets/5/pdf']]);
    assert.equal(calls[0].authorization, 'Bearer user');
    assert.equal(calls[1].key, 'secret-key');
    assert.equal(calls[1].authorization, undefined); // the user's token never goes to the Ticket service
  });

  test('another user\'s booking: 404 and the Ticket service is never asked', async () => {
    calls.length = 0;
    bookingAnswer = (req, res) => json(res, 404, { error: { code: 'booking_not_found', message: 'x' } });

    const res = await ask('/bookings/9/ticket');

    assert.equal(res.status, 404);
    assert.deepEqual(calls.map((c) => c.svc), ['booking']);
  });

  test('a booking that is not confirmed: 409, and no PDF is fetched', async () => {
    calls.length = 0;
    for (const status of ['PENDING_PAYMENT', 'CANCELLED', 'EXPIRED']) {
      bookingAnswer = (req, res) => json(res, 200, { id: 5, reference: 'TB-ABC234', status });
      const res = await ask('/bookings/5/ticket');
      assert.equal(res.status, 409);
      assert.equal((await res.json()).error.code, 'ticket_not_available');
    }
    assert.ok(calls.every((c) => c.svc === 'booking'));
  });

  test('an admin gets the ticket of any booking through the admin view', async () => {
    calls.length = 0;
    bookingAnswer = (req, res) => (req.url === '/bookings/7'
      ? json(res, 404, { error: { code: 'booking_not_found', message: 'x' } })
      : json(res, 200, { booking: { id: 7, reference: 'TB-XYZ789', status: 'CONFIRMED' }, userEmail: 'a@b.c' }));
    ticketAnswer = (req, res) => res.writeHead(200, { 'content-type': 'application/pdf' }).end(PDF);

    const res = await ask('/bookings/7/ticket', 'admin');

    assert.equal(res.status, 200);
    assert.deepEqual(calls.map((c) => c.url), ['/bookings/7', '/admin/bookings/7', '/internal/tickets/7/pdf']);
  });

  test('a passenger never reaches the admin view', async () => {
    calls.length = 0;
    bookingAnswer = (req, res) => json(res, 404, { error: { code: 'booking_not_found', message: 'x' } });

    assert.equal((await ask('/bookings/7/ticket', 'user')).status, 404);
    assert.deepEqual(calls.map((c) => c.url), ['/bookings/7']);
  });

  test('confirmed but the PDF is not made yet: 409 ticket_not_ready', async () => {
    bookingAnswer = (req, res) => json(res, 200, { id: 5, reference: 'TB-ABC234', status: 'CONFIRMED' });
    ticketAnswer = (req, res) => json(res, 404, { error: { code: 'ticket_not_found', message: 'x' } });

    const res = await ask('/bookings/5/ticket');

    assert.equal(res.status, 409);
    assert.equal((await res.json()).error.code, 'ticket_not_ready');
  });

  test('a service that fails is a 502, a bad id never reaches a service', async () => {
    calls.length = 0;
    bookingAnswer = (req, res) => json(res, 500, { error: { code: 'internal_error', message: 'x' } });
    assert.equal((await ask('/bookings/5/ticket')).status, 502);

    calls.length = 0;
    assert.equal((await ask('/bookings/5x/ticket')).status, 400);
    assert.equal(calls.length, 0);
  });
});
