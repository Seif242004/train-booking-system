// Tests of the search composition with Node's built-in test runner (no extra library). The two services are replaced by
// small HTTP servers, so the real fetch calls, headers and timeouts are exercised.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { after, before, describe, test } from 'node:test';
import express from 'express';
import { tripSearch } from '../src/search.js';

const TRIPS = [
  { tripId: 2, fromStopIndex: 0, toStopIndex: 2, trainNumber: 'T100' },
  { tripId: 4, fromStopIndex: 1, toStopIndex: 2, trainNumber: 'T100' },
];

function listen(handler) {
  return new Promise((resolve) => {
    const server = createServer(handler);
    server.listen(0, '127.0.0.1', () => resolve({ server, url: `http://127.0.0.1:${server.address().port}` }));
  });
}

const json = (res, status, body) => { res.writeHead(status, { 'content-type': 'application/json' }).end(JSON.stringify(body)); };

describe('GET /trips composed with the availability of Booking & Seat', () => {
  let trainSchedule; let booking; let gateway;
  const bookingCalls = [];
  let searchAnswer;
  let bookingBehaviour;

  before(async () => {
    trainSchedule = await listen((req, res) => {
      searchAnswer(req, res);
    });
    booking = await listen((req, res) => {
      bookingCalls.push({ url: req.url, authorization: req.headers.authorization });
      bookingBehaviour(req, res);
    });
    const app = express();
    app.get('/trips', tripSearch({
      trainScheduleUrl: trainSchedule.url, bookingSeatUrl: booking.url, upstreamTimeoutMs: 2000, availabilityTimeoutMs: 300,
    }));
    gateway = await listen(app);
  });

  after(() => { for (const s of [trainSchedule, booking, gateway]) s.server.close(); });

  const search = (query = 'from=CAI&to=ALX&date=2026-11-02') =>
    fetch(`${gateway.url}/trips?${query}`, { headers: { authorization: 'Bearer user-token' } });

  const summaryFor = (tripId) => ({ tripId, summary: { seatsLeft: { FIRST: tripId, SECOND: 20 }, standingLeft: { SECOND: 7 } } });

  const normalBooking = (req, res) => {
    const id = Number(req.url.match(/^\/trips\/(\d+)\/availability/)[1]);
    json(res, 200, summaryFor(id));
  };

  test('every trip gets the totals of its own journey, asked with the caller\'s token and the stop indexes of the trip', async () => {
    bookingCalls.length = 0;
    searchAnswer = (req, res) => json(res, 200, { trips: TRIPS });
    bookingBehaviour = normalBooking;

    const res = await search();
    const body = await res.json();

    assert.equal(res.status, 200);
    assert.deepEqual(body.trips.map((t) => t.availability), [
      { seatsLeft: { FIRST: 2, SECOND: 20 }, standingLeft: { SECOND: 7 } },
      { seatsLeft: { FIRST: 4, SECOND: 20 }, standingLeft: { SECOND: 7 } },
    ]);
    assert.equal(body.trips[0].trainNumber, 'T100'); // the trip data of Train & Schedule is untouched
    assert.deepEqual(bookingCalls.map((c) => c.url).sort(), [
      '/trips/2/availability?from=0&to=2&view=summary',
      '/trips/4/availability?from=1&to=2&view=summary',
    ]);
    assert.ok(bookingCalls.every((c) => c.authorization === 'Bearer user-token'));
  });

  test('a trip that Booking cannot answer for has availability null; the others and the search itself are fine', async () => {
    searchAnswer = (req, res) => json(res, 200, { trips: TRIPS });
    bookingBehaviour = (req, res) => (req.url.startsWith('/trips/2/') ? json(res, 500, { error: { code: 'internal_error' } }) : normalBooking(req, res));

    const res = await search();
    const body = await res.json();

    assert.equal(res.status, 200);
    assert.equal(body.trips[0].availability, null);
    assert.deepEqual(body.trips[1].availability.seatsLeft, { FIRST: 4, SECOND: 20 });
  });

  test('a trip that Booking does not know (404) has availability null', async () => {
    searchAnswer = (req, res) => json(res, 200, { trips: TRIPS });
    bookingBehaviour = (req, res) => json(res, 404, { error: { code: 'trip_not_found' } });

    const body = await (await search()).json();

    assert.deepEqual(body.trips.map((t) => t.availability), [null, null]);
  });

  test('a slow Booking costs only the availability, within the timeout', async () => {
    searchAnswer = (req, res) => json(res, 200, { trips: TRIPS });
    bookingBehaviour = (req, res) => { setTimeout(() => normalBooking(req, res), 1500); };
    const started = Date.now();

    const res = await search();
    const body = await res.json();

    assert.equal(res.status, 200);
    assert.deepEqual(body.trips.map((t) => t.availability), [null, null]);
    assert.ok(Date.now() - started < 1200, 'the lookups run together and give up after the availability timeout');
  });

  test('the lookups run at the same time, not one after the other', async () => {
    searchAnswer = (req, res) => json(res, 200, { trips: TRIPS });
    bookingBehaviour = (req, res) => { setTimeout(() => normalBooking(req, res), 200); };
    const started = Date.now();

    await (await search()).json();

    assert.ok(Date.now() - started < 380, 'two lookups of 200 ms must take about 200 ms together');
  });

  test('no Booking at all (connection refused): the trips come back with availability null', async () => {
    const dead = await listen(() => {});
    const deadUrl = dead.url;
    await new Promise((resolve) => dead.server.close(resolve));
    const app = express();
    app.get('/trips', tripSearch({ trainScheduleUrl: trainSchedule.url, bookingSeatUrl: deadUrl, upstreamTimeoutMs: 2000, availabilityTimeoutMs: 300 }));
    const local = await listen(app);
    searchAnswer = (req, res) => json(res, 200, { trips: TRIPS });

    const res = await fetch(`${local.url}/trips?from=CAI&to=ALX&date=2026-11-02`, { headers: { authorization: 'Bearer t' } });
    const body = await res.json();
    local.server.close();

    assert.equal(res.status, 200);
    assert.deepEqual(body.trips.map((t) => t.availability), [null, null]);
  });

  test('no trips: Booking is not asked, and the next available date is passed on', async () => {
    bookingCalls.length = 0;
    searchAnswer = (req, res) => json(res, 200, { trips: [], nextAvailableDate: '2026-11-09' });
    bookingBehaviour = normalBooking;

    const body = await (await search()).json();

    assert.deepEqual(body, { trips: [], nextAvailableDate: '2026-11-09' });
    assert.equal(bookingCalls.length, 0);
  });

  test('an answer of Train & Schedule that is not a success is relayed unchanged', async () => {
    const error = { error: { code: 'validation_failed', message: 'x', details: [{ field: 'to', message: 'unknown station code XXX' }] } };
    searchAnswer = (req, res) => json(res, 400, error);

    const res = await search('from=CAI&to=XXX&date=2026-11-02');

    assert.equal(res.status, 400);
    assert.deepEqual(await res.json(), error);
  });

  test('Train & Schedule being down is a 502, as for every other forwarded route', async () => {
    const dead = await listen(() => {});
    const deadUrl = dead.url;
    await new Promise((resolve) => dead.server.close(resolve));
    const app = express();
    app.get('/trips', tripSearch({ trainScheduleUrl: deadUrl, bookingSeatUrl: booking.url, upstreamTimeoutMs: 500, availabilityTimeoutMs: 300 }));
    const local = await listen(app);

    const res = await fetch(`${local.url}/trips?from=CAI&to=ALX&date=2026-11-02`, { headers: { authorization: 'Bearer t' } });
    local.server.close();

    assert.equal(res.status, 502);
    assert.equal((await res.json()).error.code, 'upstream_unavailable');
  });

  test('values that are not plain integers never reach the URL that is called', async () => {
    bookingCalls.length = 0;
    searchAnswer = (req, res) => json(res, 200, { trips: [{ tripId: '2/../../admin', fromStopIndex: 0, toStopIndex: 1 }, { tripId: 4, fromStopIndex: '0&x=1', toStopIndex: 1 }] });
    bookingBehaviour = normalBooking;

    const body = await (await search()).json();

    assert.deepEqual(body.trips.map((t) => t.availability), [null, null]);
    assert.equal(bookingCalls.length, 0);
  });
});
