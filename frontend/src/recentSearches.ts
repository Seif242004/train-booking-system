// The last searches, kept in this browser only (localStorage). Every read and write is wrapped: storage can be missing, full or blocked
// (private windows), and the app must work the same without it.
export interface RecentSearch {
  from: string;
  to: string;
  fromName: string;
  toName: string;
  date: string;
}

const KEY = 'tb.recentSearches';
const MAX = 3;

export function loadRecentSearches(): RecentSearch[] {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(KEY) ?? '[]');
    if (!Array.isArray(parsed)) return [];
    return parsed
      .filter((r): r is RecentSearch => typeof r?.from === 'string' && typeof r?.to === 'string' && typeof r?.date === 'string')
      .map((r) => ({ ...r, fromName: r.fromName || r.from, toName: r.toName || r.to }))
      .slice(0, MAX);
  } catch {
    return [];
  }
}

/** Puts a search first; the same pair of stations is kept once. */
export function rememberSearch(search: RecentSearch): void {
  try {
    const others = loadRecentSearches().filter((r) => !(r.from === search.from && r.to === search.to));
    localStorage.setItem(KEY, JSON.stringify([search, ...others].slice(0, MAX)));
  } catch {
    // Not remembering is harmless.
  }
}

/** The query string of the last search (for "Back to results"), or null. */
export function lastSearchQuery(): string | null {
  const last = loadRecentSearches().at(0);
  return last ? new URLSearchParams({ from: last.from, to: last.to, date: last.date }).toString() : null;
}
