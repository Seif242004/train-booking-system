/** Money is always shown the same way: the currency, then the amount with two decimals ("EGP 230.00"). */
export function money(amount: number, currency = 'EGP'): string {
  return `${currency} ${amount.toFixed(2)}`;
}
