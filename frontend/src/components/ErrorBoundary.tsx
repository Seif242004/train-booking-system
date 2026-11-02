import { Component, type ErrorInfo, type ReactNode } from 'react';

/** The last safety net: a page that crashes while drawing shows this instead of a blank screen. A plain link reloads the app from the home page. */
export class ErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Page error', error, info.componentStack);
  }

  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <main className="crash-page" id="main" tabIndex={-1}>
        <div className="empty-state">
          <h1>Something went wrong on our side</h1>
          <p>The page could not be shown. Your bookings and any held seats are safe. Go back to the home page and try again.</p>
          {/* A real link (not the router): it starts the app fresh, so the broken page is gone. */}
          <a className="button button-primary" href="/">Go to the home page</a>
        </div>
      </main>
    );
  }
}
