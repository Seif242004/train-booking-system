import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { after, before, describe, test } from 'node:test';
import express from 'express';
import { createApp } from '../src/app.js';
import { requireRole } from '../src/auth.js';
import { boardTripDetail, composeBoardDetail } from '../src/board.js';
import { forwardTo } from '../src/proxy.js';

function listen(handler) {
  return new Promise((resolve) => {
    const server = createServer(handler);
    server.listen(0, '127.0.0.1', () => resolve({ server, url: `http://127.0.0.1:${server.address().port}` }));
  });
}
const json = (res, status, body) => { res.writeHead(status, { 'content-type': 'application/json' }).end(JSON.stringify(body)); };

const LAYOUT = {
  stops: [{ stopIndex: 0, stationName: 'Cairo' }],
  coaches: [{ id: 10, code: 'A', class: 'SECOND', position: 1, standingCapacity: 4, seats: [{ id: 1, seatNumber: 'A1' }, { id: 2, seatNumber: 'A2' }, { id: 3, seatNumber: 'A3' }] }],
};
const DETAIL = {
  generatedAt: '2026-11-04T10:00:00+02:00',
  trip: { tripId: 7, state: 'UPCOMING' },
  seats: [{ seatId: 1, coachId: 10, state: 'TAKEN', reference: 'TB-ABC234' }, { seatId: 2, coachId: 10, state: 'HELD', reference: 'TB-LEAK00' }],
  standing: [{ coachId: 10, counts: { capacity: 4, confirmed: 1, held: 1, free: 2 } }],
};

describe('composeBoardDetail', () => {
  test('puts the states on the seats; a reference only on taken seats; an unknown seat is taken, never free', () => {
    const detail = composeBoardDetail(LAYOUT, DETAIL);
    const seats = detail.coaches[0].seats;
    assert.deepEqual(seats.map((s) => [s.id, s.state, s.reference]), [[1, 'TAKEN', 'TB-ABC234'], [2, 'HELD', null], [3, 'TAKEN', null]]);
    assert.deepEqual(detail.coaches[0].standing, { capacity: 4, confirmed: 1, held: 1, free: 2 });
    assert.equal(detail.trip.tripId, 7);
  });
});

describe('the trips board routes', () => {
  let booking; let layout; let gateway;
  const calls = [];

  before(async () => {
    booking = await listen((req, res) => {
      calls.push({ svc: 'booking', url: req.url, authorization: req.headers.authorization });
      if (req.url.startsWith('/admin/board/trips/9')) return json(res, 404, { error: { code: 'trip_not_found', message: 'no' } });
      if (req.url.startsWith('/admin/board/trips/')) return json(res, 200, DETAIL);
      return json(res, 200, { summary: { trips: 0 }, trips: [] });
    });
    layout = await listen((req, res) => { calls.push({ svc: 'layout', url: req.url }); json(res, 200, LAYOUT); });
    const app = express();
    // Stands in for requireAuth (verifying a token needs Keycloak's keys): "Bearer admin" is an admin, "Bearer user" a passenger.
    app.use((req, res, next) => {
      if (!req.headers.authorization) return res.status(401).json({ error: { code: 'unauthenticated' } });
      req.user = { sub: 'u1', roles: req.headers.authorization === 'Bearer admin' ? ['USER', 'ADMIN'] : ['USER'] };
      return next();
    });
    app.get('/admin/board/trips', requireRole('ADMIN'), forwardTo(booking.url, '/admin/board/trips'));
    app.get('/admin/board/trips/:id', requireRole('ADMIN'), boardTripDetail({ bookingSeatUrl: booking.url, trainScheduleUrl: layout.url, upstreamTimeoutMs: 400 }));
    gateway = await listen(app);
  });
  after(() => { for (const s of [booking, layout, gateway]) s.server.close(); });

  const ask = (path, token) => fetch(`${gateway.url}${path}`, { headers: token ? { authorization: `Bearer ${token}` } : {} });

  test('the real Gateway: no token is 401 on both routes', async () => {
    const real = createApp().listen(0, '127.0.0.1');
    await new Promise((resolve) => real.once('listening', resolve));
    try {
      for (const path of ['/admin/board/trips', '/admin/board/trips/7']) {
        const res = await fetch(`http://127.0.0.1:${real.address().port}${path}`);
        assert.equal(res.status, 401);
      }
    } finally {
      real.close();
    }
  });

  test('a signed-in passenger gets 403 and nothing reaches the services', async () => {
    calls.length = 0;
    assert.equal((await ask('/admin/board/trips', 'user')).status, 403);
    assert.equal((await ask('/admin/board/trips/7', 'user')).status, 403);
    assert.equal(calls.length, 0);
  });

  test('an admin gets the list, forwarded with the query string and the token', async () => {
    calls.length = 0;
    const res = await ask('/admin/board/trips?state=FULL&from=2026-11-04', 'admin');
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { summary: { trips: 0 }, trips: [] });
    assert.equal(calls[0].url, '/admin/board/trips?state=FULL&from=2026-11-04');
    assert.equal(calls[0].authorization, 'Bearer admin');
  });

  test('an admin gets the composed detail; an unknown trip is relayed as 404; bad id is 400', async () => {
    const res = await ask('/admin/board/trips/7', 'admin');
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.coaches[0].seats[0].reference, 'TB-ABC234');
    assert.equal((await ask('/admin/board/trips/9', 'admin')).status, 404);
    assert.equal((await ask('/admin/board/trips/abc', 'admin')).status, 400);
  });
});
