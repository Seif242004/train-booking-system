import { Fragment, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { CLASS_LABEL } from '../adminTypes';
import { STATE_LABEL, type BoardTrip, type SeatCounts, type TripState } from '../boardTypes';
import type { Booking } from '../bookingTypes';
import { BoardDetailPanel } from '../components/BoardDetailPanel';
import { EmptyState } from '../components/EmptyState';
import { ErrorState } from '../components/ErrorState';
import { ListSkeleton } from '../components/ListSkeleton';
import { SlideOver } from '../components/SlideOver';
import { addDays, clock, dayLabel, dayOf, daysLater } from '../time';
import { useBoardData } from '../useBoardData';

type Range = 'live' | 'today' | 'tomorrow' | 'next7' | 'last7' | 'custom';
type Filter = TripState | 'FULL';

const RANGES: { value: Range; label: string }[] = [
  { value: 'live', label: 'Live window' },
  { value: 'today', label: 'Today' },
  { value: 'tomorrow', label: 'Tomorrow' },
  { value: 'next7', label: 'Next 7 days' },
  { value: 'last7', label: 'Last 7 days' },
  { value: 'custom', label: 'Pick a date' },
];
const FILTERS: Filter[] = ['UPCOMING', 'DEPARTED', 'ARRIVED', 'FULL', 'CANCELLED'];
const FILTER_LABEL: Record<Filter, string> = { ...STATE_LABEL, FULL: 'Full' };

/** Today's date in the railway's calendar, whatever the time zone of the browser is. */
const railwayDay = new Intl.DateTimeFormat('en-CA', { timeZone: 'Africa/Cairo' });
function today(): string {
  return railwayDay.format(new Date());
}

/** The date range a choice means, as the `from` and `to` of the API (the live window sends none: the server picks it). */
function rangeDates(range: Range, custom: string): { from?: string; to?: string } {
  const now = today();
  switch (range) {
    case 'today': return { from: now, to: now };
    case 'tomorrow': return { from: addDays(now, 1), to: addDays(now, 1) };
    case 'next7': return { from: now, to: addDays(now, 7) };
    case 'last7': return { from: addDays(now, -7), to: now };
    case 'custom': return /^\d{4}-\d{2}-\d{2}$/.test(custom) ? { from: custom, to: custom } : {};
    default: return {};
  }
}

function agoText(updatedAt: number | null, now: number): string {
  if (updatedAt === null) return '';
  const seconds = Math.max(0, Math.round((now - updatedAt) / 1000));
  return seconds < 3 ? 'Updated just now' : seconds < 90 ? `Updated ${seconds} s ago` : `Updated ${Math.round(seconds / 60)} min ago`;
}

function Occupancy({ counts }: { counts: SeatCounts }) {
  const { capacity, confirmed, held, free } = counts;
  const pct = (n: number) => (capacity > 0 ? `${(n / capacity) * 100}%` : '0%');
  const taken = capacity - free - held;
  const text = `${taken} taken · ${held} held · ${free} free of ${capacity}`;
  return (
    <div className="occupancy">
      <div className="occupancy-bar" role="img" aria-label={text}>
        <span className="occ-confirmed" style={{ width: pct(confirmed) }} />
        <span className="occ-held" style={{ width: pct(held) }} />
        <span className="occ-free" style={{ width: pct(free) }} />
      </div>
      <p className="occupancy-text">{text}</p>
    </div>
  );
}

function ClassLine({ name, counts }: { name: string; counts: SeatCounts }) {
  if (counts.capacity === 0) return null;
  return (
    <li>
      <strong>{name}</strong> {counts.confirmed} taken · {counts.held} held · {counts.free} free of {counts.capacity}
    </li>
  );
}

function StateChip({ trip }: { trip: BoardTrip }) {
  const tone = trip.state === 'UPCOMING' ? 'is-ok' : trip.state === 'CANCELLED' ? 'is-danger' : trip.state === 'DEPARTED' ? 'is-warn' : '';
  return (
    <>
      <span className={`status-chip ${tone}`}>{STATE_LABEL[trip.state]}</span>
      {trip.full && <span className="status-chip is-danger board-full-chip">Full</span>}
    </>
  );
}

/** Live trips board of the admin support area: every trip with its lifecycle state and its seats, refreshed every few seconds. */
export function TripsBoard() {
  const [params, setParams] = useSearchParams();
  const range = (RANGES.some((r) => r.value === params.get('range')) ? params.get('range') : 'live') as Range;
  const custom = params.get('date') ?? '';
  const q = params.get('q') ?? '';
  const filters = useMemo(() => new Set((params.get('state') ?? '').split(',').filter((s): s is Filter => (FILTERS as string[]).includes(s))), [params]);

  const dates = rangeDates(range, custom);
  const query = new URLSearchParams(Object.entries(dates).filter((e): e is [string, string] => Boolean(e[1]))).toString();
  const board = useBoardData(query);

  const [openId, setOpenId] = useState<number | null>(null);
  // A seat hold that this admin runs inside the slide-over: closing the panel (or the page) asks first.
  const [activeHold, setActiveHold] = useState<Booking | null>(null);
  const [askClose, setAskClose] = useState(false);
  useEffect(() => {
    if (!activeHold) return undefined;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [activeHold]);
  function requestClose() {
    if (activeHold) setAskClose(true);
    else setOpenId(null);
  }
  function answerClose(answer: 'stay' | 'keep' | 'release') {
    setAskClose(false);
    if (answer !== 'stay') {
      setActiveHold(null);
      setOpenId(null);
    }
  }
  const [expanded, setExpanded] = useState<ReadonlySet<number>>(new Set());
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, []);

  function update(changes: Record<string, string | null>) {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(changes)) {
      if (value === null || value === '') next.delete(key);
      else next.set(key, value);
    }
    setParams(next, { replace: true });
  }

  function toggle(filter: Filter) {
    const next = new Set(filters);
    if (next.has(filter)) next.delete(filter);
    else next.add(filter);
    update({ state: [...next].join(',') });
  }

  function clearFilters() {
    setParams(new URLSearchParams(), { replace: true });
  }

  const search = q.trim().toLowerCase();
  const rows = useMemo(() => {
    const trips = board.data?.trips ?? [];
    return trips.filter((t) => {
      if (filters.size > 0 && !(filters.has(t.state) || (t.full && filters.has('FULL')))) return false;
      if (!search) return true;
      return [t.trainName, t.trainNumber, t.origin, t.destination, t.routeName].some((v) => v.toLowerCase().includes(search));
    });
  }, [board.data, filters, search]);

  const summary = board.data?.summary;
  const tiles: { key: 'all' | Filter; label: string; value: number | undefined }[] = [
    { key: 'all', label: 'Trips in range', value: summary?.trips },
    { key: 'UPCOMING', label: 'Upcoming', value: summary?.upcoming },
    { key: 'DEPARTED', label: 'Departed', value: summary?.departed },
    { key: 'ARRIVED', label: 'Arrived', value: summary?.arrived },
    { key: 'FULL', label: 'Full', value: summary?.full },
    { key: 'CANCELLED', label: 'Cancelled', value: summary?.cancelled },
  ];
  const filtered = filters.size > 0 || search !== '';
  const lost = board.error !== null;

  return (
    <div className="board">
      <div className="board-live" aria-label="Refresh status">
        <span className={`live-dot${lost ? ' is-lost' : ''}`} aria-hidden="true" />
        <strong>{lost ? 'Offline' : 'Live'}</strong>
        <span className="muted">{lost ? 'Connection lost, retrying' : agoText(board.updatedAt, now)}</span>
        {lost && board.data && <span className="muted">· showing the data from {agoText(board.updatedAt, now).replace('Updated ', '')}</span>}
      </div>

      <div className="board-tiles" role="group" aria-label="Filter by trip state">
        {tiles.map((tile) => {
          const pressed = tile.key === 'all' ? filters.size === 0 : filters.size === 1 && filters.has(tile.key);
          return (
            <button key={tile.key} type="button" className="board-tile" aria-pressed={pressed}
              onClick={() => (tile.key === 'all' ? update({ state: null }) : update({ state: tile.key }))}>
              <span className="board-tile-value">{tile.value ?? '–'}</span>
              <span className="board-tile-label">{tile.label}</span>
            </button>
          );
        })}
      </div>

      <div className="card board-filters">
        <div className="chips" role="group" aria-label="Date range">
          {RANGES.map((r) => (
            <button key={r.value} type="button" className="chip" aria-pressed={range === r.value} onClick={() => update({ range: r.value === 'live' ? null : r.value })}>
              {r.label}
            </button>
          ))}
        </div>
        <div className="board-filter-row">
          {range === 'custom' && (
            <label className="field-inline">Date <input type="date" value={custom} onChange={(e) => update({ date: e.target.value })} /></label>
          )}
          <label className="field-inline board-search">Search trains and stations
            <input type="search" value={q} placeholder="Train name, number or station" autoComplete="off" onChange={(e) => update({ q: e.target.value })} />
          </label>
        </div>
        <div className="chips" role="group" aria-label="Trip state">
          {FILTERS.map((f) => (
            <button key={f} type="button" className="chip" aria-pressed={filters.has(f)} onClick={() => toggle(f)}>{FILTER_LABEL[f]}</button>
          ))}
        </div>
        <p className="muted board-help">Full means no seat is free for the whole journey. A held seat has a payment running (up to 5 minutes); a seat bought for only part of the route is not free for the whole journey either.</p>
      </div>

      <p className="board-summary" role="status" aria-live="polite">
        {summary ? `${rows.length} of ${summary.trips} trips shown · ${summary.upcoming} upcoming · ${summary.departed} departed · ${summary.arrived} arrived · ${summary.full} full · ${summary.cancelled} cancelled` : ''}
      </p>

      {!board.data && !board.error && <ListSkeleton rows={5} label="Loading the trips…" />}
      {!board.data && board.error && <ErrorState message={board.error} onRetry={board.refresh} />}

      {board.data && rows.length === 0 && (
        <EmptyState title="No trips match these filters" text="Try another date range or state."
          action={<button type="button" className="button" onClick={clearFilters}>Clear filters</button>} />
      )}

      {board.data && rows.length > 0 && (
        <div className="table-wrap board-wrap">
          <table className="table board-table">
            <caption className="visually-hidden">Trips, oldest departure first</caption>
            <thead>
              <tr>
                <th scope="col">Train and route</th>
                <th scope="col">Departure</th>
                <th scope="col">State</th>
                <th scope="col">Seats</th>
                <th scope="col"><span className="visually-hidden">Per class</span></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((trip) => {
                const open = expanded.has(trip.tripId);
                const later = daysLater(trip.departureTime, trip.arrivalTime);
                return (
                  <Fragment key={trip.tripId}>
                    <tr className={`board-row${board.changed.has(trip.tripId) ? ' is-changed' : ''}${openId === trip.tripId ? ' row-selected' : ''}`}
                      onClick={(event) => {
                        if ((event.target as HTMLElement).closest('button, a, input')) return;
                        event.currentTarget.querySelector<HTMLButtonElement>('button.trip-open')?.focus();
                        setOpenId(trip.tripId);
                      }}>
                      <td data-label="Train">
                        <button type="button" className="trip-open" aria-haspopup="dialog" onClick={() => setOpenId(trip.tripId)}>
                          {trip.trainName} <span className="mono">{trip.trainNumber}</span>
                          <span className="visually-hidden">, open details</span>
                        </button>
                        <div className="muted">{trip.origin} → {trip.destination}</div>
                      </td>
                      <td data-label="Departure">
                        {dayLabel(dayOf(trip.departureTime))} · {clock(trip.departureTime)} → {clock(trip.arrivalTime)}{later > 0 ? ` (+${later})` : ''}
                        {trip.departedAt && trip.totalSeats !== null && (
                          <div className="muted">Left {clock(trip.departedAt)} · {trip.totalSeats} seats · {trip.confirmedAtDeparture} sold</div>
                        )}
                      </td>
                      <td data-label="State"><StateChip trip={trip} /></td>
                      <td data-label="Seats"><Occupancy counts={trip.totals} /></td>
                      <td className="board-expand">
                        <button type="button" className="button board-expand-button" aria-expanded={open} aria-controls={`board-classes-${trip.tripId}`}
                          onClick={() => setExpanded((previous) => { const next = new Set(previous); if (next.has(trip.tripId)) next.delete(trip.tripId); else next.add(trip.tripId); return next; })}>
                          {open ? 'Hide classes' : 'Show classes'}
                        </button>
                      </td>
                    </tr>
                    {open && (
                      <tr className="board-classes-row">
                        <td colSpan={5} id={`board-classes-${trip.tripId}`}>
                          <ul className="board-classes">
                            <ClassLine name={CLASS_LABEL.FIRST} counts={trip.first} />
                            <ClassLine name={CLASS_LABEL.SECOND} counts={trip.second} />
                            <ClassLine name={CLASS_LABEL.THIRD} counts={trip.third} />
                            <ClassLine name="Standing" counts={trip.standing} />
                          </ul>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {filtered && board.data && rows.length > 0 && (
        <p className="muted"><button type="button" className="button" onClick={clearFilters}>Clear filters</button></p>
      )}

      <SlideOver open={openId !== null} title="Trip seats" wide onClose={requestClose}>
        {openId !== null && <BoardDetailPanel tripId={openId} refreshKey={board.updatedAt} onChanged={board.refresh} onHoldChange={setActiveHold} askClose={askClose} onAnswerClose={answerClose} />}
      </SlideOver>
    </div>
  );
}
