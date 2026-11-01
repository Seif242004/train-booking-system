import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { after, before, describe, test } from 'node:test';
import express from 'express';
import { composeSeatMap, seatMap } from '../src/seatmap.js';

const LAYOUT = {
  tripId: 2, status: 'ACTIVE', departureDate: '2026-11-02', trainNumber: 'T100', trainName: 'Express', trainType: 'Express', routeName: 'Cairo-Alexandria',
  stops: [
    { stopIndex: 0, stationCode: 'CAI', stationName: 'Cairo', arrivalTime: null, departureTime: '2026-11-02T08:00:00' },
    { stopIndex: 1, stationCode: 'GIZ', stationName: 'Giza', arrivalTime: '2026-11-02T08:40:00', departureTime: '2026-11-02T08:45:00' },
    { stopIndex: 2, stationCode: 'ALX', stationName: 'Alexandria', arrivalTime: '2026-11-02T10:45:00', departureTime: null },
  ],
  coaches: [
    { id: 32, code: 'A1', class: 'FIRST', position: 1, standingCapacity: 10, seats: [{ id: 223, seatNumber: '1A', row: 1, column: 'A', class: 'FIRST' }, { id: 224, seatNumber: '1B', row: 1, column: 'B', class: 'FIRST' }] },
    { id: 33, code: 'B2', class: 'SECOND', position: 2, standingCapacity: 0, seats: [{ id: 235, seatNumber: '1A', row: 1, column: 'A', class: 'SECOND' }] },
  ],
};
const AVAILABILITY = {
  tripId: 2, tripStatus: 'OPEN', fromStop: 0, toStop: 2, version: 7,
  summary: { seatsLeft: { FIRST: 1, SECOND: 1 }, standingLeft: { FIRST: 8 } },
  fares: { FIRST: 230.0, SECOND: 150.0 },
  seats: [{ seatId: 223, coachId: 32, state: 'HELD' }, { seatId: 224, coachId: 32, state: 'AVAILABLE' }, { seatId: 235, coachId: 33, state: 'AVAILABLE' }],
  standing: [{ coachId: 32, capacity: 10, remaining: 8 }],
};

describe('composeSeatMap', () => {
  test('every seat gets its state, every coach its standing places, and the journey is described', () => {
    const map = composeSeatMap(LAYOUT, AVAILABILITY);

    assert.deepEqual(map.coaches[0].seats.map((s) => [s.seatNumber, s.state]), [['1A', 'HELD'], ['1B', 'AVAILABLE']]);
    assert.deepEqual(map.coaches[0].standing, { capacity: 10, remaining: 8 });
    assert.deepEqual(map.coaches[1].standing, { capacity: 0, remaining: 0 }); // a coach without standing places has no counters
    assert.equal(map.coaches[0].class, 'FIRST');
    assert.equal(map.journey.from.stationName, 'Cairo');
    assert.equal(map.journey.to.stationName, 'Alexandria');
    assert.equal(map.journey.departureTime, '2026-11-02T08:00:00');
    assert.equal(map.journey.arrivalTime, '2026-11-02T10:45:00');
    assert.deepEqual(map.fares, { FIRST: 230, SECOND: 150 });
    assert.equal(map.version, 7);
    assert.equal(map.status, 'ACTIVE');
    assert.equal(map.tripStatus, 'OPEN');
  });

  test('a seat that Booking does not know is shown as unavailable, never as free', () => {
    const map = composeSeatMap(LAYOUT, { ...AVAILABILITY, seats: [{ seatId: 223, coachId: 32, state: 'AVAILABLE' }] });

    assert.deepEqual(map.coaches[0].seats.map((s) => s.state), ['AVAILABLE', 'BOOKED']);
  });
});

function listen(handler) {
  return new Promise((resolve) => {
    const server = createServer(handler);
    server.listen(0, '127.0.0.1', () => resolve({ server, url: `http://127.0.0.1:${server.address().port}` }));
  });
}
const json = (res, status, body) => { res.writeHead(status, { 'content-type': 'application/json' }).end(JSON.stringify(body)); };

describe('GET /trips/:id/seat-map', () => {
  let trainSchedule; let booking; let gateway;
  let layoutAnswer; let availabilityAnswer;
  const calls = [];

  before(async () => {
    trainSchedule = await listen((req, res) => { calls.push({ url: req.url, authorization: req.headers.authorization }); layoutAnswer(req, res); });
    booking = await listen((req, res) => { calls.push({ url: req.url, authorization: req.headers.authorization }); availabilityAnswer(req, res); });
    const app = express();
    app.get('/trips/:id/seat-map', seatMap({ trainScheduleUrl: trainSchedule.url, bookingSeatUrl: booking.url, upstreamTimeoutMs: 400 }));
    gateway = await listen(app);
  });
  after(() => { for (const s of [trainSchedule, booking, gateway]) s.server.close(); });

  const ask = (path) => fetch(`${gateway.url}${path}`, { headers: { authorization: 'Bearer user-token' } });

  test('composes the layout and the availability, asking each service with the caller\'s token', async () => {
    calls.length = 0;
    layoutAnswer = (req, res) => json(res, 200, LAYOUT);
    availabilityAnswer = (req, res) => json(res, 200, AVAILABILITY);

    const res = await ask('/trips/2/seat-map?from=0&to=2');
    const body = await res.json();

    assert.equal(res.status, 200);
    assert.equal(body.coaches[0].seats[0].state, 'HELD');
    assert.deepEqual(calls.map((c) => c.url).sort(), ['/trips/2/availability?from=0&to=2', '/trips/2/layout']);
    assert.ok(calls.every((c) => c.authorization === 'Bearer user-token'));
  });

  test('values that are not plain numbers never reach a service', async () => {
    calls.length = 0;

    assert.equal((await ask('/trips/2/seat-map?from=0&to=2%26x%3D1')).status, 400);
    assert.equal((await ask('/trips/2/seat-map?from=a&to=2')).status, 400);
    assert.equal((await ask('/trips/2/seat-map')).status, 400);
    assert.equal(calls.length, 0);
  });

  test('an unknown trip (404) and a journey that does not fit (400) are relayed as they are', async () => {
    layoutAnswer = (req, res) => json(res, 404, { error: { code: 'trip_not_found', message: 'Trip 2 does not exist.' } });
    availabilityAnswer = (req, res) => json(res, 404, { error: { code: 'trip_not_found', message: 'Trip 2 does not exist.' } });
    const missing = await ask('/trips/2/seat-map?from=0&to=2');
    assert.equal(missing.status, 404);
    assert.equal((await missing.json()).error.code, 'trip_not_found');

    layoutAnswer = (req, res) => json(res, 200, LAYOUT);
    availabilityAnswer = (req, res) => json(res, 400, { error: { code: 'validation_failed', message: 'The request body is not valid.', details: [{ field: 'to', message: 'x' }] } });
    const bad = await ask('/trips/2/seat-map?from=0&to=9');
    assert.equal(bad.status, 400);
    assert.equal((await bad.json()).error.details[0].field, 'to');
  });

  test('a service that fails is a 502, one that is too slow is a 504', async () => {
    layoutAnswer = (req, res) => json(res, 200, LAYOUT);
    availabilityAnswer = (req, res) => json(res, 500, { error: { code: 'internal_error', message: 'x' } });
    assert.equal((await ask('/trips/2/seat-map?from=0&to=2')).status, 502);

    availabilityAnswer = (req, res) => { setTimeout(() => json(res, 200, AVAILABILITY), 1000); };
    assert.equal((await ask('/trips/2/seat-map?from=0&to=2')).status, 504);
  });
});
