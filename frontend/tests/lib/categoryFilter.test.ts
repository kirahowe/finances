import { describe, it, expect } from 'vitest';
import {
  categoryFilterValues,
  UNCATEGORIZED_INCOME,
  UNCATEGORIZED_EXPENSE,
} from '../../app/lib/categoryFilter';
import type { Transaction } from '../../app/lib/api';

const present = new Set([1, 2, 3]);

const tx = (over: Partial<Transaction>): Transaction =>
  ({
    'db/id': 100,
    'transaction/amount': -10,
    'transaction/payee': 'x',
    'transaction/posted-date': '2026-06-01',
    ...over,
  }) as Transaction;

const catRef = (id: number) => ({
  'db/id': id,
  'category/name': `c${id}`,
  'category/type': 'expense' as const,
});

describe('categoryFilterValues', () => {
  it('maps an unsplit categorized transaction to its category id', () => {
    expect(categoryFilterValues(tx({ 'transaction/category': catRef(2) }), present)).toEqual([2]);
  });

  it('maps an unsplit uncategorized outflow to the expense sentinel', () => {
    expect(categoryFilterValues(tx({ 'transaction/amount': -42 }), present)).toEqual([
      UNCATEGORIZED_EXPENSE,
    ]);
  });

  it('maps an unsplit uncategorized inflow to the income sentinel', () => {
    expect(categoryFilterValues(tx({ 'transaction/amount': 42 }), present)).toEqual([
      UNCATEGORIZED_INCOME,
    ]);
  });

  it('treats a category id not in presentIds (deleted) as uncategorized by sign', () => {
    expect(
      categoryFilterValues(
        tx({ 'transaction/amount': -5, 'transaction/category': catRef(99) }),
        present
      )
    ).toEqual([UNCATEGORIZED_EXPENSE]);
  });

  it('attributes split parts by their own category, ignoring the parent category', () => {
    const t = tx({
      'transaction/category': catRef(1),
      'transaction/splits': [
        { 'db/id': 1, 'split/amount': -5, 'split/category': catRef(2) },
        { 'db/id': 2, 'split/amount': -5, 'split/category': catRef(3) },
      ],
    });
    expect(categoryFilterValues(t, present).sort()).toEqual([2, 3]);
  });

  it('mixes real and uncategorized tokens across split parts, split by sign', () => {
    const t = tx({
      'transaction/splits': [
        { 'db/id': 1, 'split/amount': -5, 'split/category': catRef(2) },
        { 'db/id': 2, 'split/amount': -5 }, // uncategorized outflow
        { 'db/id': 3, 'split/amount': 8 }, // uncategorized inflow
      ],
    });
    expect(new Set(categoryFilterValues(t, present))).toEqual(
      new Set([2, UNCATEGORIZED_EXPENSE, UNCATEGORIZED_INCOME])
    );
  });

  it('deduplicates repeated tokens', () => {
    const t = tx({
      'transaction/splits': [
        { 'db/id': 1, 'split/amount': -5 },
        { 'db/id': 2, 'split/amount': -3 },
      ],
    });
    expect(categoryFilterValues(t, present)).toEqual([UNCATEGORIZED_EXPENSE]);
  });
});
