// Constructed once; building an Intl.NumberFormat per call is comparatively
// expensive and formatAmount runs once per amount cell on every table render.
const currencyFormatter = new Intl.NumberFormat('en-CA', {
  style: 'currency',
  currency: 'CAD',
});

export function formatAmount(amount: number): string {
  return currencyFormatter.format(amount);
}

export function formatDate(dateString: string): string {
  const date = new Date(dateString);
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
                  'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  // Use UTC methods to avoid timezone issues with ISO date strings
  const month = months[date.getUTCMonth()];
  const day = date.getUTCDate();
  const year = date.getUTCFullYear();
  return `${month} ${day}, ${year}`;
}
