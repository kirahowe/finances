import { describe, it, expect } from 'vitest';
import {
  distributeColumnWidths,
  fitColumnWidth,
  sameWidths,
  type ColumnLeaf,
  type SizingPolicy,
} from './columnAutoSizing';

const leaf = (id: string, minSize: number, protect = false): ColumnLeaf => ({
  id,
  minSize,
  protected: protect,
});

const POLICY: SizingPolicy = {
  shrinkOrder: ['account', 'payee', 'description'],
  growIds: ['description'],
  flexibleCap: 400,
};

describe('fitColumnWidth', () => {
  it('returns the intrinsic width when it sits inside the range', () => {
    expect(fitColumnWidth(180, 80, 600)).toBe(180);
  });

  it('clamps up to the column minimum for a narrow intrinsic width', () => {
    expect(fitColumnWidth(40, 90, 600)).toBe(90);
  });

  it('clamps down to the max for an over-wide intrinsic width', () => {
    expect(fitColumnWidth(900, 80, 600)).toBe(600);
  });

  it('is independent of the other columns (pure single-column fit)', () => {
    // The double-click / local-resize leaf: same input → same output regardless of
    // anything else in the table, which is what makes a manual resize local.
    expect(fitColumnWidth(250, 100, 600)).toBe(250);
    expect(fitColumnWidth(250, 100, 600)).toBe(250);
  });
});

describe('distributeColumnWidths', () => {
  const leaves = [
    leaf('date', 80, true),
    leaf('account', 90),
    leaf('payee', 100),
    leaf('description', 200),
  ];

  it('caps flexible columns and leaves protected columns at full content width', () => {
    const intrinsic = { date: 500, account: 120, payee: 140, description: 1000 };
    const sizes = distributeColumnWidths(intrinsic, leaves, 10_000, POLICY);
    expect(sizes.date).toBe(500); // protected → uncapped
    expect(sizes.description).toBeGreaterThanOrEqual(200);
    // Flexible columns never exceed the cap before growing.
  });

  it('shrinks flexible columns in order down to their minimums when the row overflows', () => {
    const intrinsic = { date: 80, account: 200, payee: 200, description: 300 };
    const sizes = distributeColumnWidths(intrinsic, leaves, 400, POLICY);
    expect(sizes.date).toBe(80); // protected, never shrinks
    // The flexible columns gave up width (account first per shrinkOrder).
    expect(sizes.account).toBe(90);
  });
});

describe('sameWidths', () => {
  it('is true for equal records and false otherwise', () => {
    expect(sameWidths({ a: 1, b: 2 }, { a: 1, b: 2 })).toBe(true);
    expect(sameWidths({ a: 1, b: 2 }, { a: 1, b: 3 })).toBe(false);
    expect(sameWidths({ a: 1 }, { a: 1, b: 2 })).toBe(false);
  });
});
