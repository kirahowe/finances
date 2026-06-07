import { describe, it, expect } from 'vitest';
import {
  parseMagnitudeCents,
  centsToAmountString,
  remainingCents,
  fillRemainingCents,
  canConfirm,
  isSplitBalanced,
} from './splitMath';

const row = (amount: string, categoryId: number | null = 1) => ({ amount, categoryId });

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

describe('isSplitBalanced', () => {
  it('is true when signed parts reconcile to the parent', () => {
    expect(isSplitBalanced(-100, [-60, -40])).toBe(true);
  });

  it('is exact for fractional cents (would fail with naive floats)', () => {
    expect(isSplitBalanced(-0.3, [-0.1, -0.2])).toBe(true);
  });

  it('is false after the parent amount drifts', () => {
    expect(isSplitBalanced(-105, [-60, -40])).toBe(false);
  });
});
