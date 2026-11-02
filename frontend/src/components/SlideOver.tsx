import { useEffect, useId, useRef, type ReactNode } from 'react';
import { createPortal } from 'react-dom';

interface Props {
  open: boolean;
  title: string;
  onClose: () => void;
  /** Wider panel for forms with a table inside (route stops, trip times). */
  wide?: boolean;
  children: ReactNode;
}

const FOCUSABLE = 'a[href], button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])';

/**
 * A panel that slides in from the right for adding or editing one thing, with the list still behind it. It is a modal dialog:
 * focus moves into it, Tab stays inside, Escape or the backdrop or the close button closes it, and focus goes back to the button that
 * opened it. The page behind does not scroll while it is open.
 */
export function SlideOver({ open, title, onClose, wide = false, children }: Props) {
  const titleId = useId();
  const panelRef = useRef<HTMLDivElement>(null);
  const closeRef = useRef(onClose);
  closeRef.current = onClose;

  useEffect(() => {
    if (!open) return undefined;
    const opener = document.activeElement as HTMLElement | null;
    const panel = panelRef.current;
    // Focus the first field, or the close button when there is none.
    const firstField = panel?.querySelector<HTMLElement>('.slide-body input:not(:disabled), .slide-body select:not(:disabled), .slide-body textarea:not(:disabled)');
    (firstField ?? panel?.querySelector<HTMLElement>('.slide-close'))?.focus();

    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.stopPropagation();
        closeRef.current();
        return;
      }
      if (event.key !== 'Tab' || !panel) return;
      const items = [...panel.querySelectorAll<HTMLElement>(FOCUSABLE)].filter((el) => el.offsetParent !== null);
      if (items.length === 0) return;
      const first = items[0];
      const last = items[items.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.body.style.overflow = previousOverflow;
      opener?.focus();
    };
  }, [open]);

  if (!open) return null;
  return createPortal(
    <div className="slide-backdrop" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <div ref={panelRef} role="dialog" aria-modal="true" aria-labelledby={titleId} className={wide ? 'slide-panel slide-panel-wide' : 'slide-panel'}>
        <div className="slide-head">
          <h2 id={titleId}>{title}</h2>
          <button type="button" className="icon-button slide-close" aria-label="Close panel" onClick={onClose}>
            <svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round">
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </div>
        <div className="slide-body">{children}</div>
      </div>
    </div>,
    document.body,
  );
}
