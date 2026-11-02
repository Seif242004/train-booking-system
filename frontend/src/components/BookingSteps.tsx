import { Link } from 'react-router-dom';

const STEPS = ['Search', 'Seats', 'Payment', 'Ticket'];

type Step = 'seats' | 'payment' | 'ticket';
const INDEX: Record<Step, number> = { seats: 1, payment: 2, ticket: 3 };

export interface StepLinks {
  /** Targets of the finished steps. A step without a target is plain text (a confirmed booking cannot go back to the seats). */
  search?: string;
  seats?: string;
  payment?: string;
}

/** The four steps of a booking, with the current one marked (aria-current) and the finished ones ticked and, when they have a target, linked. */
export function BookingSteps({ current, links = {} }: { current: Step; links?: StepLinks }) {
  const at = INDEX[current];
  const targets = [links.search, links.seats, links.payment];
  return (
    <ol className="booking-steps" aria-label="Booking steps">
      {STEPS.map((label, index) => {
        const state = index < at ? 'done' : index === at ? 'current' : 'todo';
        const target = state === 'done' ? targets[index] : undefined;
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
            {target ? <Link to={target}>{label}</Link> : label}
            {state === 'done' && <span className="sr-only"> (done)</span>}
          </li>
        );
      })}
    </ol>
  );
}
