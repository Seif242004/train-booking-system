import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { after, before, describe, test } from 'node:test';

// The Gateway reads its settings when it is imported: point it at the fake Payment service before that.
let payment;
const received = [];
const paymentServer = createServer((req, res) => {
  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', () => {
    received.push({ method: req.method, url: req.url, headers: req.headers, body: Buffer.concat(chunks) });
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end('{"received":true}');
  });
});

describe('the Stripe webhook route', () => {
  let gateway;
  let base;

  before(async () => {
    await new Promise((resolve) => paymentServer.listen(0, '127.0.0.1', resolve));
    payment = `http://127.0.0.1:${paymentServer.address().port}`;
    process.env.PAYMENT_URL = payment;
    const { createApp } = await import('../src/app.js');
    gateway = createApp().listen(0, '127.0.0.1');
    await new Promise((resolve) => gateway.once('listening', resolve));
    base = `http://127.0.0.1:${gateway.address().port}`;
  });

  after(() => {
    gateway.close();
    paymentServer.close();
  });

  test('forwards the body byte for byte with the signature, and no token is needed', async () => {
    received.length = 0;
    // Spaces and key order that a JSON parser would change: the signature is computed over these exact bytes.
    const body = '{ "id" : "evt_1",   "type":"payment_intent.succeeded", "data":{"b":1,"a":2} }\n';

    const res = await fetch(`${base}/webhooks/payment`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'stripe-signature': 't=1,v1=abc', authorization: 'Bearer should-not-travel', cookie: 'x=1' },
      body,
    });

    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { received: true });
    assert.equal(received.length, 1);
    assert.equal(received[0].url, '/webhooks/payment');
    assert.equal(received[0].body.toString('utf8'), body);
    assert.equal(received[0].headers['stripe-signature'], 't=1,v1=abc');
    assert.equal(received[0].headers.authorization, undefined);
    assert.equal(received[0].headers.cookie, undefined);
  });

  test('only POST on the exact path is routed', async () => {
    received.length = 0;

    assert.equal((await fetch(`${base}/webhooks/payment`)).status, 404);
    assert.equal((await fetch(`${base}/webhooks/other`, { method: 'POST', body: '{}' })).status, 404);
    assert.equal(received.length, 0);
  });

  test('answers 502 when Payment is down, so Stripe retries', async () => {
    paymentServer.close();
    await new Promise((resolve) => setTimeout(resolve, 50));

    const res = await fetch(`${base}/webhooks/payment`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' });

    assert.equal(res.status, 502);
  });
});
