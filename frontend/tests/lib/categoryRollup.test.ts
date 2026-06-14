import { describe, it, expect } from 'vitest';
import { buildCategoryRollup } from '../../app/lib/categoryRollup';
import type { Category, Transaction } from '../../app/lib/api';
import type { CategoryType } from '../../app/lib/categoryTypes';

const cat = (
  id: number,
  name: string,
  type: CategoryType,
  parentId?: number,
  sortOrder?: number
): Category => ({
  'db/id': id,
  'category/name': name,
  'category/type': type,
  ...(parentId != null ? { 'category/parent': { 'db/id': parentId } } : {}),
  ...(sortOrder != null ? { 'category/sort-order': sortOrder } : {}),
});

const tx = (id: number, amount: number, categoryId: number | null): Transaction => ({
  'db/id': id,
  'transaction/posted-date': '2026-05-01',
  'transaction/payee': `Payee ${id}`,
  'transaction/amount': amount,
  ...(categoryId != null
    ? { 'transaction/category': { 'db/id': categoryId, 'category/name': `c${categoryId}` } }
    : {}),
});

const splitTx = (
  id: number,
  parts: Array<{ amount: number; categoryId: number | null }>
): Transaction => ({
  'db/id': id,
  'transaction/posted-date': '2026-05-01',
  'transaction/payee': `Payee ${id}`,
  'transaction/amount': parts.reduce((a, p) => a + p.amount, 0),
  'transaction/splits': parts.map((p, i) => ({
    'db/id': id * 100 + i,
    'split/amount': p.amount,
    'split/order': i,
    'split/category': p.categoryId != null ? { 'db/id': p.categoryId } : null,
  })),
});

describe('buildCategoryRollup', () => {
  it('groups children under their parent with a subtotal of the children', () => {
    const cats = [
      cat(1, 'Housing', 'expense', undefined, 1),
      cat(2, 'Mortgage', 'expense', 1, 1),
      cat(3, 'Property tax', 'expense', 1, 2),
    ];
    const txns = [tx(10, -1854, 2), tx(11, -3390.93, 3)];

    const r = buildCategoryRollup(txns, cats);

    expect(r.expenses.rows[0]).toMatchObject({
      name: 'Housing',
      depth: 0,
      isGroup: true,
      clickable: true,
    });
    expect(r.expenses.rows[0].amount).toBeCloseTo(5244.93);
    // Clicking the group filters to the parent plus all of its children.
    expect(r.expenses.rows[0].ids).toEqual([1, 2, 3]);
    expect(r.expenses.rows[1]).toMatchObject({ name: 'Mortgage', depth: 1, isGroup: false });
    expect(r.expenses.rows[1].amount).toBeCloseTo(1854);
    expect(r.expenses.rows[1].ids).toEqual([2]);
    expect(r.expenses.rows[2]).toMatchObject({ name: 'Property tax', depth: 1 });
    expect(r.expenses.total).toBeCloseTo(5244.93);
  });

  it("includes a parent's own direct transactions in its group subtotal", () => {
    const cats = [cat(1, 'Housing', 'expense', undefined, 1), cat(2, 'Mortgage', 'expense', 1, 1)];
    const txns = [tx(10, -100, 1), tx(11, -50, 2)];

    const r = buildCategoryRollup(txns, cats);

    expect(r.expenses.rows[0]).toMatchObject({ name: 'Housing', isGroup: true });
    expect(r.expenses.rows[0].amount).toBeCloseTo(150);
    expect(r.expenses.rows[1]).toMatchObject({ name: 'Mortgage', depth: 1 });
    expect(r.expenses.rows[1].amount).toBeCloseTo(50);
  });

  it('includes inactive children in a group row ids so the filter covers the whole group', () => {
    const cats = [
      cat(1, 'Housing', 'expense', undefined, 1),
      cat(2, 'Mortgage', 'expense', 1, 1),
      cat(3, 'Repairs', 'expense', 1, 2), // no activity this month
    ];
    const txns = [tx(10, -1854, 2)];

    const r = buildCategoryRollup(txns, cats);

    expect(r.expenses.rows[0].ids).toEqual([1, 2, 3]);
    // The inactive child is not rendered as its own row.
    expect(r.expenses.rows.map((row) => row.name)).toEqual(['Housing', 'Mortgage']);
  });

  it('renders a childless top-level category as a plain leaf row', () => {
    const cats = [cat(1, 'Groceries', 'expense', undefined, 1)];
    const txns = [tx(10, -43.76, 1)];

    const r = buildCategoryRollup(txns, cats);

    expect(r.expenses.rows[0]).toMatchObject({
      name: 'Groceries',
      depth: 0,
      isGroup: false,
      clickable: true,
    });
    expect(r.expenses.rows[0].ids).toEqual([1]);
    expect(r.expenses.rows[0].amount).toBeCloseTo(43.76);
  });

  it('attributes split amounts to each split part category, not the parent', () => {
    const cats = [
      cat(1, 'Groceries', 'expense', undefined, 1),
      cat(2, 'Home supplies', 'expense', undefined, 2),
    ];
    const txns = [splitTx(10, [
      { amount: -30, categoryId: 1 },
      { amount: -20, categoryId: 2 },
    ])];

    const r = buildCategoryRollup(txns, cats);

    const groceries = r.expenses.rows.find((row) => row.name === 'Groceries');
    const supplies = r.expenses.rows.find((row) => row.name === 'Home supplies');
    expect(groceries?.amount).toBeCloseTo(30);
    expect(supplies?.amount).toBeCloseTo(20);
    expect(r.expenses.total).toBeCloseTo(50);
  });

  it('separates income, expenses, and transfers and excludes transfers from the grand total', () => {
    const cats = [
      cat(1, 'Paycheck', 'income', undefined, 1),
      cat(2, 'Groceries', 'expense', undefined, 1),
      cat(3, 'CC Payment', 'transfer', undefined, 1),
    ];
    const txns = [tx(10, 5000, 1), tx(11, -3000, 2), tx(12, -2000, 3)];

    const r = buildCategoryRollup(txns, cats);

    expect(r.income.total).toBeCloseTo(5000);
    expect(r.expenses.total).toBeCloseTo(3000);
    expect(r.transfers.total).toBeCloseTo(2000);
    expect(r.transfers.rows[0]).toMatchObject({ name: 'CC Payment' });
    // Grand total is income minus expenses; transfers do not affect it.
    expect(r.grandTotal).toBeCloseTo(2000);
  });

  it('buckets uncategorized transactions by sign and makes them non-clickable', () => {
    const cats = [cat(1, 'Paycheck', 'income', undefined, 1)];
    const txns = [tx(10, 5000, 1), tx(11, -200, null), tx(12, 300, null)];

    const r = buildCategoryRollup(txns, cats);

    const incomeUncat = r.income.rows.find((row) => row.name === 'Uncategorized');
    const expenseUncat = r.expenses.rows.find((row) => row.name === 'Uncategorized');
    expect(incomeUncat).toMatchObject({ clickable: false, ids: [] });
    expect(incomeUncat?.amount).toBeCloseTo(300);
    expect(expenseUncat).toMatchObject({ clickable: false, ids: [] });
    expect(expenseUncat?.amount).toBeCloseTo(200);
    // Uncategorized real money still counts toward the net.
    expect(r.grandTotal).toBeCloseTo(5100);
  });

  it('reports a signed grand total that is negative when expenses exceed income', () => {
    const cats = [
      cat(1, 'Paycheck', 'income', undefined, 1),
      cat(2, 'Housing', 'expense', undefined, 1),
    ];
    const txns = [tx(10, 4740.66, 1), tx(11, -5244.93, 2)];

    const r = buildCategoryRollup(txns, cats);

    expect(r.income.total).toBeCloseTo(4740.66);
    expect(r.expenses.total).toBeCloseTo(5244.93);
    expect(r.grandTotal).toBeCloseTo(-504.27);
  });

  it("keeps a group's ids within its own section, excluding a cross-type child", () => {
    const cats = [
      cat(1, "Misc", "expense", undefined, 1),
      cat(2, "Supplies", "expense", 1, 1),
      cat(3, "Refunds", "income", 1, 2), // a misconfigured cross-type child
    ];
    const txns = [tx(10, -40, 2), tx(11, 25, 3)];

    const r = buildCategoryRollup(txns, cats);

    // The expense group carries only its parent and same-type child.
    const misc = r.expenses.rows.find((row) => row.name === "Misc");
    expect(misc?.ids).toEqual([1, 2]);
    expect(r.expenses.total).toBeCloseTo(40);
    // The income child surfaces as a standalone leaf in the Income section.
    const refunds = r.income.rows.find((row) => row.name === "Refunds");
    expect(refunds).toMatchObject({ ids: [3], depth: 0, isGroup: false });
    expect(r.income.total).toBeCloseTo(25);
  });

  it('returns empty sections and a zero grand total for no transactions', () => {
    const r = buildCategoryRollup([], []);
    expect(r.income.rows).toEqual([]);
    expect(r.expenses.rows).toEqual([]);
    expect(r.transfers.rows).toEqual([]);
    expect(r.income.total).toBe(0);
    expect(r.grandTotal).toBe(0);
  });

  it('orders sections and rows by category sort-order', () => {
    const cats = [
      cat(1, 'Transportation', 'expense', undefined, 2),
      cat(2, 'Housing', 'expense', undefined, 1),
    ];
    const txns = [tx(10, -50, 1), tx(11, -100, 2)];

    const r = buildCategoryRollup(txns, cats);

    expect(r.expenses.rows.map((row) => row.name)).toEqual(['Housing', 'Transportation']);
  });
});
