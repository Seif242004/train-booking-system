import { useCallback, useEffect, useState } from 'react';
import { gatewayDownload } from '../api';

const AUTO_RETRIES = 4; // the ticket is made a moment after the payment: ask again a few times before showing a failure
const RETRY_MS = 3000;

/**
 * The QR code of the ticket (the same code as in the PDF). An <img> cannot send the Authorization header, so the image is fetched with the
 * token, shown from an object URL, and the URL is revoked when the component goes away. A failure never hides the rest of the ticket.
 */
export function TicketQr({ bookingId, reference }: { bookingId: number; reference: string }) {
  const [src, setSrc] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0); // a manual "Try again" bumps this

  const load = useCallback(
    async (isCurrent: () => boolean) => {
      for (let tries = 0; tries <= AUTO_RETRIES; tries += 1) {
        const result = await gatewayDownload(`/bookings/${bookingId}/ticket/qr`);
        if (!isCurrent()) return;
        if (result.ok) {
          setSrc(URL.createObjectURL(result.blob));
          setFailed(false);
          return;
        }
        if (tries < AUTO_RETRIES) await new Promise((resolve) => window.setTimeout(resolve, RETRY_MS));
        if (!isCurrent()) return;
      }
      setFailed(true);
    },
    [bookingId],
  );

  useEffect(() => {
    let current = true;
    setFailed(false);
    void load(() => current);
    return () => {
      current = false;
    };
  }, [load, attempt]);

  // Revoke the object URL when it is replaced and when the ticket page closes.
  useEffect(() => {
    if (!src) return undefined;
    return () => URL.revokeObjectURL(src);
  }, [src]);

  return (
    <div className="ticket-qr">
      {src ? (
        <img className="ticket-qr-image" src={src} alt={`QR code for ticket ${reference}`} />
      ) : failed ? (
        <div className="ticket-qr-failed" role="alert">
          <p>We could not load the QR code. Try again.</p>
          <button type="button" className="button" onClick={() => { setSrc(null); setAttempt((n) => n + 1); }}>Try again</button>
        </div>
      ) : (
        <div className="ticket-qr-skeleton skeleton" role="status" aria-busy="true"><span className="sr-only">Loading the QR code…</span></div>
      )}
      <p className="ticket-qr-ref mono">{reference}</p>
      <p className="ticket-qr-help">Show this code at the gate. It is the same code as in the PDF.</p>
    </div>
  );
}
