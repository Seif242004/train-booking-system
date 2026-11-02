import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';

type Tone = 'ok' | 'error';
interface Toast {
  id: number;
  text: string;
  tone: Tone;
}

interface ToastApi {
  /** Says that something finished. Use the same words as the button that started it ("Booking cancelled"). */
  show: (text: string, tone?: Tone) => void;
}

const ToastContext = createContext<ToastApi | null>(null);

const SHOW_MS = 6000;

/**
 * Short messages for finished actions. They sit in a polite live region, so a screen reader announces them without moving the focus, they
 * never take the focus themselves, and each has a close button that can be reached with the keyboard. They go away by themselves.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const next = useRef(1);

  const dismiss = useCallback((id: number) => setToasts((now) => now.filter((t) => t.id !== id)), []);
  const show = useCallback(
    (text: string, tone: Tone = 'ok') => {
      const id = next.current++;
      setToasts((now) => [...now.slice(-2), { id, text, tone }]);
      window.setTimeout(() => dismiss(id), SHOW_MS);
    },
    [dismiss],
  );
  const api = useMemo(() => ({ show }), [show]);

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className="toast-region" role="status" aria-live="polite" aria-relevant="additions">
        {toasts.map((t) => (
          <p key={t.id} className={`toast toast-${t.tone}`}>
            <span>{t.text}</span>
            <button type="button" className="toast-close" aria-label="Dismiss message" onClick={() => dismiss(t.id)}>
              <svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round">
                <path d="M6 6l12 12M18 6L6 18" />
              </svg>
            </button>
          </p>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastApi {
  const value = useContext(ToastContext);
  if (!value) throw new Error('useToast must be used inside <ToastProvider>');
  return value;
}
