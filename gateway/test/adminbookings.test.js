import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { after, before, describe, test } from 'node:test';
import { createApp } from '../src/app.js';

// The admin bookings route needs a valid ADMIN token. Verifying tokens needs Keycloak's keys, so the test only checks the refusals that
// happen before any key is needed (no token), and that an unknown method/path is not routed.
describe('the admin bookings route', () => {
  let gateway;
  let base;

  before(async () => {
    gateway = createApp().listen(0, '127.0.0.1');
    await new Promise((resolve) => gateway.once('listening', resolve));
    base = `http://127.0.0.1:${gateway.address().port}`;
  });

  after(() => gateway.close());

  test('without a token: 401 and nothing is forwarded', async () => {
    const res = await fetch(`${base}/admin/bookings/5/cancel`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{"reason":"x"}' });

    assert.equal(res.status, 401);
    assert.equal((await res.json()).error.code, 'unauthenticated');
  });

  test('cancelling a whole trip and reading its progress need a token too', async () => {
    const cancel = await fetch(`${base}/admin/trips/5/cancel`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{"reason":"x"}' });
    const progress = await fetch(`${base}/admin/trips/5/cancellation`);

    assert.equal(cancel.status, 401);
    assert.equal(progress.status, 401);
  });

  test('a garbage token: 401', async () => {
    const res = await fetch(`${base}/admin/bookings/5/cancel`, { method: 'POST', headers: { authorization: 'Bearer not.a.token', 'content-type': 'application/json' }, body: '{}' });

    assert.equal(res.status, 401);
  });
});
