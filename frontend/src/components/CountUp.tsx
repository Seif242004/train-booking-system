import { useEffect, useRef, useState } from 'react';
import { money } from '../format';

const DURATION_MS = 500;

/** An amount that counts from its previous value to the new one. With reduced motion, or the first time it is drawn, it just shows the amount. */
export function CountUp({ amount, currency }: { amount: number; currency: string }) {
  const [shown, setShown] = useState(amount);
  const from = useRef(amount);

  useEffect(() => {
    const start = from.current;
    if (start === amount || window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      from.current = amount;
      setShown(amount);
      return undefined;
    }
    const began = performance.now();
    let frame = 0;
    const step = (now: number) => {
      const t = Math.min(1, (now - began) / DURATION_MS);
      const value = start + (amount - start) * (1 - (1 - t) ** 3);
      setShown(value);
      if (t < 1) frame = requestAnimationFrame(step);
      else from.current = amount;
    };
    frame = requestAnimationFrame(step);
    return () => cancelAnimationFrame(frame);
  }, [amount]);

  // The text a screen reader gets is always the real amount, not a number in the middle of counting.
  return (
    <>
      <span aria-hidden="true">{money(shown, currency)}</span>
      <span className="sr-only">{money(amount, currency)}</span>
    </>
  );
}
