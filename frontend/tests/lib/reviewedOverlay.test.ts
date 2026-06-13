import { describe, it, expect } from 'vitest';
import {
  applyReviewedOverlay,
  setTxOverride,
  setSplitOverride,
  EMPTY_REVIEWED_OVERRIDES,
} from '../../app/lib/reviewedOverlay';
import { applyFilters } from '../../app/lib/filterOptions';
import type { Transaction } from '../../app/lib/api';

const unsplit = (id: number, reviewed?: boolean): Transaction => ({
  'db/id': id,
  'transaction/posted-date': '2026-05-01',
  'transaction/payee': `Payee ${id}`,
  'transaction/amount': -10,
  ...(reviewed !== undefined ? { 'transaction/reviewed': reviewed } : {}),
});

const split = (id: number, parts: Array<{ id: number; reviewed?: boolean }>): Transaction => ({
  'db/id': id,
  'transaction/posted-date': '2026-05-01',
  'transaction/payee': `Payee ${id}`,
  'transaction/amount': -100,
  'transaction/reviewed': parts.every((p) => p.reviewed === true),
  'transaction/splits': parts.map((p, i) => ({
    'db/id': p.id,
    'split/amount': -50,
    'split/order': i,
    ...(p.reviewed !== undefined ? { 'split/reviewed': p.reviewed } : {}),
  })),
});

describe('applyReviewedOverlay', () => {
  it('overlays an unsplit transaction toggle', () => {
    const txns = [unsplit(1)];
    const overrides = setTxOverride(EMPTY_REVIEWED_OVERRIDES, 1, true);
    expect(applyReviewedOverlay(txns, overrides)[0]['transaction/reviewed']).toBe(true);
  });

  it('returns untouched rows by identity (referentially stable)', () => {
    const txns = [unsplit(1), unsplit(2)];
    const overrides = setTxOverride(EMPTY_REVIEWED_OVERRIDES, 1, true);
    const out = applyReviewedOverlay(txns, overrides);
    expect(out[0]).not.toBe(txns[0]); // changed row is a fresh object
    expect(out[1]).toBe(txns[1]); // untouched row is the same reference
  });

  it('overlays a split part and rolls the parent up only when every part is reviewed', () => {
    const txns = [split(5, [{ id: 51 }, { id: 52 }])];

    const one = applyReviewedOverlay(txns, setSplitOverride(EMPTY_REVIEWED_OVERRIDES, 51, true));
    expect(one[0]['transaction/splits']![0]['split/reviewed']).toBe(true);
    expect(one[0]['transaction/splits']![1]['split/reviewed']).toBeUndefined();
    expect(one[0]['transaction/reviewed']).toBe(false); // one of two

    const both = applyReviewedOverlay(
      txns,
      setSplitOverride(setSplitOverride(EMPTY_REVIEWED_OVERRIDES, 51, true), 52, true)
    );
    expect(both[0]['transaction/reviewed']).toBe(true); // roll-up flips
  });

  it('leaves a split parent untouched when no part is overridden', () => {
    const txns = [split(5, [{ id: 51, reviewed: true }, { id: 52, reviewed: true }])];
    const out = applyReviewedOverlay(txns, EMPTY_REVIEWED_OVERRIDES);
    expect(out[0]).toBe(txns[0]);
  });

  // The reported bug: with "Unreviewed" selected, a row the user just checked stayed
  // visible and the counts didn't move, because the filter read the raw loader snapshot
  // while the checkbox read a table-local override. Overlaying above the filter fixes it.
  it('keeps filter and counts in sync with a pending toggle', () => {
    const txns = [unsplit(1, false), unsplit(2, false), unsplit(3, true)];
    const overlaid = applyReviewedOverlay(txns, setTxOverride(EMPTY_REVIEWED_OVERRIDES, 1, true));

    const reviewedCount = overlaid.filter((tx) => tx['transaction/reviewed']).length;
    expect(reviewedCount).toBe(2); // was 1, the toggle is reflected immediately

    const unreviewed = applyFilters(
      overlaid,
      { reviewed: ['unreviewed'] },
      { reviewed: (tx: Transaction) => (tx['transaction/reviewed'] ? 'reviewed' : 'unreviewed') }
    );
    // The just-reviewed row (1) drops out of the Unreviewed view; only 2 remains.
    expect(unreviewed.map((tx) => tx['db/id'])).toEqual([2]);
  });
});
