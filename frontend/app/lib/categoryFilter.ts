import type { FilterValue } from './filterState';
import type { FilterOption } from './filterOptions';
import type { CategoryType } from './categoryTypes';
import type { Transaction } from './api';

// Uncategorized is not a stored category — it's the absence of one. We surface it
// as two string sentinels (by sign, like the `reviewed` filter's string values) so
// it can live in `filters.category` alongside real numeric category ids.
export const UNCATEGORIZED_INCOME = 'uncategorized:income';
export const UNCATEGORIZED_EXPENSE = 'uncategorized:expense';

export const UNCATEGORIZED_LABELS: Record<string, string> = {
  [UNCATEGORIZED_INCOME]: 'Uncategorized (income)',
  [UNCATEGORIZED_EXPENSE]: 'Uncategorized (expenses)',
};

export const isUncategorizedToken = (value: FilterValue): boolean =>
  value === UNCATEGORIZED_INCOME || value === UNCATEGORIZED_EXPENSE;

const uncategorizedTokenForAmount = (amount: number): FilterValue =>
  amount >= 0 ? UNCATEGORIZED_INCOME : UNCATEGORIZED_EXPENSE;

/** The uncategorized sentinel for a rollup section (only Income/Expenses ever has one). */
export const uncategorizedTokenForType = (type: CategoryType): FilterValue =>
  type === 'income' ? UNCATEGORIZED_INCOME : UNCATEGORIZED_EXPENSE;

/**
 * The category-filter tokens a transaction matches. Split-aware and a mirror of
 * the rollup's attribution: each split part (or the whole transaction when
 * unsplit) maps to its category id, or — when it has no category, or one not in
 * `presentIds` (e.g. a deleted category) — to a sign-based uncategorized sentinel.
 * A transaction can therefore match several tokens at once.
 */
export function categoryFilterValues(
  tx: Transaction,
  presentIds: Set<number>
): FilterValue[] {
  const tokens = new Set<FilterValue>();
  const add = (categoryId: number | null | undefined, amount: number) => {
    if (categoryId != null && presentIds.has(categoryId)) {
      tokens.add(categoryId);
    } else {
      tokens.add(uncategorizedTokenForAmount(amount));
    }
  };

  const splits = tx['transaction/splits'];
  if (splits && splits.length > 0) {
    for (const s of splits) {
      add(s['split/category']?.['db/id'], s['split/amount'] ?? 0);
    }
  } else {
    add(tx['transaction/category']?.['db/id'], tx['transaction/amount'] ?? 0);
  }

  return [...tokens];
}

/**
 * Builds the Category filter-dropdown options from the transactions in view.
 * Split-aware (a split transaction counts toward each of its parts' categories) so
 * the counts match what the filter selects, and includes the two by-sign
 * Uncategorized sentinels. Sorted alphabetically by label.
 */
export function buildCategoryOptions(
  transactions: Transaction[],
  presentIds: Set<number>,
  nameById: Map<number, string>
): FilterOption[] {
  const counts = new Map<FilterValue, number>();
  for (const tx of transactions) {
    for (const token of categoryFilterValues(tx, presentIds)) {
      counts.set(token, (counts.get(token) ?? 0) + 1);
    }
  }
  return Array.from(counts.entries())
    .map(([value, count]) => ({
      value,
      count,
      label:
        typeof value === 'number'
          ? nameById.get(value) ?? 'Unknown'
          : UNCATEGORIZED_LABELS[value] ?? String(value),
    }))
    .sort((a, b) => a.label.localeCompare(b.label));
}
