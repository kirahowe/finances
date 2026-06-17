// Free-text search over the in-view transactions (functional core).
//
// Payee and description are high-cardinality, so they get a search box rather than a
// multi-select facet. The match is a case-insensitive substring across the fields a user
// would scan for: payee, the displayed (effective) description, the category name, and —
// for a split — each part's memo and category. Search runs as one more pass in the
// derived `filteredTransactions` pipeline, after the facet filters.

import type { Transaction } from './api';

function searchableText(tx: Transaction): string[] {
  const fields: (string | null | undefined)[] = [
    tx['transaction/payee'],
    tx['transaction/effective-description'] ?? tx['transaction/description'],
    tx['transaction/category']?.['category/name'],
  ];

  const parts = tx['transaction/splits'];
  if (parts) {
    for (const p of parts) {
      fields.push(p['split/memo']);
      fields.push(p['split/category']?.['category/name']);
    }
  }

  return fields.filter((f): f is string => typeof f === 'string' && f.length > 0);
}

/** Filter to the transactions whose searchable text contains `query` (case-insensitive).
 *  A blank query is a no-op (returns the input unchanged). */
export function searchTransactions(transactions: Transaction[], query: string): Transaction[] {
  const q = query.trim().toLowerCase();
  if (!q) return transactions;
  return transactions.filter((tx) =>
    searchableText(tx).some((text) => text.toLowerCase().includes(q))
  );
}
