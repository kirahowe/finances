export function formatAmount(amount: number): string {
  const formatter = new Intl.NumberFormat('en-CA', {
    style: 'currency',
    currency: 'CAD',
  });
  return formatter.format(amount);
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
