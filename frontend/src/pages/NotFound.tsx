import { Link, useLocation } from 'react-router-dom';
import { EmptyState } from '../components/EmptyState';

/** Any address the app does not know: says so plainly and offers the two places most people want. */
export function NotFound() {
  const { pathname } = useLocation();
  return (
    <section className="not-found">
      <p className="not-found-code" aria-hidden="true">404</p>
      <EmptyState
        title="We cannot find that page"
        text={<>There is nothing at <span className="mono">{pathname}</span>. The address may have a typing mistake, or the page may have moved.</>}
        action={
          <div className="actions">
            <Link className="button button-primary" to="/search">Search trains</Link>
            <Link className="button" to="/">Go to the home page</Link>
          </div>
        }
      />
    </section>
  );
}
