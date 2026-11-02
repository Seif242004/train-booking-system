import { useEffect, useState } from 'react';

/**
 * True only once `active` has stayed true for `delayMs`. Used for loading placeholders: a wait shorter than about 300 ms shows nothing
 * (a skeleton that flashes for a moment is worse than none).
 */
export function useDelayedFlag(active: boolean, delayMs = 300): boolean {
  const [shown, setShown] = useState(false);
  useEffect(() => {
    if (!active) {
      setShown(false);
      return undefined;
    }
    const timer = window.setTimeout(() => setShown(true), delayMs);
    return () => window.clearTimeout(timer);
  }, [active, delayMs]);
  return shown;
}
