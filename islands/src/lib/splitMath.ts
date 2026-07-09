/**
 * Pure helpers for the split editor.
 *
 * The user types each part as a positive magnitude ("this cost $8"); a new part's
 * signed amount is that magnitude carried into the parent transaction's sign (an
 * expense splits into expenses). A part seeded from a stored split keeps its own
 * signed value until its magnitude is edited, so an existing part whose sign
 * opposes the parent (a mixed-sign split) survives a round-trip instead of being
 * silently normalized. Reconciliation is done in signed integer cents, matching
 * the authoritative bigdec check the server runs on save.
 */

export interface SplitRowInput {
  amount: string;
  categoryId: number | null;
  // Signed cents from a stored split, retained until the magnitude is edited.
  seedCents?: number | null;
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

/** Convert a numeric amount (e.g. a stored signed split amount) to integer cents. */
export function toCents(amount: number): number {
  return Math.round(amount * 100);
}

/** Format integer cents as a 2-decimal string, e.g. -800 -> "-8.00", 4000 -> "40.00". */
export function centsToAmountString(cents: number): string {
  return (cents / 100).toFixed(2);
}

const signOf = (amount: number): number => (amount < 0 ? -1 : 1);

/** Whether `amount` is representable in whole cents (the editor's 2-decimal precision). */
export function isWholeCents(amount: number): boolean {
  return Math.abs(amount * 100 - Math.round(amount * 100)) < 1e-6;
}

/**
 * The signed cents a row currently represents: its stored signed value until the
 * magnitude is edited, otherwise the entered magnitude carried into the parent's
 * sign. Null when the amount is blank/unparseable.
 */
export function rowSignedCents(parentAmount: number, row: SplitRowInput): number | null {
  if (row.seedCents != null) return row.seedCents;
  const mag = parseMagnitudeCents(row.amount);
  return mag == null ? null : signOf(parentAmount) * mag;
}

// Cents left to reach the parent total, expressed in the parent's direction:
// positive = still to allocate, negative = over-allocated. Unparseable rows count
// as 0 so the figure stays live while typing.
function directionalRemaining(parentAmount: number, sumSignedCents: number): number {
  const remaining = signOf(parentAmount) * (toCents(parentAmount) - sumSignedCents);
  return remaining === 0 ? 0 : remaining; // normalize -0
}

/** Cents still needed to reach the parent's total (parent-direction magnitude). 0 = balanced. */
export function remainingCents(parentAmount: number, rows: SplitRowInput[]): number {
  const sum = rows.reduce((acc, r) => acc + (rowSignedCents(parentAmount, r) ?? 0), 0);
  return directionalRemaining(parentAmount, sum);
}

/**
 * Magnitude (in cents) that row `index` would need to balance the split, given the
 * other rows. May be <= 0 if the other rows already meet or exceed the total.
 */
export function fillRemainingCents(
  parentAmount: number,
  rows: SplitRowInput[],
  index: number
): number {
  const others = rows.reduce(
    (acc, r, i) => (i === index ? acc : acc + (rowSignedCents(parentAmount, r) ?? 0)),
    0
  );
  return directionalRemaining(parentAmount, others);
}

/**
 * Whether the split editor's rows are valid and ready to save. A part may be left
 * uncategorized (the Uncategorized bucket owns it — categorize now or later); only
 * the amounts must be present, non-zero, and reconcile exactly.
 */
export function canConfirm(parentAmount: number, rows: SplitRowInput[]): boolean {
  if (rows.length < 2) return false;
  // The 2-decimal editor can only reconcile a whole-cent parent exactly; for a
  // sub-cent parent the server would reject an otherwise "balanced" set.
  if (!isWholeCents(parentAmount)) return false;
  for (const r of rows) {
    const cents = rowSignedCents(parentAmount, r);
    if (cents === null || cents === 0) return false;
  }
  return remainingCents(parentAmount, rows) === 0;
}
