// Optimistic "description" projection (functional core).
//
// Transactions are imported and immutable; a user's cleaned-up description is stored as
// an additive override (:transaction/user-description) and the server hands back the
// value to display as :transaction/effective-description. A split part has no import —
// it's user-created — so its description is just its :split/memo. An edit has to show up
// in the table instantly without waiting on (or re-fetching per) the network, so we
// overlay the user's pending edits onto the loader snapshot here and render the cells
// from that single projection. Persistence is decoupled (see useWriteBehind).

import type { Transaction } from './api';

// Pending, not-yet-reconciled description edits, keyed by entity id. `tx` holds
// transaction-level overrides (an empty string reverts to the imported description);
// `split` holds per-part descriptions, stored as the split's memo (empty clears it,
// since a split has no import to fall back to).
export interface DescriptionOverrides {
  tx: Record<number, string>;
  split: Record<number, string>;
}

export const EMPTY_DESCRIPTION_OVERRIDES: DescriptionOverrides = { tx: {}, split: {} };

export function setTxDescriptionOverride(
  overrides: DescriptionOverrides,
  txId: number,
  description: string
): DescriptionOverrides {
  return { ...overrides, tx: { ...overrides.tx, [txId]: description } };
}

export function setSplitDescriptionOverride(
  overrides: DescriptionOverrides,
  splitId: number,
  description: string
): DescriptionOverrides {
  return { ...overrides, split: { ...overrides.split, [splitId]: description } };
}

// Overlay pending edits onto the server snapshot. Mirrors the backend: a transaction's
// effective-description is its override when set, else the import; a split's memo is the
// override when set. An empty override reverts to the underlying value (the import for a
// transaction, nothing for a split). Rows with no pending edit are returned by identity,
// so the result is referentially stable for everything the user hasn't touched.
export function applyDescriptionOverlay(
  transactions: Transaction[],
  overrides: DescriptionOverrides
): Transaction[] {
  const { tx: txOverrides, split: splitOverrides } = overrides;

  return transactions.map((transaction) => {
    const id = transaction['db/id'];
    const parts = transaction['transaction/splits'];
    const hasTxOverride = id in txOverrides;
    const touchedSplit = parts?.some((p) => p['db/id'] in splitOverrides) ?? false;
    if (!hasTxOverride && !touchedSplit) return transaction;

    let next = transaction;

    if (hasTxOverride) {
      const override = txOverrides[id];
      const imported = transaction['transaction/description'] ?? null;
      next = {
        ...next,
        'transaction/user-description': override !== '' ? override : null,
        'transaction/effective-description': override !== '' ? override : imported,
      };
    }

    if (touchedSplit && parts) {
      next = {
        ...next,
        'transaction/splits': parts.map((p) => {
          if (!(p['db/id'] in splitOverrides)) return p;
          const override = splitOverrides[p['db/id']];
          return { ...p, 'split/memo': override !== '' ? override : null };
        }),
      };
    }

    return next;
  });
}
