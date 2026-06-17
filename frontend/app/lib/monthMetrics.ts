import type { Transaction } from "./api";

export interface MonthCounts {
  /** Total transactions in view. */
  total: number;
  /** Transactions whose category (or any split part) is still unassigned. */
  uncategorized: number;
  /** Transactions not yet marked reviewed. */
  unreviewed: number;
  /** Transactions the Hide-transfers toggle would remove (matched transfer legs). */
  transfersHidden: number;
}

/** True when a transaction still needs a category assigned. A split needs work
 * when any of its parts lacks a category; an unsplit row needs a category ref. */
export function needsCategory(tx: Transaction): boolean {
  const splits = tx["transaction/splits"];
  if (splits && splits.length > 0) {
    return splits.some((s) => !s["split/category"]?.["db/id"]);
  }
  return !tx["transaction/category"]?.["db/id"];
}

/** True when a transaction is not yet reviewed. */
export function needsReview(tx: Transaction): boolean {
  return tx["transaction/reviewed"] !== true;
}

/** Counts that drive the filter chips and review toggle, derived from the in-view
 * (overlaid) transactions so they track optimistic edits. */
export function computeMonthCounts(transactions: Transaction[]): MonthCounts {
  let uncategorized = 0;
  let unreviewed = 0;
  let transfersHidden = 0;
  for (const tx of transactions) {
    if (needsCategory(tx)) uncategorized += 1;
    if (needsReview(tx)) unreviewed += 1;
    if (tx["transaction/transfer-hidden"]) transfersHidden += 1;
  }
  return { total: transactions.length, uncategorized, unreviewed, transfersHidden };
}
