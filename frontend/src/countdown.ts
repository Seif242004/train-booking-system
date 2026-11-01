/**
 * The countdown of a hold is driven by the server's time (SRS FR-H4): the server sends when the hold ends ({@code expiresAt})
 * and what its own clock said when it answered ({@code serverTime}). The difference between that and the browser's clock at the
 * moment the answer arrived is the skew, and it is taken off every reading, so a browser whose clock is minutes off still shows
 * the right time left. The countdown only disables the form: it is the server that decides when the hold is over.
 */
export interface Countdown {
  expiresAtMs: number;
  /** Browser clock minus server clock, in milliseconds, measured when the answer arrived. */
  skewMs: number;
}

export function startCountdown(expiresAt: string, serverTime: string, receivedAtMs: number): Countdown {
  return { expiresAtMs: Date.parse(expiresAt), skewMs: receivedAtMs - Date.parse(serverTime) };
}

/** Milliseconds left, never below 0. */
export function remainingMs(countdown: Countdown, nowMs: number): number {
  return Math.max(0, countdown.expiresAtMs - (nowMs - countdown.skewMs));
}

/** 5:00, 4:59 ... 0:00 (rounded up, so it shows 0:00 only when the time is really over). */
export function formatRemaining(ms: number): string {
  const seconds = Math.ceil(ms / 1000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}
