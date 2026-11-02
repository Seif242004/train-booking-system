import { CLASS_LABEL } from '../adminTypes';
import type { SeatMapCoach } from '../seatMapTypes';

interface Props {
  coaches: SeatMapCoach[];
  currentId: number | undefined;
  /** Coaches in which the passenger holds something. */
  heldIn: (coach: SeatMapCoach) => boolean;
  onSelect: (coach: SeatMapCoach) => void;
}

/** What is left in a coach, in words. */
export function coachLeft(coach: SeatMapCoach): string {
  const free = coach.seats.filter((seat) => seat.state === 'AVAILABLE').length;
  if (free > 0) return `${free} left`;
  if (coach.standing.remaining > 0) return `${coach.standing.remaining} standing`;
  return 'Full';
}

/** The whole train as small cars, front first, with what is left in each. The current coach is marked; every car is a button. */
export function TrainStrip({ coaches, currentId, heldIn, onSelect }: Props) {
  return (
    <div className="train-strip" role="group" aria-label="Coaches of the train, front of the train first">
      <ol className="train-strip-list">
        <li className="train-engine" aria-hidden="true">
          <svg width="44" height="44" viewBox="0 0 44 44" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
            <path d="M6 34V18a10 10 0 0110-10h22v26z" />
            <path d="M14 15h10v8H14zM4 34h38" />
          </svg>
        </li>
        {coaches.map((coach) => {
          const current = coach.id === currentId;
          const left = coachLeft(coach);
          return (
            <li key={coach.id}>
              <button
                type="button"
                className={current ? 'train-car is-current' : 'train-car'}
                aria-pressed={current}
                aria-label={`Coach ${coach.code}, ${CLASS_LABEL[coach.class]} class, ${left}${heldIn(coach) ? ', you hold places here' : ''}`}
                onClick={() => onSelect(coach)}
              >
                <span className="train-car-body" aria-hidden="true">
                  <i /><i /><i /><i />
                </span>
                <span className="train-car-code">{coach.code}{heldIn(coach) && <span aria-hidden="true"> ✓</span>}</span>
                <span className="train-car-left">{left}</span>
                <span className="train-car-class">{CLASS_LABEL[coach.class]}</span>
              </button>
            </li>
          );
        })}
      </ol>
    </div>
  );
}
