import { Link } from 'react-router-dom';
import type { Back } from '../routes';

/** The way back at the top of a page: "← Back to results". Every page below the landing page has one (next to the logo in the header). */
export function PageNav({ back }: { back: Back }) {
  return (
    <nav className="page-nav" aria-label="Back">
      <Link to={back.to}>
        <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
          <path d="M15 5l-7 7 7 7" />
        </svg>
        {back.label}
      </Link>
    </nav>
  );
}
