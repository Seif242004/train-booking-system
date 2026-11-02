import { matchPath } from 'react-router-dom';
import { lastSearchQuery } from './recentSearches';

export interface Back {
  to: string;
  label: string;
}

interface RouteInfo {
  pattern: string;
  /** The document title and the text announced to screen readers after a navigation. */
  title: string;
  /** Where the visible "Back" link goes (docs/design/FLOW.md). Pages whose way back depends on loaded data draw their own. */
  back?: () => Back;
}

const home = (): Back => ({ to: '/', label: 'Back to home' });

// The first match wins. Keep in step with App.tsx and docs/design/FLOW.md.
const ROUTES: RouteInfo[] = [
  { pattern: '/', title: 'Train tickets in Egypt' },
  { pattern: '/search', title: 'Search trains', back: home },
  { pattern: '/trips/:id/seats', title: 'Choose your seats', back: () => ({ to: `/search${lastSearchQuery() ? `?${lastSearchQuery()}` : ''}`, label: 'Back to results' }) },
  { pattern: '/bookings/:id/pay', title: 'Payment' },
  { pattern: '/bookings/:id/ticket', title: 'Your ticket', back: () => ({ to: '/bookings', label: 'Back to my bookings' }) },
  { pattern: '/bookings', title: 'My bookings', back: home },
  { pattern: '/account', title: 'Your account', back: home },
  { pattern: '/help', title: 'Help and FAQ', back: home },
  { pattern: '/refunds', title: 'Cancellations and refunds', back: home },
  { pattern: '/terms', title: 'Terms of use', back: home },
  { pattern: '/privacy', title: 'Privacy notice', back: home },
  { pattern: '/contact', title: 'Contact', back: home },
  { pattern: '/admin', title: 'Admin overview' },
  { pattern: '/admin/stations', title: 'Admin: stations' },
  { pattern: '/admin/trains', title: 'Admin: trains' },
  { pattern: '/admin/trains/:id', title: 'Admin: train' },
  { pattern: '/admin/routes', title: 'Admin: routes and fares' },
  { pattern: '/admin/routes/:id/fares', title: 'Admin: route fares' },
  { pattern: '/admin/trips', title: 'Admin: trips' },
  { pattern: '/admin/support', title: 'Admin: support' },
];

function find(pathname: string): RouteInfo | undefined {
  return ROUTES.find((r) => matchPath({ path: r.pattern, end: true }, pathname));
}

export function titleFor(pathname: string): string {
  return find(pathname)?.title ?? 'Page not found';
}

export function backFor(pathname: string): Back | null {
  return find(pathname)?.back?.() ?? null;
}
