import { useDelayedFlag } from '../useDelayedFlag';

/**
 * What an admin list shows while it loads: after 300 ms a few grey rows with a slow light sweep (before that, nothing: a placeholder that
 * flashes for a moment is worse than none). Screen readers get the words "Loading" at once.
 */
export function ListSkeleton({ rows = 4, label = 'Loading…' }: { rows?: number; label?: string }) {
  const visible = useDelayedFlag(true);
  return (
    <div aria-busy="true" role="status">
      <p className="sr-only">{label}</p>
      {visible && (
        <div className="list-skeleton" aria-hidden="true">
          {Array.from({ length: rows }, (_, index) => (
            <div key={index} className="skeleton" style={{ height: 56 }} />
          ))}
        </div>
      )}
    </div>
  );
}
