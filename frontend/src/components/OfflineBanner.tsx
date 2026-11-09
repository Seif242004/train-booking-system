import { useEffect, useState } from 'react';

/** A slim bar at the very top while the browser has no network. It disappears by itself when the connection returns. */
// The effect adds two browser event listeners once and removes them on unmount. navigator.onLine is only a hint from the browser.
export function OfflineBanner() {
  const [online, setOnline] = useState(() => navigator.onLine);
  useEffect(() => {
    const on = () => setOnline(true);
    const off = () => setOnline(false);
    window.addEventListener('online', on);
    window.addEventListener('offline', off);
    return () => {
      window.removeEventListener('online', on);
      window.removeEventListener('offline', off);
    };
  }, []);
  if (online) return null;
  return (
    <div className="offline-banner" role="status">
      You are offline. Changes will not go through until the connection is back.
    </div>
  );
}
