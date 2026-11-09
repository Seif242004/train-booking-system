// Entry point of the web app (built by Vite, served by nginx in Compose on port 5173).
// It loads the global CSS once, in a fixed order (tokens first, because every later file reads the variables they define),
// then mounts the React tree into <div id="root"> of index.html.
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import './styles/tokens.css';
import './styles/base.css';
import './styles/components.css';
import './styles/shell.css';
import './styles/nav.css';
import './styles/feedback.css';
import './styles/pages/landing.css';
import './styles/pages/search.css';
import './styles/pages/booking.css';
import './styles/pages/seatmap.css';
import './styles/pages/search-extras.css';
import './styles/pages/bookings.css';
import './styles/pages/admin.css';
import './styles/pages/account.css';
import './styles/pages/board.css';

// StrictMode is a development-only helper: it runs effects twice to expose missing clean-up code. It renders nothing itself.
// The "!" tells TypeScript that the element exists (index.html always contains it).
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
