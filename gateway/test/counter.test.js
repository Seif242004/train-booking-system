import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { after, before, describe, test } from 'node:test';
import express from 'express';
import { createApp } from '../src/app.js';
import { requireRole } from '../src/auth.js';
import { composeBoardDetail } from '../src/board.js';
import { forwardTo } from '../src/proxy.js';

function listen(handler) {
  return new Promise((resolve) => {
    const server = createServer(handler);
    server.listen(0, '127.0.0.1', () => resolve({ server, url: `http://127.0.0.1:${server.address().port}` }));
  });
}
const json = (res, status, body) => { res.writeHead(status, { 'content-type': 'application/json' }).end(JSON.stringify(body)); };

describe('counter and complimentary bookings: the admin routes', () => {
  test('the real Gateway: no token is 401 on the lookup, the issue and the refunded-at-counter routes', async () => {
    const real = createApp().listen(0, '127.0.0.1');
    await new Promise((resolve) => real.once('listening', resolve));
    const base = `http://127.0.0.1:${real.address().port}`;
    try {
      assert.equal((await fetch(`${base}/admin/passengers/lookup?q=a@b.co`)).status, 401);
      assert.equal((await fetch(`${base}/admin/bookings/5/issue`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' })).status, 401);
      assert.equal((await fetch(`${base}/admin/bookings/5/refunded-at-counter`, { method: 'POST' })).status, 401);
    } finally {
      real.close();
    }
  });

  describe('with a stand-in login', () => {
    let booking; let gateway;
    const calls = [];
    before(async () => {
      booking = await listen((req, res) => { calls.push({ method: req.method, url: req.url, authorization: req.headers.authorization }); json(res, 200, { ok: true }); });
      const app = express();
      app.use((req, res, next) => {
        if (!req.headers.authorization) return res.status(401).json({ error: { code: 'unauthenticated' } });
        req.user = { sub: 'u1', roles: req.headers.authorization === 'Bearer admin' ? ['USER', 'ADMIN'] : ['USER'] };
        return next();
      });
      const raw = express.raw({ type: () => true, limit: '1mb' });
      app.get('/admin/passengers/lookup', requireRole('ADMIN'), forwardTo(booking.url, '/admin/passengers'));
      app.use('/admin/bookings', requireRole('ADMIN'), raw, forwardTo(booking.url, '/admin/bookings'));
      gateway = await listen(app);
    });
    after(() => { booking.server.close(); gateway.server.close(); });
    const ask = (method, path, token, body) => fetch(`${gateway.url}${path}`, { method, headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' }, body });

    test('a signed-in passenger gets 403 and nothing reaches Booking', async () => {
      calls.length = 0;
      assert.equal((await ask('GET', '/admin/passengers/lookup?q=a@b.co', 'user')).status, 403);
      assert.equal((await ask('POST', '/admin/bookings/5/issue', 'user', '{}')).status, 403);
      assert.equal((await ask('POST', '/admin/bookings/5/refunded-at-counter', 'user')).status, 403);
      assert.equal(calls.length, 0);
    });

    test('an admin is forwarded with the token, the query and the body', async () => {
      calls.length = 0;
      assert.equal((await ask('GET', '/admin/passengers/lookup?q=a%40b.co', 'admin')).status, 200);
      assert.equal((await ask('POST', '/admin/bookings/5/issue', 'admin', '{"method":"COUNTER"}')).status, 200);
      assert.equal((await ask('POST', '/admin/bookings/5/refunded-at-counter', 'admin')).status, 200);
      assert.deepEqual(calls.map((c) => `${c.method} ${c.url}`), ['GET /admin/passengers/lookup?q=a%40b.co', 'POST /admin/bookings/5/issue', 'POST /admin/bookings/5/refunded-at-counter']);
      assert.ok(calls.every((c) => c.authorization === 'Bearer admin'));
    });
  });

  test('the board detail carries the hold expiry of a held seat, and only for held seats', () => {
    const layout = { stops: [], coaches: [{ id: 1, code: 'A', class: 'SECOND', position: 1, standingCapacity: 0, seats: [{ id: 1 }, { id: 2 }] }] };
    const detail = { generatedAt: 'x', trip: {}, standing: [], seats: [
      { seatId: 1, coachId: 1, state: 'HELD', reference: null, holdExpiresAt: '2026-11-04T15:00:00+02:00' },
      { seatId: 2, coachId: 1, state: 'TAKEN', reference: 'TB-X', holdExpiresAt: null }] };
    const seats = composeBoardDetail(layout, detail).coaches[0].seats;
    assert.equal(seats[0].holdExpiresAt, '2026-11-04T15:00:00+02:00');
    assert.equal(seats[1].holdExpiresAt, null);
  });
});
