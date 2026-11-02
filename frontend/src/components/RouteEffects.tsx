import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigationType } from 'react-router-dom';
import { titleFor } from '../routes';

// Settling time after a route change in which scroll events are ignored: the browser clamps the old position while the new page renders.
const SETTLE_MS = 200;
// How long a restored scroll position keeps trying while the page is still loading its content.
const RESTORE_FOR_MS = 1500;

/**
 * What a single-page app must do by hand on each navigation:
 *  - a distinct document title, announced to screen readers through a polite live region;
 *  - focus moves to the new page's h1 (waiting for it when the page loads asynchronously);
 *  - Back and Forward restore the scroll position, a new page starts at the top.
 */
export function RouteEffects() {
  const { pathname, key } = useLocation();
  const navigationType = useNavigationType();
  const [announcement, setAnnouncement] = useState('');
  const positions = useRef(new Map<string, number>());
  const currentKey = useRef(key);
  const changedAt = useRef(0);
  const previousPath = useRef<string | null>(null);
  const previousKey = useRef<string | null>(null);

  const title = titleFor(pathname);
  useEffect(() => {
    document.title = `${title} · Train Booking`;
  }, [title]);

  // Remember where each history entry was scrolled to.
  useEffect(() => {
    if ('scrollRestoration' in history) history.scrollRestoration = 'manual';
    const onScroll = () => {
      if (Date.now() - changedAt.current > SETTLE_MS) positions.current.set(currentKey.current, window.scrollY);
    };
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  // Scroll: restore on Back and Forward, go to the top on a new page of the app. A change of the query string only (a date chip) keeps the place.
  useEffect(() => {
    if (previousKey.current === key) return undefined; // StrictMode runs effects twice
    const samePath = previousPath.current === pathname;
    const first = previousKey.current === null;
    previousKey.current = key;
    currentKey.current = key;
    changedAt.current = Date.now();
    if (first) return undefined;
    if (navigationType !== 'POP') {
      if (!samePath) window.scrollTo(0, 0);
      return undefined;
    }
    const target = positions.current.get(key) ?? 0;
    const started = Date.now();
    const attempt = window.setInterval(() => {
      window.scrollTo(0, target);
      const reached = Math.abs(window.scrollY - target) < 2;
      if (reached || Date.now() - started > RESTORE_FOR_MS) window.clearInterval(attempt);
    }, 60);
    window.scrollTo(0, target);
    return () => window.clearInterval(attempt);
  }, [key, pathname, navigationType]);

  // Focus and announcement, only when the page (not just its query string) changes.
  useEffect(() => {
    if (previousPath.current === pathname) return undefined;
    const initial = previousPath.current === null;
    previousPath.current = pathname;
    if (initial) return undefined;
    setAnnouncement(title);
    const focusHeading = () => {
      const heading = document.querySelector<HTMLElement>('#main h1');
      if (!heading) return false;
      heading.tabIndex = -1;
      heading.focus({ preventScroll: true });
      return true;
    };
    if (focusHeading()) return undefined;
    // The page loads its content first: focus the h1 as soon as it exists.
    const main = document.getElementById('main');
    if (!main) return undefined;
    const observer = new MutationObserver(() => focusHeading() && observer.disconnect());
    observer.observe(main, { childList: true, subtree: true });
    const stop = window.setTimeout(() => observer.disconnect(), 2000);
    return () => {
      observer.disconnect();
      window.clearTimeout(stop);
    };
  }, [pathname, title]);

  return (
    <div className="sr-only" role="status" aria-live="polite">
      {announcement}
    </div>
  );
}
