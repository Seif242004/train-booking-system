const STEPS = ['Search', 'Seats', 'Payment', 'Ticket'];

type Step = 'seats' | 'payment' | 'ticket';
const INDEX: Record<Step, number> = { seats: 1, payment: 2, ticket: 3 };

/** The four steps of a booking, with the current one marked (aria-current) and the finished ones ticked. */
export function BookingSteps({ current }: { current: Step }) {
  const at = INDEX[current];
  return (
    <ol className="booking-steps" aria-label="Booking steps">
      {STEPS.map((label, index) => {
        const state = index < at ? 'done' : index === at ? 'current' : 'todo';
        return (
          <li key={label} className={`booking-step booking-step-${state}`} aria-current={state === 'current' ? 'step' : undefined}>
            <span className="booking-step-badge" aria-hidden="true">
              {state === 'done' ? (
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M5 12l5 5 9-10" />
                </svg>
              ) : (
                index + 1
              )}
            </span>
            {label}
            {state === 'done' && <span className="sr-only"> (done)</span>}
          </li>
        );
      })}
    </ol>
  );
}
