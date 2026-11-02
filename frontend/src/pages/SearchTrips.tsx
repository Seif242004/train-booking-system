import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { rememberSearch } from '../recentSearches';
import { CLASS_LABEL, type CoachClass, type JourneyAvailability, type Station, type TripSearchResponse, type TripSearchResult, type TripStop } from '../adminTypes';
import { gatewayRequest, readError } from '../api';
import { SearchForm, type SearchValues } from '../components/SearchForm';
import { money } from '../format';
import { addDays, clock, dayHeading, dayLabel, daysLater, duration, todayLocal } from '../time';
import { useDelayedFlag } from '../useDelayedFlag';

/**
 * What is left in one class for the journey, in words: "12 seats left · 5 standing", or "Full" when there is nothing
 * left of either. It is text, never only a colour. Returns null when the trip has neither seats nor standing of the class.
 */
function leftInClass(availability: JourneyAvailability, coachClass: CoachClass): string | null {
  const seats = availability.seatsLeft[coachClass];
  const standing = availability.standingLeft[coachClass];
  if (seats === undefined && standing === undefined) return null;
  if ((seats ?? 0) === 0 && (standing ?? 0) === 0) return 'Full';
  const parts: string[] = [];
  if (seats !== undefined) parts.push(seats === 0 ? 'no seats left' : `${seats} ${seats === 1 ? 'seat' : 'seats'} left`);
  if (standing !== undefined) parts.push(standing === 0 ? 'no standing' : `${standing} standing`);
  return parts.join(' · ');
}

/**
 * The one time shown for a stop in the stop line: when the train leaves the first stop and the passenger's boarding stop,
 * and when it arrives at every other stop.
 */
function stopTime(stop: TripStop, boardingIndex: number): string | null {
  return stop.stopIndex === 0 || stop.stopIndex === boardingIndex ? stop.departureTime : stop.arrivalTime;
}

/** All the stops of a trip with their times as a line of dots; the stops of the passenger's journey stand out and the others are dimmed. */
function StopLine({ trip }: { trip: TripSearchResult }) {
  const tripStart = trip.stops[0]?.departureTime ?? trip.departureTime;
  return (
    <div className="stop-line-box">
      <p className="stop-count">{trip.stops.length} stops</p>
      <ol className="stop-line" aria-label="Stops of this trip">
        {trip.stops.map((stop, index) => {
          const onJourney = stop.stopIndex >= trip.fromStopIndex && stop.stopIndex <= trip.toStopIndex;
          const isEnd = stop.stopIndex === trip.fromStopIndex || stop.stopIndex === trip.toStopIndex;
          const time = stopTime(stop, trip.fromStopIndex);
          const later = time ? daysLater(tripStart, time) : 0;
          const last = index === trip.stops.length - 1;
          const barOn = onJourney && stop.stopIndex < trip.toStopIndex;
          return (
            <li key={stop.stopIndex} className={onJourney ? 'is-on' : 'is-off'} title={onJourney ? undefined : 'Not on your journey'}>
              <span className="stop-track" aria-hidden="true">
                <span className={isEnd ? 'stop-dot stop-dot-end' : 'stop-dot'} />
                {!last && <span className={barOn ? 'stop-bar stop-bar-on' : 'stop-bar'} />}
              </span>
              <span className="stop-name">{stop.stationName}</span>
              <span className="stop-time">
                {clock(time)}
                {later > 0 && <sup> +{later}</sup>}
              </span>
              {!onJourney && <span className="sr-only"> (not on your journey)</span>}
            </li>
          );
        })}
      </ol>
    </div>
  );
}

function TripCard({ trip, index }: { trip: TripSearchResult; index: number }) {
  const later = daysLater(trip.departureTime, trip.arrivalTime);
  return (
    <li className="trip-card" style={{ ['--i' as string]: index }}>
      <div className="trip-times">
        <div className="trip-clocks">
          <span className="trip-clock">{clock(trip.departureTime)}</span>
          <span className="trip-arrow" aria-hidden="true">→</span>
          <span className="trip-clock">
            {clock(trip.arrivalTime)}
            {later > 0 && <sup> +{later} day</sup>}
          </span>
        </div>
        <span className="trip-meta">
          {duration(trip.departureTime, trip.arrivalTime)} · Train <span className="mono">{trip.trainNumber}</span> {trip.trainName}
        </span>
        <span className="trip-meta trip-meta-soft">{trip.trainType} · {trip.routeName}</span>
      </div>

      <StopLine trip={trip} />

      {trip.fares.length > 0 ? (
        <ul className="fare-tiles" aria-label="Fares for this journey">
          {trip.fares.map((fare) => {
            const left = trip.availability ? leftInClass(trip.availability, fare.class) : null;
            const soldOut = left === 'Full';
            const price = money(fare.amount, fare.currency);
            if (soldOut) {
              return (
                <li key={fare.class}>
                  <div className="fare-tile fare-tile-full">
                    <span className="fare-class">{CLASS_LABEL[fare.class]} class</span>
                    <span className="fare-price">{price}</span>
                    <span className="fare-left">Sold out</span>
                  </div>
                </li>
              );
            }
            return (
              <li key={fare.class}>
                <Link className="fare-tile" to={`/trips/${trip.tripId}/seats?from=${trip.fromStopIndex}&to=${trip.toStopIndex}&class=${fare.class}`}>
                  <span className="fare-class">{CLASS_LABEL[fare.class]} class</span>
                  <span className="fare-price">{price}</span>
                  {left && <span className="fare-left">{left}</span>}
                  <span className="sr-only"> Select seats</span>
                </Link>
              </li>
            );
          })}
        </ul>
      ) : (
        <p className="muted trip-nofares">No fares are set for this journey yet.</p>
      )}
      {trip.availability === null && <p className="muted availability-missing">Seats left: not available right now.</p>}
    </li>
  );
}

function SkeletonCards() {
  return (
    <ul className="trip-list" aria-busy="true" aria-label="Loading trains">
      {[0, 1, 2].map((n) => (
        <li key={n} className="trip-card trip-card-skeleton">
          <div className="trip-times">
            <div className="skeleton" style={{ height: 32, width: 160 }} />
            <div className="skeleton" style={{ height: 14, width: 120, marginTop: 10 }} />
          </div>
          <div className="stop-line-box">
            <div className="skeleton" style={{ height: 12, width: 80 }} />
            <div className="skeleton" style={{ height: 14, width: '100%', marginTop: 12 }} />
          </div>
          <div className="fare-tiles-skeleton">
            {[0, 1, 2].map((k) => (
              <div key={k} className="skeleton" style={{ height: 76, width: 112 }} />
            ))}
          </div>
        </li>
      ))}
    </ul>
  );
}

interface Searched {
  from: Station;
  to: Station;
  date: string;
  results: TripSearchResult[];
  nextDate?: string;
}

/** Finds the station a typed name or code means (from the landing page of a signed-out visitor): the code, then the name, then a name that starts with it. */
function stationFor(stations: Station[], text: string): Station | undefined {
  const wanted = text.trim().toLowerCase();
  if (wanted === '') return undefined;
  return (
    stations.find((s) => s.code.toLowerCase() === wanted) ??
    stations.find((s) => s.name.toLowerCase() === wanted) ??
    stations.find((s) => s.name.toLowerCase().startsWith(wanted))
  );
}

/** Passenger page: search the trips from one station to another on a date (FR-S1, Flow 2). */
export function SearchTrips() {
  const [stations, setStations] = useState<Station[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [params, setParams] = useSearchParams();

  const [formValues, setFormValues] = useState<SearchValues>({ from: '', to: '', date: todayLocal() });
  const [formKey, setFormKey] = useState(0);
  const [serverErrors, setServerErrors] = useState<Partial<Record<keyof SearchValues, string>>>({});
  const [showForm, setShowForm] = useState(true);

  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);
  // What was searched, kept apart from the form so that the headline does not change while the user is still typing.
  const [searched, setSearched] = useState<Searched | null>(null);
  // The first departure tomorrow, for the suggestion under today's trains.
  const [tomorrowFirst, setTomorrowFirst] = useState<string | null>(null);
  const handedOver = useRef(false);
  const showSkeleton = useDelayedFlag(searching);

  useEffect(() => {
    void (async () => {
      const result = await gatewayRequest('GET', '/stations');
      if (result.status === 200) setStations(result.body as Station[]);
      else setLoadError(readError(result).message);
    })();
  }, []);

  /** Searches one origin, destination and date; the boxes are set to them so the page always shows what was searched. */
  async function runSearch(fromCode: string, toCode: string, day: string) {
    setFormValues({ from: fromCode, to: toCode, date: day });
    setFormKey((k) => k + 1);
    setServerErrors({});
    setSearchError(null);
    setSearching(true);
    const result = await gatewayRequest('GET', `/trips?${new URLSearchParams({ from: fromCode, to: toCode, date: day })}`);
    setSearching(false);

    if (result.status === 200) {
      const fromStation = stations?.find((s) => s.code === fromCode);
      const toStation = stations?.find((s) => s.code === toCode);
      const body = result.body as TripSearchResponse;
      if (fromStation && toStation) {
        // The address always says what is shown (Back from the seat map returns here), and the search is remembered for the landing page.
        // replace: a new day or station pair does not add a history entry, so Back never walks through every date tried.
        setParams({ from: fromCode, to: toCode, date: day }, { replace: true });
        rememberSearch({ from: fromCode, to: toCode, fromName: fromStation.name, toName: toStation.name, date: day });
        setSearched({ from: fromStation, to: toStation, date: day, results: body.trips, nextDate: body.nextAvailableDate });
        setShowForm(false);
      }
    } else {
      const error = readError(result);
      setSearched(null);
      setShowForm(true);
      if (error.code === 'validation_failed') setServerErrors(Object.fromEntries(error.details.map((d) => [d.field, d.message])));
      else setSearchError(error.message);
    }
  }

  // The landing page hands over its search in the address (?from=&to=&date=): fill the boxes and search once the stations are known.
  useEffect(() => {
    if (!stations || handedOver.current) return;
    handedOver.current = true;
    const fromText = params.get('from');
    const toText = params.get('to');
    const day = params.get('date') ?? todayLocal();
    if (!fromText && !toText) return;
    const fromStation = stationFor(stations, fromText ?? '');
    const toStation = stationFor(stations, toText ?? '');
    if (fromStation && toStation && fromStation.code !== toStation.code && /^\d{4}-\d{2}-\d{2}$/.test(day)) {
      void runSearch(fromStation.code, toStation.code, day);
    } else {
      setFormValues({ from: fromStation?.code ?? '', to: toStation?.code ?? '', date: /^\d{4}-\d{2}-\d{2}$/.test(day) ? day : todayLocal() });
      setFormKey((k) => k + 1);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stations]);

  // Under today's trains: what is the first train tomorrow? Asked quietly; the suggestion works without the answer.
  const searchedToday = searched !== null && searched.date === todayLocal();
  const fromCode = searched?.from.code;
  const toCode = searched?.to.code;
  useEffect(() => {
    setTomorrowFirst(null);
    if (!searchedToday || !fromCode || !toCode) return;
    let cancelled = false;
    void (async () => {
      const result = await gatewayRequest('GET', `/trips?${new URLSearchParams({ from: fromCode, to: toCode, date: addDays(todayLocal(), 1) })}`);
      if (cancelled || result.status !== 200) return;
      const first = (result.body as TripSearchResponse).trips[0];
      if (first) setTomorrowFirst(clock(first.departureTime));
    })();
    return () => {
      cancelled = true;
    };
  }, [searchedToday, fromCode, toCode]);

  function submit(values: SearchValues) {
    void runSearch(values.from, values.to, values.date);
  }

  const today = todayLocal();
  const dayChips = searched ? [...new Set([...[0, 1, 2, 3].map((n) => addDays(today, n)), searched.date])].sort() : [];
  const count = searched?.results.length ?? 0;

  return (
    <>
      {searched && (
        <div className="results-bar">
          <div className="container results-bar-inner">
            <div className="results-title">
              <h1>
                {searched.from.name} → {searched.to.name}
              </h1>
              <button type="button" className="link-button" aria-expanded={showForm} onClick={() => setShowForm((v) => !v)}>
                {showForm ? 'Hide search' : 'Change search'}
              </button>
            </div>
            <div role="group" aria-label="Choose a day" className="day-chips">
              {dayChips.map((day) => (
                <button key={day} type="button" className="chip" aria-pressed={day === searched.date} disabled={searching && day !== searched.date}
                  onClick={() => void runSearch(searched.from.code, searched.to.code, day)}>
                  {day === today ? 'Today' : day === addDays(today, 1) ? 'Tomorrow' : dayLabel(day)}
                </button>
              ))}
            </div>
          </div>
        </div>
      )}

      <div className="container search-page">
        {!searched && <h1>Search trains</h1>}

        {loadError && <p className="notice notice-error">{loadError}</p>}
        {stations?.length === 0 && <p className="notice">There are no stations yet, so there is nothing to search. An admin adds them.</p>}

        {showForm && (
          <div className="search-panel">
            <SearchForm key={formKey} idPrefix="search" stations={stations} initial={formValues} onSubmit={submit} busy={searching || stations === null} serverErrors={serverErrors} />
            {searchError && <p className="notice notice-error">{searchError}</p>}
          </div>
        )}

        {!searched && searching && showSkeleton && <SkeletonCards />}

        {searched && (
          <div className="results">
            <div className="results-head">
              <h2>{dayHeading(searched.date, today)}</h2>
              <p className="results-count" aria-live="polite">
                {searching ? 'Finding trains' : `${count} ${count === 1 ? 'train' : 'trains'}`}
              </p>
            </div>
            {searched.date === today && <p className="muted">Trips that have already left {searched.from.name} are not shown.</p>}

            {searching && showSkeleton ? (
              <SkeletonCards />
            ) : count === 0 ? (
              <div className="empty-state">
                <h2>No trains on {dayLabel(searched.date)}</h2>
                {searched.nextDate ? (
                  <>
                    <p>The next day with a train from {searched.from.name} to {searched.to.name} is {dayLabel(searched.nextDate)}.</p>
                    <button type="button" className="button button-primary button-compact" disabled={searching}
                      onClick={() => void runSearch(searched.from.code, searched.to.code, searched.nextDate!)}>
                      Show {dayLabel(searched.nextDate)}
                    </button>
                  </>
                ) : (
                  <>
                    <p>There are no later trips from {searched.from.name} to {searched.to.name}. Try another pair of stations or another day.</p>
                    <button type="button" className="button button-primary button-compact" onClick={() => setShowForm(true)}>
                      Change search
                    </button>
                  </>
                )}
              </div>
            ) : (
              <ul className="trip-list" aria-busy={searching}>
                {searched.results.map((trip, index) => (
                  <TripCard key={trip.tripId} trip={trip} index={index} />
                ))}
              </ul>
            )}

            {searchedToday && count > 0 && !searching && (
              <div className="next-day-card">
                <div>
                  <p className="next-day-title">{count === 1 ? 'That is the last train today.' : 'Those are the last trains today.'}</p>
                  <p className="next-day-text">{tomorrowFirst ? `Tomorrow's first departure is ${tomorrowFirst}.` : 'See what runs tomorrow.'}</p>
                </div>
                <button type="button" className="button button-primary button-compact"
                  onClick={() => void runSearch(searched.from.code, searched.to.code, addDays(today, 1))}>
                  See tomorrow's trains
                </button>
              </div>
            )}
          </div>
        )}
      </div>
    </>
  );
}
