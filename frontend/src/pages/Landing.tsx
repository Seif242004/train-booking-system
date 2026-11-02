import { useEffect, useState, type ReactNode } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import type { Station } from '../adminTypes';
import { gatewayRequest } from '../api';
import { useAuth } from '../auth/AuthContext';
import { HeroPhoto } from '../components/HeroPhoto';
import { SearchForm, type SearchValues } from '../components/SearchForm';
import { loadRecentSearches } from '../recentSearches';
import { todayLocal } from '../time';

const icon = (children: ReactNode) => (
  <svg aria-hidden="true" width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    {children}
  </svg>
);

const BENEFITS: { title: string; text: string; icon: ReactNode }[] = [
  {
    title: 'Pick your exact seat',
    text: 'See every coach and choose the seat you want, not only a class.',
    icon: icon(<><rect x="5" y="4" width="14" height="12" rx="3" /><path d="M8 16v3M16 16v3M5 12h14" /></>),
  },
  {
    title: 'Held for 5 minutes',
    text: 'The hold starts with your first pick, so nobody else can take your seat while you pay.',
    icon: icon(<><circle cx="12" cy="13" r="8" /><path d="M12 9v4l3 2M9 3h6" /></>),
  },
  {
    title: 'Cancel and get refunded',
    text: 'Cancel a confirmed booking before departure and the full amount goes back to your card.',
    icon: icon(<><path d="M4 12a8 8 0 108-8H7" /><path d="M10 1L7 4l3 3" /></>),
  },
  {
    title: 'Ticket in your inbox',
    text: 'We email your ticket as soon as the payment goes through.',
    icon: icon(<><rect x="3" y="5" width="18" height="14" rx="3" /><path d="M4 7l8 6 8-6" /></>),
  },
];

const STEPS = [
  { title: 'Search', text: 'Pick your stations and day. Every trip shows all its stops, so you can see if yours is on the way.' },
  { title: 'Choose your seats', text: 'Open the coach plan and tap the seats you want. They are held for 5 minutes from your first pick.' },
  { title: 'Pay and travel', text: 'Pay by card. Your ticket appears right away and arrives in your email.' },
];

/**
 * The landing page ("/"): the hero photo with the search card, the three steps, and what you get. The search card hands over to the
 * search page. A signed-in visitor picks stations from the list; a signed-out one types the names and is sent to Keycloak first.
 */
export function Landing() {
  const { status, authenticated, error, login, register } = useAuth();
  const navigate = useNavigate();
  const [stations, setStations] = useState<Station[] | null>(null);
  // The last three searches of this browser, as one-tap chips.
  const [recent] = useState(loadRecentSearches);

  useEffect(() => {
    if (!authenticated) return;
    void (async () => {
      const result = await gatewayRequest('GET', '/stations');
      if (result.status === 200) setStations(result.body as Station[]);
    })();
  }, [authenticated]);

  function search(values: SearchValues) {
    const query = `?${new URLSearchParams({ from: values.from, to: values.to, date: values.date })}`;
    if (authenticated) navigate(`/search${query}`);
    else login(`${window.location.origin}/search${query}`);
  }

  return (
    <>
      <section className="hero on-dark" aria-labelledby="hero-title">
        <HeroPhoto />
        <div className="hero-content container">
          <h1 id="hero-title" className="hero-title">
            <span className="line-in" style={{ animationDelay: '0.05s' }}>Egypt by train,</span>
            <span className="line-in" style={{ animationDelay: '0.2s' }}>booked in a minute.</span>
          </h1>
          <p className="hero-lead line-in" style={{ animationDelay: '0.4s' }}>
            Choose your stations, pick the exact seat you want and pay online.
          </p>
          {error && <p className="notice notice-error hero-notice">{error}</p>}
          {status === 'ready' && !authenticated && !error && (
            <p className="hero-account line-in" style={{ animationDelay: '0.5s' }}>
              New here?{' '}
              <button type="button" className="link-button" onClick={register}>
                Create an account
              </button>
            </p>
          )}
        </div>
      </section>

      <div className="container search-overlap">
        <div className="search-card">
          <SearchForm hero idPrefix="home" stations={stations} initial={{ from: '', to: '', date: todayLocal() }} onSubmit={search} />
          {recent.length > 0 && (
            <div className="recent-searches">
              <span id="recent-label">Recent searches</span>
              <ul aria-labelledby="recent-label">
                {recent.map((r) => (
                  <li key={`${r.from}-${r.to}`}>
                    <button type="button" className="chip" onClick={() => search({ from: r.from, to: r.to, date: r.date >= todayLocal() ? r.date : todayLocal() })}>
                      {r.fromName} → {r.toName}
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      </div>

      <section className="steps container" aria-labelledby="steps-title">
        <h2 id="steps-title" className="section-title">Three steps from search to ticket</h2>
        <ol className="step-list">
          {STEPS.map((step, index) => (
            <li key={step.title}>
              <div className="step-track" aria-hidden="true">
                <span className={index === 0 ? 'step-dot step-dot-filled' : 'step-dot'} />
                {index < STEPS.length - 1 && <span className="step-bar" />}
              </div>
              <h3>{step.title}</h3>
              <p>{step.text}</p>
            </li>
          ))}
        </ol>
      </section>

      <section className="benefits dark on-dark" aria-labelledby="benefits-title">
        <div className="container benefits-inner">
          <div className="benefits-intro">
            <h2 id="benefits-title" className="section-title">Your seat stays yours while you pay</h2>
            <p>And if plans change, you can cancel without calling anyone.</p>
            <Link to="/search" className="button button-primary">Find a train</Link>
          </div>
          <ul className="benefit-list">
            {BENEFITS.map((benefit) => (
              <li key={benefit.title}>
                <span className="benefit-icon">{benefit.icon}</span>
                <div>
                  <h3>{benefit.title}</h3>
                  <p>{benefit.text}</p>
                </div>
              </li>
            ))}
          </ul>
        </div>
      </section>
    </>
  );
}
