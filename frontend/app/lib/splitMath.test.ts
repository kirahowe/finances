import { describe, it, expect } from 'vitest';
import {
  parseMagnitudeCents,
  centsToAmountString,
  remainingCents,
  fillRemainingCents,
  canConfirm,
  rowSignedCents,
  isWholeCents,
  sortSplits,
} from './splitMath';

const row = (amount: string, categoryId: number | null = 1) => ({ amount, categoryId });
const seeded = (seedCents: number, amount: string, categoryId: number | null = 1) => ({
  amount,
  categoryId,
  seedCents,
});

describe('parseMagnitudeCents', () => {
  it('parses amounts as positive magnitudes', () => {
    expect(parseMagnitudeCents('8')).toBe(800);
    expect(parseMagnitudeCents('8.50')).toBe(850);
    expect(parseMagnitudeCents('.25')).toBe(25);
    expect(parseMagnitudeCents(' 40.5 ')).toBe(4050);
  });

  it('treats a typed minus sign as a magnitude (forgiving)', () => {
    expect(parseMagnitudeCents('-8')).toBe(800);
  });

  it('rejects malformed or over-precise input', () => {
    expect(parseMagnitudeCents('')).toBeNull();
    expect(parseMagnitudeCents('abc')).toBeNull();
    expect(parseMagnitudeCents('1.234')).toBeNull();
  });
});

describe('centsToAmountString', () => {
  it('formats signed cents to a 2-decimal string', () => {
    expect(centsToAmountString(4000)).toBe('40.00');
    expect(centsToAmountString(-800)).toBe('-8.00');
    expect(centsToAmountString(25)).toBe('0.25');
  });
});

describe('remainingCents', () => {
  it('is zero when magnitudes sum to the parent total (expense)', () => {
    expect(remainingCents(-100, [row('60'), row('40')])).toBe(0);
  });

  it('works the same for income (positive parent)', () => {
    expect(remainingCents(100, [row('60'), row('40')])).toBe(0);
  });

  it('reports the shortfall when parts are under', () => {
    expect(remainingCents(-100, [row('60'), row('30')])).toBe(1000);
  });

  it('goes negative when parts over-allocate', () => {
    expect(remainingCents(-100, [row('60'), row('50')])).toBe(-1000);
  });

  it('treats in-progress rows as zero so it stays live', () => {
    expect(remainingCents(-100, [row('60'), row('')])).toBe(4000);
  });
});

describe('fillRemainingCents', () => {
  it('returns the full total when all other rows are empty', () => {
    expect(fillRemainingCents(-100, [row(''), row('')], 0)).toBe(10000);
  });

  it('returns what the empty row needs to balance', () => {
    expect(fillRemainingCents(-100, [row('60'), row('')], 1)).toBe(4000);
  });

  it('excludes the target row so it rebalances a filled row', () => {
    expect(fillRemainingCents(-100, [row('60'), row('60')], 0)).toBe(4000);
  });

  it('is <= 0 when the other rows already meet the total', () => {
    expect(fillRemainingCents(-100, [row('100'), row('')], 1)).toBe(0);
  });
});

describe('canConfirm', () => {
  it('accepts >=2 categorized rows whose magnitudes balance', () => {
    expect(canConfirm(-100, [row('60'), row('40')])).toBe(true);
    expect(canConfirm(100, [row('60'), row('40')])).toBe(true);
  });

  it('rejects fewer than two rows', () => {
    expect(canConfirm(-100, [row('100')])).toBe(false);
  });

  it('rejects an uncategorized row', () => {
    expect(canConfirm(-100, [row('60', null), row('40')])).toBe(false);
  });

  it('rejects a zero amount', () => {
    expect(canConfirm(-100, [row('0'), row('100')])).toBe(false);
  });

  it('rejects unbalanced rows', () => {
    expect(canConfirm(-100, [row('60'), row('30')])).toBe(false);
  });
});

describe('sortSplits', () => {
  it('orders parts by split/order without mutating the input', () => {
    const parts = [
      { 'split/order': 2, id: 'c' },
      { 'split/order': 0, id: 'a' },
      { 'split/order': 1, id: 'b' },
    ];
    expect(sortSplits(parts).map((p) => p.id)).toEqual(['a', 'b', 'c']);
    // original array is untouched
    expect(parts.map((p) => p.id)).toEqual(['c', 'a', 'b']);
  });

  it('treats a missing order as 0', () => {
    const parts = [{ 'split/order': 1, id: 'b' }, { id: 'a' }];
    expect(sortSplits(parts).map((p) => p.id)).toEqual(['a', 'b']);
  });
});

describe('rowSignedCents', () => {
  it('carries a typed magnitude into the parent sign', () => {
    expect(rowSignedCents(-100, row('60'))).toBe(-6000);
    expect(rowSignedCents(100, row('60'))).toBe(6000);
  });

  it('keeps a seeded part’s own sign (mixed-sign split)', () => {
    expect(rowSignedCents(-100, seeded(2000, '20'))).toBe(2000);
    expect(rowSignedCents(-100, seeded(-12000, '120'))).toBe(-12000);
  });

  it('is null for a blank/unparseable amount', () => {
    expect(rowSignedCents(-100, row(''))).toBeNull();
    expect(rowSignedCents(-100, row('abc'))).toBeNull();
  });
});

describe('mixed-sign splits', () => {
  // A part stored as +20 against a -100 parent (e.g. a purchase with a rebate line).
  const mixed = [seeded(-12000, '120'), seeded(2000, '20')];

  it('reconciles a seeded mixed-sign set as balanced', () => {
    expect(remainingCents(-100, mixed)).toBe(0);
    expect(canConfirm(-100, mixed)).toBe(true);
  });

  it('re-normalizes a part to the parent sign once its magnitude is edited', () => {
    // The +20 row is retyped (seedCents cleared): it becomes an expense, breaking balance.
    expect(rowSignedCents(-100, row('20'))).toBe(-2000);
    expect(canConfirm(-100, [seeded(-12000, '120'), row('20')])).toBe(false);
  });
});

describe('isWholeCents / canConfirm precision guard', () => {
  it('accepts whole-cent parents', () => {
    expect(isWholeCents(-100)).toBe(true);
    expect(isWholeCents(-0.3)).toBe(true);
  });

  it('rejects a sub-cent parent the 2-decimal editor cannot reconcile', () => {
    expect(isWholeCents(-100.005)).toBe(false);
    // Even when the cent-rounded magnitudes look balanced, the editor refuses to save.
    expect(canConfirm(-100.005, [row('60'), row('40')])).toBe(false);
  });
});
