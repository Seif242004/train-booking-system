import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';

interface Props {
  title: string;
  /** A link back to the list this page belongs to (for the pages of one train or one route). */
  back?: { to: string; label: string };
  /** The main action of the page, for example the "Add station" button. */
  action?: ReactNode;
  children?: ReactNode;
}

/** The top of an admin page: the title, the main action on the right, and an optional line of text below. */
export function AdminHeader({ title, back, action, children }: Props) {
  return (
    <header className="admin-page-head">
      {back && (
        <Link className="admin-back" to={back.to}>
          ← {back.label}
        </Link>
      )}
      <div className="admin-page-title">
        <h1>{title}</h1>
        {action}
      </div>
      {children}
    </header>
  );
}
