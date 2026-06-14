import type { Category, Transaction } from './api';
import type { CategoryType } from './categoryTypes';
import { parentIdOf } from './categoryHierarchy';

/**
 * One line in the rollup pane. `amount` is a positive display magnitude (the
 * section heading conveys direction); `ids` are the category ids a click filters
 * the table to — a group row carries its parent plus every child, a leaf carries
 * just itself, and the non-clickable "Uncategorized" row carries none.
 */
export interface RollupRow {
  ids: number[];
  name: string;
  depth: 0 | 1;
  /** A top-level category that has children with activity (rendered emphasized). */
  isGroup: boolean;
  amount: number;
  clickable: boolean;
}

export interface RollupSection {
  type: CategoryType;
  rows: RollupRow[];
  /** Positive display magnitude of the section's net. */
  total: number;
}

export interface CategoryRollup {
  income: RollupSection;
  expenses: RollupSection;
  transfers: RollupSection;
  /** Signed net cash flow: income total minus expense total. Transfers excluded. */
  grandTotal: number;
}

interface CatInfo {
  name: string;
  type: CategoryType;
  parentId: number | null;
  sortOrder: number;
}

/**
 * Builds the category rollup from the transactions in view and the full category
 * list. Splits attribute to each part's own category; an unsplit transaction
 * attributes to its category, and a missing/unknown category falls into an
 * "Uncategorized" bucket split by sign (inflows → Income, outflows → Expenses).
 * The single-level parent hierarchy mirrors the rest of the app.
 */
export function buildCategoryRollup(
  transactions: Transaction[],
  categories: Category[]
): CategoryRollup {
  const present = new Set(categories.map((c) => c['db/id']));

  const info = new Map<number, CatInfo>();
  const childrenOf = new Map<number, number[]>();
  for (const c of categories) {
    const pid = parentIdOf(c);
    const parentId = pid !== null && present.has(pid) ? pid : null;
    info.set(c['db/id'], {
      name: c['category/name'],
      type: c['category/type'],
      parentId,
      sortOrder: c['category/sort-order'] ?? Number.MAX_SAFE_INTEGER,
    });
    if (parentId !== null) {
      const siblings = childrenOf.get(parentId) ?? [];
      siblings.push(c['db/id']);
      childrenOf.set(parentId, siblings);
    }
  }

  // Signed sums per category id, plus uncategorized split by sign.
  const sums = new Map<number, number>();
  let uncategorizedIncome = 0;
  let uncategorizedExpense = 0;

  const attribute = (categoryId: number | null | undefined, amount: number) => {
    if (categoryId != null && info.has(categoryId)) {
      sums.set(categoryId, (sums.get(categoryId) ?? 0) + amount);
    } else if (amount >= 0) {
      uncategorizedIncome += amount;
    } else {
      uncategorizedExpense += amount;
    }
  };

  for (const t of transactions) {
    const splits = t['transaction/splits'];
    if (splits && splits.length > 0) {
      for (const s of splits) {
        attribute(s['split/category']?.['db/id'], s['split/amount'] ?? 0);
      }
    } else {
      attribute(t['transaction/category']?.['db/id'], t['transaction/amount'] ?? 0);
    }
  }

  // Signed section totals (income positive, expenses negative); uncategorized is
  // real money so it counts toward the net.
  let incomeSigned = uncategorizedIncome;
  let expenseSigned = uncategorizedExpense;
  let transferSigned = 0;
  for (const [id, sum] of sums) {
    const type = info.get(id)!.type;
    if (type === 'income') incomeSigned += sum;
    else if (type === 'expense') expenseSigned += sum;
    else transferSigned += sum;
  }

  const sortKey = (id: number) => info.get(id)?.sortOrder ?? Number.MAX_SAFE_INTEGER;
  const bySortOrder = (a: number, b: number) => sortKey(a) - sortKey(b);

  const topLevelSorted = [...info.keys()]
    .filter((id) => info.get(id)!.parentId === null)
    .sort(bySortOrder);

  const sectionRows = (type: CategoryType, uncategorized: number): RollupRow[] => {
    const rows: RollupRow[] = [];
    const emitted = new Set<number>();

    for (const headId of topLevelSorted) {
      if (info.get(headId)!.type !== type) continue;
      // Same-type children only: children normally share the parent's type, but the
      // data model doesn't enforce it, so a cross-type child must not leak into this
      // section's group (its subtotal or its click-to-filter ids).
      const sameTypeChildIds = (childrenOf.get(headId) ?? []).filter(
        (id) => info.get(id)?.type === type
      );
      const groupIds = [headId, ...sameTypeChildIds];
      const activeChildIds = sameTypeChildIds.filter((id) => sums.has(id)).sort(bySortOrder);
      const headSum = sums.get(headId) ?? 0;
      const hasHead = sums.has(headId);
      if (!hasHead && activeChildIds.length === 0) continue;

      if (activeChildIds.length > 0) {
        const groupSum =
          headSum + activeChildIds.reduce((acc, id) => acc + (sums.get(id) ?? 0), 0);
        rows.push({
          ids: groupIds,
          name: info.get(headId)!.name,
          depth: 0,
          isGroup: true,
          amount: Math.abs(groupSum),
          clickable: true,
        });
        emitted.add(headId);
        for (const cid of activeChildIds) {
          rows.push({
            ids: [cid],
            name: info.get(cid)!.name,
            depth: 1,
            isGroup: false,
            amount: Math.abs(sums.get(cid)!),
            clickable: true,
          });
          emitted.add(cid);
        }
      } else {
        rows.push({
          ids: groupIds,
          name: info.get(headId)!.name,
          depth: 0,
          isGroup: false,
          amount: Math.abs(headSum),
          clickable: true,
        });
        emitted.add(headId);
      }
    }

    // Active categories of this type whose parent is a different type (so they were
    // never reached above) render as standalone leaves.
    const orphans = [...sums.keys()]
      .filter((id) => info.get(id)?.type === type && !emitted.has(id))
      .sort(bySortOrder);
    for (const id of orphans) {
      rows.push({
        ids: [id],
        name: info.get(id)!.name,
        depth: 0,
        isGroup: false,
        amount: Math.abs(sums.get(id)!),
        clickable: true,
      });
    }

    if (uncategorized !== 0) {
      rows.push({
        ids: [],
        name: 'Uncategorized',
        depth: 0,
        isGroup: false,
        amount: Math.abs(uncategorized),
        clickable: false,
      });
    }

    return rows;
  };

  return {
    income: { type: 'income', rows: sectionRows('income', uncategorizedIncome), total: Math.abs(incomeSigned) },
    expenses: { type: 'expense', rows: sectionRows('expense', uncategorizedExpense), total: Math.abs(expenseSigned) },
    transfers: { type: 'transfer', rows: sectionRows('transfer', 0), total: Math.abs(transferSigned) },
    grandTotal: incomeSigned + expenseSigned,
  };
}
