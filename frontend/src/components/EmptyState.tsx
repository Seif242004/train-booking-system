import type { ReactNode } from 'react';

interface Props {
  title: string;
  /** One sentence that says what to do next. */
  text?: ReactNode;
  /** The next action, for example a button or a link. */
  action?: ReactNode;
}

/** An empty list or an empty result: says what is missing and points to the next action. */
export function EmptyState({ title, text, action }: Props) {
  return (
    <div className="empty-state">
      <h2>{title}</h2>
      {text && <p>{text}</p>}
      {action}
    </div>
  );
}
