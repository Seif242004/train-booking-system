// Tests of the WebSocket proxy with Node's built-in runner: a real http server with the proxy attached, and a fake Booking & Seat
// (a raw TCP server that answers the upgrade and then echoes what it receives).
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import net from 'node:net';
import { after, before, describe, test } from 'node:test';
import { HttpError } from '../src/errors.js';
import { attachWebSocketProxy, tokenFromUpgrade } from '../src/wsproxy.js';

const ORIGIN = 'http://localhost:5173';

function listenTcp(onConnection) {
  return new Promise((resolve) => {
    const server = net.createServer(onConnection);
    server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port }));
  });
}

/** Sends a raw upgrade request and collects what comes back until the socket closes or `stopAfter` bytes have arrived. */
function upgrade(port, headers, { path = '/ws', wait = 300, send } = {}) {
  return new Promise((resolve) => {
    const socket = net.connect({ host: '127.0.0.1', port }, () => {
      const lines = [`GET ${path} HTTP/1.1`, `Host: localhost:${port}`, 'Connection: Upgrade', 'Upgrade: websocket', 'Sec-WebSocket-Version: 13', 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ=='];
      for (const [name, value] of Object.entries(headers)) lines.push(`${name}: ${value}`);
      socket.write(`${lines.join('\r\n')}\r\n\r\n`);
      if (send) setTimeout(() => socket.write(send), 100);
    });
    let received = '';
    socket.on('data', (chunk) => { received += chunk.toString(); });
    const done = () => resolve(received);
    socket.on('close', done);
    setTimeout(() => { socket.destroy(); done(); }, wait);
  });
}

describe('tokenFromUpgrade', () => {
  test('reads the token from the subprotocol, never from the URL', () => {
    assert.equal(tokenFromUpgrade({ 'sec-websocket-protocol': 'v12.stomp, access_token.abc.def.ghi' }), 'abc.def.ghi');
    assert.equal(tokenFromUpgrade({ 'sec-websocket-protocol': 'access_token.abc, v12.stomp' }), 'abc');
  });
  test('accepts an Authorization header for tools that can set one', () => {
    assert.equal(tokenFromUpgrade({ authorization: 'Bearer xyz' }), 'xyz');
  });
  test('no token at all, or an empty one, is no token', () => {
    assert.equal(tokenFromUpgrade({}), null);
    assert.equal(tokenFromUpgrade({ 'sec-websocket-protocol': 'v12.stomp' }), null);
    assert.equal(tokenFromUpgrade({ 'sec-websocket-protocol': 'access_token.' }), null);
  });
});

describe('the /ws upgrade', () => {
  let booking; let gateway; let gatewayPort;
  const upstreamRequests = [];
  const logs = [];
  const realLog = console.log;

  before(async () => {
    booking = await listenTcp((socket) => {
      let first = true;
      socket.on('data', (chunk) => {
        if (first) {
          first = false;
          upstreamRequests.push(chunk.toString());
          socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Protocol: v12.stomp\r\n\r\n');
        } else {
          socket.write(`echo:${chunk.toString()}`);
        }
      });
      socket.on('error', () => {});
    });
    gateway = createServer((req, res) => res.end('http'));
    attachWebSocketProxy(gateway, {
      bookingSeatUrl: `http://127.0.0.1:${booking.port}`,
      corsOrigin: ORIGIN,
      authenticate: async (header) => {
        if (header === 'Bearer good-token') return { sub: 'alice-sub' };
        if (header === 'Bearer keys-down') throw new HttpError(503, 'auth_unavailable', 'x');
        throw new HttpError(401, 'invalid_token', 'x');
      },
    });
    await new Promise((resolve) => gateway.listen(0, '127.0.0.1', resolve));
    gatewayPort = gateway.address().port;
    console.log = (line) => { logs.push(String(line)); };
  });

  after(() => { console.log = realLog; gateway.close(); booking.server.close(); });

  test('a valid token (offered as a subprotocol) is passed on to Booking, and the connection is piped both ways', async () => {
    upstreamRequests.length = 0;

    const received = await upgrade(gatewayPort, { Origin: ORIGIN, 'Sec-WebSocket-Protocol': 'v12.stomp, access_token.good-token' }, { send: 'hello' });

    assert.match(received, /^HTTP\/1\.1 101 /);
    assert.match(received, /Sec-WebSocket-Protocol: v12\.stomp/); // the real subprotocol, never the token
    assert.ok(received.includes('echo:hello'), 'what the browser sends reaches Booking and the answer comes back');
    assert.equal(upstreamRequests.length, 1);
    assert.match(upstreamRequests[0], /^GET \/ws HTTP\/1\.1\r\n/);
    assert.match(upstreamRequests[0], /Sec-WebSocket-Protocol: v12\.stomp, access_token\.good-token/); // Booking checks the token again itself
  });

  test('no token, a bad token and an expired one are 401, and Booking is never contacted', async () => {
    upstreamRequests.length = 0;

    assert.match(await upgrade(gatewayPort, { Origin: ORIGIN }), /^HTTP\/1\.1 401 /);
    assert.match(await upgrade(gatewayPort, { Origin: ORIGIN, 'Sec-WebSocket-Protocol': 'v12.stomp, access_token.bad' }), /^HTTP\/1\.1 401 /);
    assert.equal(upstreamRequests.length, 0);
  });

  test('when the Keycloak keys cannot be fetched it is a 503, not a 401', async () => {
    assert.match(await upgrade(gatewayPort, { Origin: ORIGIN, 'Sec-WebSocket-Protocol': 'v12.stomp, access_token.keys-down' }), /^HTTP\/1\.1 503 /);
  });

  test('another origin is refused (cross-site WebSocket hijacking), even with a valid token', async () => {
    upstreamRequests.length = 0;

    const received = await upgrade(gatewayPort, { Origin: 'http://evil.example', 'Sec-WebSocket-Protocol': 'v12.stomp, access_token.good-token' });

    assert.match(received, /^HTTP\/1\.1 403 /);
    assert.equal(upstreamRequests.length, 0);
  });

  test('a token in the URL is not accepted, and other paths are not WebSocket routes', async () => {
    assert.match(await upgrade(gatewayPort, { Origin: ORIGIN }, { path: '/ws?access_token=good-token' }), /^HTTP\/1\.1 401 /);
    assert.match(await upgrade(gatewayPort, { Origin: ORIGIN, 'Sec-WebSocket-Protocol': 'access_token.good-token' }, { path: '/admin/ws' }), /^HTTP\/1\.1 404 /);
  });

  test('the token and the query string are never written to the log', async () => {
    logs.length = 0;

    await upgrade(gatewayPort, { Origin: ORIGIN, 'Sec-WebSocket-Protocol': 'v12.stomp, access_token.good-token' }, { path: '/ws?secret=1' });

    assert.ok(logs.length >= 1);
    assert.ok(logs.every((line) => !line.includes('good-token') && !line.includes('secret=1')));
    assert.ok(logs.some((line) => line.includes('alice-sub')));
  });

  test('a Booking that is down is a 502', async () => {
    const dead = await listenTcp(() => {});
    const deadPort = dead.port;
    await new Promise((resolve) => dead.server.close(resolve));
    const server = createServer();
    attachWebSocketProxy(server, { bookingSeatUrl: `http://127.0.0.1:${deadPort}`, corsOrigin: ORIGIN, authenticate: async () => ({ sub: 'x' }) });
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));

    const received = await upgrade(server.address().port, { Origin: ORIGIN, 'Sec-WebSocket-Protocol': 'access_token.t' });
    server.close();

    assert.match(received, /^HTTP\/1\.1 502 /);
  });
});
