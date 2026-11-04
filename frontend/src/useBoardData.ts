import { useCallback, useEffect, useRef, useState } from 'react';
import { gatewayGet, readError } from './api';
import type { BoardResponse, BoardTrip } from './boardTypes';

const REFRESH_MS = 5000;
const MAX_BACKOFF_MS = 60_000;
const HIGHLIGHT_MS = 2200;

/** What a row shows, as one string: when it differs after a refresh, the row is highlighted. */
function signature(trip: BoardTrip): string {
  const t = trip.totals;
  return [trip.state, trip.full, t.capacity, t.confirmed, t.held, t.free, trip.second.free, trip.first.free, trip.third.free, trip.standing.free].join('/');
}

export interface BoardData {
  data: BoardResponse | null;
  /** The message of the last failed refresh, or null. The last good data stays in `data` either way. */
  error: string | null;
  /** The time of the last good answer (ms since 1970), or null before the first one. */
  updatedAt: number | null;
  /** Trips whose numbers changed in the last refresh. */
  changed: ReadonlySet<number>;
  refresh: () => void;
}

/**
 * Loads the live trips board and keeps it fresh: polls the list every 5 seconds, stops while the tab is hidden and refreshes at once when
 * it becomes visible again, and backs off (doubling up to a minute) after errors. A failed refresh never clears the data on screen.
 * The WebSocket is not used: its topics carry seat events that need a trip subscription and a per-user queue, and a board for the admin would
 * need a new authorisation surface there; polling the admin endpoint reuses the Gateway's admin check.
 */
export function useBoardData(query: string): BoardData {
  const [data, setData] = useState<BoardResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [updatedAt, setUpdatedAt] = useState<number | null>(null);
  const [changed, setChanged] = useState<ReadonlySet<number>>(new Set());
  const signatures = useRef<Map<number, string> | null>(null);
  const failures = useRef(0);
  const timer = useRef<number | undefined>(undefined);
  const clearHighlight = useRef<number | undefined>(undefined);
  const generation = useRef(0);
  const loadRef = useRef<() => Promise<void>>(async () => undefined);

  const schedule = useCallback((delay: number) => {
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => void loadRef.current(), delay);
  }, []);

  loadRef.current = async () => {
    if (document.hidden) return; // resumed by the visibilitychange listener
    const mine = generation.current;
    const result = await gatewayGet(`/admin/board/trips${query ? `?${query}` : ''}`);
    if (mine !== generation.current) return; // the filters changed meanwhile: this answer is for an older question
    if (result.status === 200) {
      const body = result.body as BoardResponse;
      const next = new Map(body.trips.map((trip) => [trip.tripId, signature(trip)]));
      const previous = signatures.current;
      signatures.current = next;
      if (previous) {
        const diff = new Set<number>();
        for (const [id, sig] of next) if (previous.has(id) && previous.get(id) !== sig) diff.add(id);
        if (diff.size > 0) {
          setChanged(diff);
          window.clearTimeout(clearHighlight.current);
          clearHighlight.current = window.setTimeout(() => setChanged(new Set()), HIGHLIGHT_MS);
        }
      }
      failures.current = 0;
      setData(body);
      setError(null);
      setUpdatedAt(Date.now());
      schedule(REFRESH_MS);
    } else {
      failures.current += 1;
      setError(readError(result).message);
      schedule(Math.min(REFRESH_MS * 2 ** failures.current, MAX_BACKOFF_MS));
    }
  };

  // New filters: load at once. The old data stays on screen until the new answer arrives (the board never goes blank).
  useEffect(() => {
    generation.current += 1;
    signatures.current = null;
    failures.current = 0;
    void loadRef.current();
    return () => window.clearTimeout(timer.current);
  }, [query]);

  useEffect(() => {
    function onVisible() {
      if (!document.hidden) {
        failures.current = 0;
        window.clearTimeout(timer.current);
        void loadRef.current();
      }
    }
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      document.removeEventListener('visibilitychange', onVisible);
      window.clearTimeout(timer.current);
      window.clearTimeout(clearHighlight.current);
    };
  }, []);

  const refresh = useCallback(() => {
    window.clearTimeout(timer.current);
    void loadRef.current();
  }, []);

  return { data, error, updatedAt, changed, refresh };
}
