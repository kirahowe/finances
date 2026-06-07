/**
 * Pure helpers for the split editor and the drift badge.
 *
 * The editor lets the user enter each part as a positive magnitude ("this cost
 * $8"); the part's real signed amount is the magnitude with the parent
 * transaction's sign applied (an expense splits into expenses). All math is in
 * integer cents to avoid floating-point drift; the authoritative bigdec
 * reconciliation still happens server-side on save.
 */

export interface SplitRowInput {
  amount: string;
  categoryId: number | null;
}

const AMOUNT_RE = /^-?(\d+(\.\d{1,2})?|\.\d{1,2})$/;

/** Parse a user-entered amount to a positive magnitude in integer cents, or null if malformed. */
export function parseMagnitudeCents(raw: string): number | null {
  const s = raw.trim();
  if (!AMOUNT_RE.test(s)) return null;
  const value = Number(s);
  if (!Number.isFinite(value)) return null;
  return Math.abs(Math.round(value * 100));
}

/** Convert a numeric amount (e.g. from the API) to integer cents. */
export function toCents(amount: number): number {
  return Math.round(amount * 100);
}

/** Format integer cents as a 2-decimal string, e.g. -800 -> "-8.00", 4000 -> "40.00". */
export function centsToAmountString(cents: number): string {
  return (cents / 100).toFixed(2);
}

/**
 * Magnitude (in cents) still needed to reach the parent's total. Zero means
 * balanced; negative means the parts over-allocate. In-progress / unparseable
 * rows count as 0 so the figure stays live while typing.
 */
export function remainingCents(parentAmount: number, rows: SplitRowInput[]): number {
  const total = Math.abs(toCents(parentAmount));
  const sum = rows.reduce((acc, r) => acc + (parseMagnitudeCents(r.amount) ?? 0), 0);
  return total - sum;
}

/**
 * Magnitude (in cents) that row `index` would need to balance the split, given
 * the other rows. May be <= 0 if the other rows already meet or exceed the total.
 */
export function fillRemainingCents(
  parentAmount: number,
  rows: SplitRowInput[],
  index: number
): number {
  const total = Math.abs(toCents(parentAmount));
  const others = rows.reduce(
    (acc, r, i) => (i === index ? acc : acc + (parseMagnitudeCents(r.amount) ?? 0)),
    0
  );
  return total - others;
}

/** Whether the split editor's rows are valid and ready to save. */
export function canConfirm(parentAmount: number, rows: SplitRowInput[]): boolean {
  if (rows.length < 2) return false;
  for (const r of rows) {
    const mag = parseMagnitudeCents(r.amount);
    if (mag === null || mag === 0) return false;
    if (r.categoryId == null) return false;
  }
  return remainingCents(parentAmount, rows) === 0;
}

/**
 * Whether existing split parts (signed numeric amounts from the API) still
 * reconcile to the parent. Used to flag drift after a re-sync changed the amount.
 */
export function isSplitBalanced(parentAmount: number, partAmounts: number[]): boolean {
  const sum = partAmounts.reduce((acc, a) => acc + toCents(a), 0);
  return toCents(parentAmount) === sum;
}
