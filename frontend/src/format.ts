/** Money is always shown the same way: the currency, then the amount with two decimals ("EGP 230.00"). */
export function money(amount: number, currency = 'EGP'): string {
  return `${currency} ${amount.toFixed(2)}`;
}

/** An enum value in words for the screen: "PENDING_PAYMENT" becomes "Pending payment" (the API keeps its capitals; the screen does not). */
export function label(value: string): string {
  const text = value.replaceAll('_', ' ').toLowerCase();
  return text.charAt(0).toUpperCase() + text.slice(1);
}
