import { describe, it, expect } from 'vitest';
import type { Transaction } from './api';
import { withLingeringRows } from './lingeringRows';

const tx = (id: number): Transaction =>
  ({
    'db/id': id,
    'transaction/amount': -1,
    'transaction/payee': `Payee ${id}`,
    'transaction/posted-date': '2025-01-01',
  }) as Transaction;

describe('withLingeringRows', () => {
  it('returns the matches unchanged when nothing is lingering', () => {
    const candidates = [tx(1), tx(2), tx(3)];
    const matched = [tx(1), tx(3)];
    const { rows, staleIds } = withLingeringRows(candidates, matched, new Set());
    expect(rows).toBe(matched);
    expect(staleIds.size).toBe(0);
  });

  it('keeps an edited-away row visible and marks it stale', () => {
    const candidates = [tx(1), tx(2), tx(3)];
    // Row 2 was categorized and no longer matches, but the user is still working on it.
    const matched = [tx(1), tx(3)];
    const { rows, staleIds } = withLingeringRows(candidates, matched, new Set([2]));
    expect(rows.map((t) => t['db/id'])).toEqual([1, 2, 3]); // original order preserved
    expect([...staleIds]).toEqual([2]);
  });

  it('does not mark a lingering row stale once it matches again', () => {
    const candidates = [tx(1), tx(2), tx(3)];
    // Row 2 is in the linger set but currently matches (edit undone) — not stale.
    const matched = [tx(1), tx(2), tx(3)];
    const { rows, staleIds } = withLingeringRows(candidates, matched, new Set([2]));
    expect(rows.map((t) => t['db/id'])).toEqual([1, 2, 3]);
    expect(staleIds.size).toBe(0);
  });

  it('ignores lingering ids that are no longer candidates', () => {
    const candidates = [tx(1), tx(2)];
    const matched = [tx(1)];
    const { rows, staleIds } = withLingeringRows(candidates, matched, new Set([99]));
    expect(rows.map((t) => t['db/id'])).toEqual([1]);
    expect(staleIds.size).toBe(0);
  });
});
