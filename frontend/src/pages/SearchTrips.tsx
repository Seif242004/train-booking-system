import { useEffect, useState, type FormEvent } from 'react';
import { CLASS_LABEL, type Station, type TripSearchResponse, type TripSearchResult, type TripStop } from '../adminTypes';
import { gatewayRequest, readError } from '../api';
import { addDays, clock, daysLater, duration, todayLocal } from '../time';

/**
 * The one time shown for a stop in the stop line: when the train leaves the first stop and the passenger's boarding stop,
 * and when it arrives at every other stop.
 */
function stopTime(stop: TripStop, boardingIndex: number): string | null {
  return stop.stopIndex === 0 || stop.stopIndex === boardingIndex ? stop.departureTime : stop.arrivalTime;
}

/** All the stops of a trip with their times; the stops of the passenger's journey stand out and the others are dimmed. */
function StopLine({ trip }: { trip: TripSearchResult }) {
  const tripStart = trip.stops[0]?.departureTime ?? trip.departureTime;
  return (
    <ol className="result-stops" aria-label="Stops of this trip">
      {trip.stops.map((stop) => {
        const onJourney = stop.stopIndex >= trip.fromStopIndex && stop.stopIndex <= trip.toStopIndex;
        const time = stopTime(stop, trip.fromStopIndex);
        const later = time ? daysLater(tripStart, time) : 0;
        return (
          <li key={stop.stopIndex} className={onJourney ? 'stop-on' : 'stop-off'}
            title={onJourney ? undefined : 'Not on your journey'}>
            <span className="sr-only">{onJourney ? '' : '(not on your journey) '}</span>
            <span className="stop-time">{clock(time)}</span>
            {later > 0 && <sup className="muted"> +{later}</sup>}
            {' '}{stop.stationName}
          </li>
        );
      })}
    </ol>
  );
}

/** Passenger page: search the trips from one station to another on a date (FR-S1, Flow 2). */
export function SearchTrips() {
  const [stations, setStations] = useState<Station[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [date, setDate] = useState(todayLocal());
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);
  // What was searched, kept apart from the form so that the headline does not change while the user is still typing.
  const [searched, setSearched] = useState<{ from: Station; to: Station; date: string; results: TripSearchResult[]; nextDate?: string } | null>(null);

  useEffect(() => {
    void (async () => {
      const result = await gatewayRequest('GET', '/stations');
      if (result.status === 200) setStations(result.body as Station[]);
      else setLoadError(readError(result).message);
    })();
  }, []);

  /** The form was submitted: check the three boxes, then search. */
  function submit(event: FormEvent) {
    event.preventDefault();
    const problems: Record<string, string> = {};
    if (from === '') problems.from = 'Choose where you start.';
    if (to === '') problems.to = 'Choose where you are going.';
    if (from !== '' && from === to) problems.to = 'Choose a different station than where you start.';
    if (date === '') problems.date = 'Choose a date.';
    setFieldErrors(problems);
    setSearchError(null);
    if (Object.keys(problems).length > 0) return;
    void runSearch(from, to, date);
  }

  /** Searches one origin, destination and date; the boxes are set to them so the page always shows what was searched. */
  async function runSearch(fromCode: string, toCode: string, day: string) {
    setFrom(fromCode);
    setTo(toCode);
    setDate(day);
    setFieldErrors({});
    setSearchError(null);
    setSearching(true);
    const result = await gatewayRequest('GET', `/trips?${new URLSearchParams({ from: fromCode, to: toCode, date: day })}`);
    setSearching(false);

    if (result.status === 200) {
      const fromStation = stations?.find((s) => s.code === fromCode);
      const toStation = stations?.find((s) => s.code === toCode);
      const body = result.body as TripSearchResponse;
      if (fromStation && toStation) setSearched({ from: fromStation, to: toStation, date: day, results: body.trips, nextDate: body.nextAvailableDate });
    } else {
      const error = readError(result);
      setSearched(null);
      if (error.code === 'validation_failed') setFieldErrors(Object.fromEntries(error.details.map((d) => [d.field, d.message])));
      else setSearchError(error.message);
    }
  }

  return (
    <section>
      <h1>Search trips</h1>

      {loadError && <p className="notice notice-error">{loadError}</p>}
      {stations?.length === 0 && <p className="notice">There are no stations yet, so there is nothing to search. An admin adds them.</p>}

      <form className="form search-form" onSubmit={submit} noValidate>
        <div className="inline-fields">
          <div className="field">
            <label htmlFor="search-from">From</label>
            <select id="search-from" value={from} onChange={(e) => setFrom(e.target.value)} disabled={stations === null}
              aria-invalid={fieldErrors.from ? true : undefined} aria-describedby="search-from-hint">
              <option value="">{stations === null ? 'Loading…' : 'Choose a station…'}</option>
              {stations?.map((s) => (
                <option key={s.id} value={s.code}>{s.name} ({s.code})</option>
              ))}
            </select>
            <span id="search-from-hint" className="field-error">{fieldErrors.from}</span>
          </div>
          <div className="field">
            <label htmlFor="search-to">To</label>
            <select id="search-to" value={to} onChange={(e) => setTo(e.target.value)} disabled={stations === null}
              aria-invalid={fieldErrors.to ? true : undefined} aria-describedby="search-to-hint">
              <option value="">{stations === null ? 'Loading…' : 'Choose a station…'}</option>
              {stations?.map((s) => (
                <option key={s.id} value={s.code}>{s.name} ({s.code})</option>
              ))}
            </select>
            <span id="search-to-hint" className="field-error">{fieldErrors.to}</span>
          </div>
          <div className="field">
            <label htmlFor="search-date">Date</label>
            <input id="search-date" type="date" value={date} onChange={(e) => setDate(e.target.value)}
              aria-invalid={fieldErrors.date ? true : undefined} aria-describedby="search-date-hint" />
            <span className="date-shortcuts">
              <button type="button" className="button" onClick={() => setDate(todayLocal())}>Today</button>
              <button type="button" className="button" onClick={() => setDate(addDays(todayLocal(), 1))}>Tomorrow</button>
            </span>
            <span id="search-date-hint" className="field-error">{fieldErrors.date}</span>
          </div>
        </div>
        {searchError && <p className="notice notice-error">{searchError}</p>}
        <div className="actions">
          <button type="submit" className="button button-primary" disabled={searching || stations === null}>
            {searching ? 'Searching…' : 'Search trips'}
          </button>
        </div>
      </form>

      {searched && (
        <div aria-live="polite">
          <div className="day-nav">
            <button type="button" className="button" disabled={searching || searched.date <= todayLocal()}
              onClick={() => void runSearch(searched.from.code, searched.to.code, addDays(searched.date, -1))}>
              ← Previous day
            </button>
            <h2>
              {searched.from.name} to {searched.to.name}, {searched.date}
            </h2>
            <button type="button" className="button" disabled={searching}
              onClick={() => void runSearch(searched.from.code, searched.to.code, addDays(searched.date, 1))}>
              Next day →
            </button>
          </div>
          {searched.date === todayLocal() && <p className="muted">Trips that have already left {searched.from.name} are not shown.</p>}
          {searched.results.length === 0 ? (
            <p className="muted no-trips">
              No trips on {searched.date}.
              {searched.nextDate ? (
                <>
                  {' '}Next available: {searched.nextDate}{' '}
                  <button type="button" className="button" disabled={searching}
                    onClick={() => void runSearch(searched.from.code, searched.to.code, searched.nextDate!)}>
                    Show {searched.nextDate}
                  </button>
                </>
              ) : (
                ' There are no later trips from ' + searched.from.name + ' to ' + searched.to.name + '.'
              )}
            </p>
          ) : (
            <ul className="results">
              {searched.results.map((trip) => {
                const later = daysLater(trip.departureTime, trip.arrivalTime);
                return (
                  <li key={trip.tripId} className="result-card">
                    <div className="result-times">
                      <span className="result-clock">{clock(trip.departureTime)}</span>
                      <span className="result-arrow" aria-hidden="true">→</span>
                      <span className="result-clock">
                        {clock(trip.arrivalTime)}
                        {later > 0 && <sup className="muted"> +{later} day</sup>}
                      </span>
                      <span className="muted result-duration">{duration(trip.departureTime, trip.arrivalTime)}</span>
                    </div>
                    <p className="result-train">
                      <span className="mono">{trip.trainNumber}</span> {trip.trainName} <span className="muted">· {trip.trainType} · {trip.routeName}</span>
                    </p>
                    <StopLine trip={trip} />
                    {trip.fares.length > 0 ? (
                      <ul className="chips fare-chips" aria-label="Fares for this journey">
                        {trip.fares.map((fare) => (
                          <li key={fare.class}>
                            {CLASS_LABEL[fare.class]} <strong>{fare.amount.toFixed(2)}</strong> {fare.currency}
                          </li>
                        ))}
                      </ul>
                    ) : (
                      <p className="muted">No fares are set for this journey yet.</p>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
          <p className="muted">Seats and standing places left, and booking, come in a later step.</p>
        </div>
      )}
    </section>
  );
}
