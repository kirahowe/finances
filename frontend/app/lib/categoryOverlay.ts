// Optimistic "category" projection (functional core).
//
// A transaction's category is user-assigned and, like the reviewed flag and the
// description, an edit must show up at once across every surface that reads the list:
// the cell, the Category filter predicate, the filter counts, and the rollup. So we
// overlay pending category assignments onto the loader snapshot here, above the filter,
// and render everything from that single projection. Persistence is decoupled (see
// useWriteBehind) so the UI never waits on — or re-fetches per — the network. This
// mirrors reviewedOverlay / descriptionOverlay; before this existed a category edit went
// through a full loader revalidation, so it lagged the network and behaved differently
// from the other in-place edits.
//
// In-cell category editing only applies to UNSPLIT transactions — a split's parts are
// categorized through the split modal — so overrides are transaction-level only.

import type { Transaction } from './api';
import type { CategoryType } from './categoryTypes';

// The category a transaction has been reassigned to but not yet reconciled, keyed by
// transaction id. `null` means "cleared back to Uncategorized". The value is the resolved
// category reference (id + name + type) so the cell label, the transfer-status pill (it
// keys off `category/type`), and the category filter token are all correct before the
// write lands.
export type CategoryOverrideValue =
  | { 'db/id': number; 'category/name': string; 'category/type'?: CategoryType }
  | null;

export interface CategoryOverrides {
  tx: Record<number, CategoryOverrideValue>;
}

export const EMPTY_CATEGORY_OVERRIDES: CategoryOverrides = { tx: {} };

export function setTxCategoryOverride(
  overrides: CategoryOverrides,
  txId: number,
  category: CategoryOverrideValue
): CategoryOverrides {
  return { ...overrides, tx: { ...overrides.tx, [txId]: category } };
}

// Overlay the pending category assignments onto the server snapshot. A split transaction
// is categorized per-part, never as a whole, so any override on one is ignored. Rows with
// no pending override are returned by identity, so the result is referentially stable for
// everything the user hasn't touched.
export function applyCategoryOverlay(
  transactions: Transaction[],
  overrides: CategoryOverrides
): Transaction[] {
  const { tx: txOverrides } = overrides;

  return transactions.map((transaction) => {
    const id = transaction['db/id'];
    if (!(id in txOverrides)) return transaction;

    const parts = transaction['transaction/splits'];
    if (parts && parts.length > 0) return transaction; // split parts own their categories

    return { ...transaction, 'transaction/category': txOverrides[id] };
  });
}
