import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import './styles/tokens.css';
import './styles/base.css';
import './styles/components.css';
import './styles/shell.css';
import './styles/nav.css';
import './styles/pages/landing.css';
import './styles/pages/search.css';
import './styles/pages/booking.css';
import './styles/pages/seatmap.css';
import './styles/pages/search-extras.css';
import './styles/pages/bookings.css';
import './styles/pages/admin.css';
import './styles/pages/account.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
