import type { ReactNode } from 'react';

interface Props {
  /** What happened, in words that do not blame the user. */
  message: string;
  /** How to recover: a retry handler and/or another link. */
  onRetry?: () => void;
  action?: ReactNode;
}

/** The one pattern for a failed load: says what happened and offers a way out (retry, or somewhere else to go). */
export function ErrorState({ message, onRetry, action }: Props) {
  return (
    <div className="error-state" role="alert">
      <p className="error-state-title">We could not load this</p>
      <p>{message}</p>
      <div className="actions">
        {onRetry && <button type="button" className="button button-primary" onClick={onRetry}>Try again</button>}
        {action}
      </div>
    </div>
  );
}
