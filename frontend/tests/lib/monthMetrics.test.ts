import { describe, it, expect } from 'vitest';
import { computeMonthCounts, needsCategory, needsReview } from '../../app/lib/monthMetrics';
import type { Transaction } from '../../app/lib/api';

const tx = (over: Partial<Transaction>): Transaction => ({
  'db/id': 1,
  'transaction/amount': -10,
  'transaction/payee': 'Test',
  'transaction/posted-date': '2026-01-01',
  ...over,
});

const cat = { 'db/id': 5, 'category/name': 'Groceries' };

describe('needsCategory', () => {
  it('is true for an unsplit transaction with no category', () => {
    expect(needsCategory(tx({}))).toBe(true);
  });

  it('is false for an unsplit transaction with a category', () => {
    expect(needsCategory(tx({ 'transaction/category': cat }))).toBe(false);
  });

  it('is false when every split part has a category', () => {
    expect(
      needsCategory(
        tx({
          'transaction/splits': [
            { 'db/id': 11, 'split/amount': -4, 'split/category': cat },
            { 'db/id': 12, 'split/amount': -6, 'split/category': cat },
          ],
        })
      )
    ).toBe(false);
  });

  it('is true when any split part is missing a category', () => {
    expect(
      needsCategory(
        tx({
          'transaction/splits': [
            { 'db/id': 11, 'split/amount': -4, 'split/category': cat },
            { 'db/id': 12, 'split/amount': -6 },
          ],
        })
      )
    ).toBe(true);
  });
});

describe('needsReview', () => {
  it('is false only when explicitly reviewed', () => {
    expect(needsReview(tx({ 'transaction/reviewed': true }))).toBe(false);
    expect(needsReview(tx({ 'transaction/reviewed': false }))).toBe(true);
    expect(needsReview(tx({}))).toBe(true);
  });
});

describe('computeMonthCounts', () => {
  it('counts total, uncategorized, unreviewed and hidden transfers', () => {
    const counts = computeMonthCounts([
      tx({ 'db/id': 1, 'transaction/category': cat, 'transaction/reviewed': true }),
      tx({ 'db/id': 2 }), // uncategorized + unreviewed
      tx({ 'db/id': 3, 'transaction/category': cat }), // unreviewed only
      tx({ 'db/id': 4, 'transaction/transfer-hidden': true }), // uncat + unreviewed + hidden
    ]);

    expect(counts).toEqual({
      total: 4,
      uncategorized: 2,
      unreviewed: 3,
      transfersHidden: 1,
    });
  });

  it('is all-zero for an empty month', () => {
    expect(computeMonthCounts([])).toEqual({
      total: 0,
      uncategorized: 0,
      unreviewed: 0,
      transfersHidden: 0,
    });
  });
});
