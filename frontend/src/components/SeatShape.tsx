export type SeatLook = 'available' | 'mine' | 'held' | 'booked';

/**
 * A seat seen from above: headrest, backrest, cushion and two armrests. The look is set by CSS from the class; the mark inside
 * (check, clock, cross) means the state never depends on colour alone.
 */
export function SeatShape({ look }: { look: SeatLook }) {
  return (
    <svg className={`seat-shape seat-shape-${look}`} viewBox="0 0 44 48" aria-hidden="true" focusable="false">
      <rect className="s-arm" x="1.5" y="19" width="6" height="21" rx="3" />
      <rect className="s-arm" x="36.5" y="19" width="6" height="21" rx="3" />
      <rect className="s-back" x="8" y="4" width="28" height="26" rx="9" />
      <rect className="s-head" x="14" y="1" width="16" height="8" rx="4" />
      <rect className="s-cushion" x="8" y="28" width="28" height="16" rx="6" />
      {look === 'mine' && <path className="s-mark" d="M15.5 19l5 5 8.5-10" />}
      {look === 'booked' && <path className="s-mark" d="M16 14l12 11M28 14L16 25" />}
      {look === 'held' && (
        <>
          <circle className="s-mark" cx="22" cy="19" r="7" />
          <path className="s-mark" d="M22 15v4.5l3 1.5" />
        </>
      )}
    </svg>
  );
}
