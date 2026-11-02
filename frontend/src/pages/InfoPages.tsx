import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { config } from '../config';

// Help and policy pages. They describe only what the system really does (docs/SRS.md, docs/ARCHITECTURE.md). The terms and the privacy
// notice are drafts that need the supervisor's review before any real use.

function DocPage({ title, lead, draft = false, children }: { title: string; lead: string; draft?: boolean; children: ReactNode }) {
  return (
    <section className="doc-page">
      <h1>{title}</h1>
      <p className="doc-lead">{lead}</p>
      {draft && (
        <p className="doc-draft" role="note">
          Draft for review. This text describes how the system works today and has not been reviewed for real use.
        </p>
      )}
      {children}
      <nav className="doc-links" aria-label="More help">
        <Link to="/help">Help and FAQ</Link>
        <Link to="/refunds">Cancellations and refunds</Link>
        <Link to="/terms">Terms of use</Link>
        <Link to="/privacy">Privacy notice</Link>
        <Link to="/contact">Contact</Link>
      </nav>
    </section>
  );
}

const FAQ: { q: string; a: ReactNode }[] = [
  {
    q: 'How does booking work?',
    a: (
      <>
        Search for a trip, open the seat map, tap the seats you want, then pay by card. Your ticket appears on screen and is emailed to you.
        You need an account to search and book; signing in happens on our sign-in page.
      </>
    ),
  },
  {
    q: 'What is the 5-minute hold?',
    a: (
      <>
        The moment you pick your first seat or standing place, we hold it for you for 5 minutes. Seats you pick afterwards join the same hold and do
        not add time. If you tap a seat again it is released at once, and releasing everything ends the hold. If the 5 minutes run out before you
        pay, the seats are released, you are not charged and you return to the seat map.
      </>
    ),
  },
  {
    q: 'What are the seat classes and standing places?',
    a: (
      <>
        Coaches are First, Second or Third class, and each fare is in Egyptian pounds (EGP) for your journey. Some coaches also have standing
        places: you choose how many, and they cost the fare of that coach&apos;s class. A seat is yours for your whole journey, from where you board
        to where you get off.
      </>
    ),
  },
  {
    q: 'How do I pay?',
    a: (
      <>
        Payment is by card, in an embedded form from Stripe. Card details go straight to Stripe and never touch our servers. Your booking is
        confirmed as soon as Stripe tells us the payment succeeded. If a payment is declined, you can try again while your seats are still held.
      </>
    ),
  },
  {
    q: 'Where is my ticket?',
    a: (
      <>
        After payment, the ticket page shows your booking, and we email a PDF ticket with a QR code. It can take a moment to arrive. You can ask for
        it again from the ticket page or from <Link to="/bookings">My bookings</Link>. A failed email never cancels your booking.
      </>
    ),
  },
  {
    q: 'Can I cancel?',
    a: (
      <>
        Yes. You can cancel your own confirmed booking from <Link to="/bookings">My bookings</Link> until the train leaves your boarding stop.
        Read the details in <Link to="/refunds">Cancellations and refunds</Link>.
      </>
    ),
  },
  {
    q: 'How do refunds work?',
    a: (
      <>
        A cancelled booking is refunded in full to the card you paid with. The refund shows as &quot;Refund in progress&quot; first and
        &quot;Refunded in full&quot; when it is done.
      </>
    ),
  },
];

export function HelpPage() {
  return (
    <DocPage title="Help and FAQ" lead="Short answers about booking, paying and travelling.">
      <div className="faq">
        {FAQ.map((item) => (
          <details key={item.q}>
            <summary>{item.q}</summary>
            <p>{item.a}</p>
          </details>
        ))}
      </div>
    </DocPage>
  );
}

export function RefundsPage() {
  return (
    <DocPage title="Cancellations and refunds" lead="What happens when you cancel, and when your money comes back.">
      <h2>Cancelling a booking</h2>
      <ul>
        <li>You can cancel your own <strong>confirmed</strong> booking yourself, from <Link to="/bookings">My bookings</Link>. It is instant and needs no approval.</li>
        <li>You can cancel until your train leaves <strong>your boarding stop</strong>. After that, the booking can no longer be cancelled.</li>
        <li>Support can also cancel a booking for you if you ask. They enter a reason, and it works the same way as cancelling it yourself.</li>
        <li>If we cancel a whole trip, every confirmed booking on it is cancelled and refunded.</li>
      </ul>
      <h2>Refunds</h2>
      <ul>
        <li>A cancellation is refunded <strong>in full</strong> to the card you paid with. There are no partial refunds and no fees.</li>
        <li>The refund status is shown on your booking, separate from the booking status: <em>Refund in progress</em>, <em>Refunded in full</em> or <em>Refund failed</em>. If it failed, please <Link to="/contact">contact us</Link>.</li>
        <li>You get an email when a booking is cancelled and another when the refund is done. Your ticket&apos;s QR code stops working once the booking is cancelled.</li>
      </ul>
      <h2>If you do not pay in time</h2>
      <p>
        If the 5-minute hold runs out, your seats are released and you are never charged. There is nothing to refund in that case.
      </p>
    </DocPage>
  );
}

export function TermsPage() {
  return (
    <DocPage title="Terms of use" lead="The rules for using this booking service." draft>
      <h2>Your account</h2>
      <p>You need an account to book. Your account is managed by our sign-in service. Keep your password private; you are responsible for what happens under your account.</p>
      <h2>Bookings and tickets</h2>
      <p>A booking is confirmed when your card payment succeeds. Seats you pick are held for 5 minutes. The ticket for a booking is valid for the trip, coach and seats shown on it, and stops being valid if the booking is cancelled.</p>
      <h2>Prices and payment</h2>
      <p>Prices are in Egyptian pounds (EGP) and are calculated by our servers when you book. Card payments are processed by Stripe.</p>
      <h2>Cancellation and refunds</h2>
      <p>See <Link to="/refunds">Cancellations and refunds</Link>. A cancelled booking is refunded in full.</p>
      <h2>Changes to the service</h2>
      <p>A trip can be cancelled by the operator. In that case your booking is cancelled and refunded in full.</p>
      <h2>Contact</h2>
      <p>Questions about these terms: <Link to="/contact">Contact</Link>.</p>
    </DocPage>
  );
}

export function PrivacyPage() {
  return (
    <DocPage title="Privacy notice" lead="What we keep about you and why." draft>
      <h2>What we keep</h2>
      <ul>
        <li><strong>Account:</strong> your name, email address and sign-in details are stored by our sign-in service. We never see your password.</li>
        <li><strong>Bookings:</strong> the trips, seats, prices and times of your bookings, with your name and email address so we can send you your ticket.</li>
        <li><strong>Tickets:</strong> a PDF ticket with a QR code for each confirmed booking.</li>
        <li><strong>Payments:</strong> card payments are handled by Stripe. Card details go directly to Stripe and are not stored by us. We keep the payment and refund status of each booking.</li>
        <li><strong>Searches:</strong> your last searches are remembered in your own browser only, to save you typing.</li>
      </ul>
      <h2>Why</h2>
      <p>To let you book, pay, travel, cancel and be refunded, and to send you emails about those things.</p>
      <h2>Emails</h2>
      <p>We send emails for your ticket, a cancellation and a refund. We do not send marketing emails.</p>
      <h2>Who sees it</h2>
      <p>Support staff can look up a booking to help you. Stripe processes your payments. We use a service to deliver email.</p>
      <h2>Your choices</h2>
      <p>You can change your name, email and password in your account. For anything else, <Link to="/contact">contact us</Link>.</p>
    </DocPage>
  );
}

export function ContactPage() {
  const email = config.supportEmail;
  const placeholder = email.endsWith('.invalid');
  return (
    <DocPage title="Contact" lead="Need help with a booking or a refund? Write to us.">
      <h2>Email</h2>
      <p>
        <a href={`mailto:${email}`}>{email}</a>
      </p>
      {placeholder && (
        <p className="doc-draft" role="note">
          This is a placeholder address. Set VITE_SUPPORT_EMAIL to the real support address before real use.
        </p>
      )}
      <h2>What to include</h2>
      <p>Your booking reference (shown on your ticket and in <Link to="/bookings">My bookings</Link>) and the email address of your account.</p>
    </DocPage>
  );
}
