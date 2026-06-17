import { describe, it, expect } from 'vitest';
import type { Transaction } from './api';
import {
  applyCategoryOverlay,
  setTxCategoryOverride,
  EMPTY_CATEGORY_OVERRIDES,
} from './categoryOverlay';

const tx = (over: Partial<Transaction> = {}): Transaction =>
  ({
    'db/id': 1,
    'transaction/amount': -12.5,
    'transaction/payee': 'Whole Foods',
    'transaction/posted-date': '2025-01-03',
    ...over,
  }) as Transaction;

const groceries = { 'db/id': 5, 'category/name': 'Groceries', 'category/type': 'expense' as const };

describe('categoryOverlay', () => {
  it('overlays a pending category onto an unsplit transaction', () => {
    const overrides = setTxCategoryOverride(EMPTY_CATEGORY_OVERRIDES, 1, groceries);
    const [result] = applyCategoryOverlay([tx()], overrides);
    expect(result['transaction/category']).toEqual(groceries);
  });

  it('clears the category when the override is null', () => {
    const overrides = setTxCategoryOverride(EMPTY_CATEGORY_OVERRIDES, 1, null);
    const [result] = applyCategoryOverlay(
      [tx({ 'transaction/category': groceries })],
      overrides
    );
    expect(result['transaction/category']).toBeNull();
  });

  it('returns untouched rows by identity (referential stability)', () => {
    const a = tx({ 'db/id': 1 });
    const b = tx({ 'db/id': 2, 'transaction/payee': 'Shell' });
    const overrides = setTxCategoryOverride(EMPTY_CATEGORY_OVERRIDES, 1, groceries);
    const [resA, resB] = applyCategoryOverlay([a, b], overrides);
    expect(resA).not.toBe(a); // edited row is a fresh object
    expect(resB).toBe(b); // untouched row is the same reference
  });

  it('never overlays a split transaction (parts own their categories)', () => {
    const split = tx({
      'transaction/splits': [
        { 'db/id': 10, 'split/amount': -12.5, 'split/category': null },
      ],
    });
    const overrides = setTxCategoryOverride(EMPTY_CATEGORY_OVERRIDES, 1, groceries);
    const [result] = applyCategoryOverlay([split], overrides);
    expect(result).toBe(split);
    expect(result['transaction/category']).toBeUndefined();
  });

  it('last write wins for repeated edits to the same transaction', () => {
    const dining = { 'db/id': 7, 'category/name': 'Dining', 'category/type': 'expense' as const };
    let overrides = setTxCategoryOverride(EMPTY_CATEGORY_OVERRIDES, 1, groceries);
    overrides = setTxCategoryOverride(overrides, 1, dining);
    const [result] = applyCategoryOverlay([tx()], overrides);
    expect(result['transaction/category']).toEqual(dining);
  });
});
