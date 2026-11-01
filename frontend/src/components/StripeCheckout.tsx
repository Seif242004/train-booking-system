import { Elements, PaymentElement, useElements, useStripe } from '@stripe/react-stripe-js';
import { loadStripe, type Stripe } from '@stripe/stripe-js';
import { useState, type FormEvent } from 'react';

// Stripe.js is loaded from Stripe's own servers, once per publishable key. Card data is typed into Stripe's iframe and goes straight
// to Stripe: it never reaches this page's code or our servers.
const stripePromises = new Map<string, Promise<Stripe | null>>();
function stripeFor(publishableKey: string): Promise<Stripe | null> {
  let promise = stripePromises.get(publishableKey);
  if (!promise) {
    promise = loadStripe(publishableKey);
    stripePromises.set(publishableKey, promise);
  }
  return promise;
}

interface Props {
  publishableKey: string;
  clientSecret: string;
  /** Where Stripe sends the customer back after a payment method that redirects (a bank page, for example). */
  returnUrl: string;
  /** Called when Stripe accepted the payment on the page. The booking is confirmed by the server, on Stripe's signed webhook. */
  onSubmitted: () => void;
}

/** Stripe's embedded payment form (Flow 4): it is part of our payment page, next to the countdown. */
export function StripeCheckout({ publishableKey, clientSecret, returnUrl, onSubmitted }: Props) {
  return (
    <Elements stripe={stripeFor(publishableKey)} options={{ clientSecret }}>
      <CheckoutForm returnUrl={returnUrl} onSubmitted={onSubmitted} />
    </Elements>
  );
}

function CheckoutForm({ returnUrl, onSubmitted }: Pick<Props, 'returnUrl' | 'onSubmitted'>) {
  const stripe = useStripe();
  const elements = useElements();
  const [paying, setPaying] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  async function pay(event: FormEvent) {
    event.preventDefault();
    if (!stripe || !elements) return;
    setPaying(true);
    setProblem(null);
    // redirect: 'if_required' keeps card payments on this page; only methods that need a bank page redirect to returnUrl.
    const { error } = await stripe.confirmPayment({ elements, confirmParams: { return_url: returnUrl }, redirect: 'if_required' });
    if (error) {
      // A declined card, a wrong number...: the payment stays open and so does the hold, so the user can try again (FR-P5).
      setProblem(error.message ?? 'The payment did not go through. You can try again.');
      setPaying(false);
      return;
    }
    // Not trusted as the result: the booking is confirmed only when the server has Stripe's signed webhook.
    onSubmitted();
  }

  return (
    <form onSubmit={(event) => void pay(event)}>
      <PaymentElement />
      {problem && <p className="notice notice-error" role="alert">{problem}</p>}
      <p>
        <button type="submit" className="button button-primary" disabled={!stripe || !elements || paying}>
          {paying ? 'Paying…' : 'Pay now'}
        </button>
      </p>
    </form>
  );
}
