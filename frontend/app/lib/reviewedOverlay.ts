// Optimistic "reviewed" projection (functional core).
//
// The server is the source of truth for the reviewed flag, but a toggle must show up
// instantly across three surfaces that all read the transaction list: the table's
// checkbox, the Reviewed filter predicate, and the filter's counts. To keep them in
// sync we overlay the user's pending toggles onto the loader snapshot ONCE, above the
// filter, and render everything from that single projection. Persistence is decoupled
// (see useReviewedSync) so the UI never waits on — or re-fetches per — the network.

import type { Transaction } from './api';

// Pending, not-yet-reconciled reviewed values the user has toggled, keyed by entity id.
// `tx` holds unsplit-transaction overrides; `split` holds per-part overrides. A split
// parent has no override of its own — its effective reviewed rolls up from its parts.
export interface ReviewedOverrides {
  tx: Record<number, boolean>;
  split: Record<number, boolean>;
}

export const EMPTY_REVIEWED_OVERRIDES: ReviewedOverrides = { tx: {}, split: {} };

export function setTxOverride(
  overrides: ReviewedOverrides,
  txId: number,
  reviewed: boolean
): ReviewedOverrides {
  return { ...overrides, tx: { ...overrides.tx, [txId]: reviewed } };
}

export function setSplitOverride(
  overrides: ReviewedOverrides,
  splitId: number,
  reviewed: boolean
): ReviewedOverrides {
  return { ...overrides, split: { ...overrides.split, [splitId]: reviewed } };
}

// Overlay the pending toggles onto the server snapshot. Mirrors the backend's
// `with-reviewed`: a split transaction's effective reviewed is "every part reviewed",
// recomputed here from the overlaid parts so the filter and counts are correct before
// the writes land. Rows with no pending override are returned by identity, so the result
// is referentially stable for everything the user hasn't touched.
export function applyReviewedOverlay(
  transactions: Transaction[],
  overrides: ReviewedOverrides
): Transaction[] {
  const { tx: txOverrides, split: splitOverrides } = overrides;

  return transactions.map((transaction) => {
    const parts = transaction['transaction/splits'];

    if (parts && parts.length > 0) {
      const touched = parts.some((p) => p['db/id'] in splitOverrides);
      if (!touched) return transaction; // server already rolled the parent up
      const overlaidParts = parts.map((p) =>
        p['db/id'] in splitOverrides
          ? { ...p, 'split/reviewed': splitOverrides[p['db/id']] }
          : p
      );
      return {
        ...transaction,
        'transaction/splits': overlaidParts,
        'transaction/reviewed': overlaidParts.every((p) => p['split/reviewed'] === true),
      };
    }

    const id = transaction['db/id'];
    return id in txOverrides
      ? { ...transaction, 'transaction/reviewed': txOverrides[id] }
      : transaction;
  });
}
