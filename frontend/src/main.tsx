import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import './styles/tokens.css';
import './styles/base.css';
import './styles/legacy.css';
import './styles/components.css';
import './styles/shell.css';
import './styles/pages/landing.css';
import './styles/pages/search.css';
import './styles/pages/booking.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
