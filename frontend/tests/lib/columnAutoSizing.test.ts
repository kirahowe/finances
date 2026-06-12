import { describe, it, expect } from 'vitest';
import {
  distributeColumnWidths,
  sameWidths,
  type ColumnLeaf,
  type SizingPolicy,
} from '../../app/lib/columnAutoSizing';

// A small stand-in for the real transactions table: two protected columns (date,
// amount) and two flexible ones (payee, description). Shrink takes from description
// first, then payee; grow fills payee + description.
const LEAVES: ColumnLeaf[] = [
  { id: 'date', minSize: 80, protected: true },
  { id: 'payee', minSize: 100, protected: false },
  { id: 'description', minSize: 100, protected: false },
  { id: 'amount', minSize: 90, protected: true },
];

const POLICY: SizingPolicy = {
  shrinkOrder: ['description', 'payee'],
  growIds: ['payee', 'description'],
  flexibleCap: 400,
};

describe('distributeColumnWidths', () => {
  it('caps flexible columns at flexibleCap but leaves protected columns at full width', () => {
    const intrinsic = { date: 500, payee: 800, description: 800, amount: 500 };
    // containerWidth 0 => no shrink/grow, just the capped intrinsic widths.
    const sizes = distributeColumnWidths(intrinsic, LEAVES, 0, POLICY);
    expect(sizes).toEqual({ date: 500, payee: 400, description: 400, amount: 500 });
  });

  it('floors each column at its minSize', () => {
    const intrinsic = { date: 10, payee: 10, description: 10, amount: 10 };
    const sizes = distributeColumnWidths(intrinsic, LEAVES, 0, POLICY);
    expect(sizes).toEqual({ date: 80, payee: 100, description: 100, amount: 90 });
  });

  it('grows the wide text columns to fill spare container width', () => {
    // Capped total = 100+100+100+100 = 400; container 600 => 200 surplus split across
    // payee + description (equal base => 100 each).
    const intrinsic = { date: 100, payee: 100, description: 100, amount: 100 };
    const sizes = distributeColumnWidths(intrinsic, LEAVES, 600, POLICY);
    expect(sizes.date).toBe(100);
    expect(sizes.amount).toBe(100);
    expect(sizes.payee).toBe(200);
    expect(sizes.description).toBe(200);
    expect(sizes.payee + sizes.description).toBe(400); // absorbed the full 200 surplus
  });

  it('shrinks flexible columns in order when the row overflows, protecting the rest', () => {
    // Capped total = 100+300+300+100 = 800; container 600 => 200 deficit. Description
    // gives up first (300 -> 100), still 0 more needed.
    const intrinsic = { date: 100, payee: 300, description: 300, amount: 100 };
    const sizes = distributeColumnWidths(intrinsic, LEAVES, 600, POLICY);
    expect(sizes.date).toBe(100); // protected, untouched
    expect(sizes.amount).toBe(100); // protected, untouched
    expect(sizes.description).toBe(100); // shrunk first, down to its min
    expect(sizes.payee).toBe(300); // not yet touched
  });

  it('keeps protected columns full even when the deficit exceeds flexible slack (table scrolls)', () => {
    // Flexible columns can only give back to their mins; a leftover deficit just means
    // the table will scroll — protected columns must never clip.
    const intrinsic = { date: 400, payee: 120, description: 120, amount: 400 };
    const sizes = distributeColumnWidths(intrinsic, LEAVES, 300, POLICY);
    expect(sizes.date).toBe(400);
    expect(sizes.amount).toBe(400);
    expect(sizes.payee).toBe(100); // floored at min
    expect(sizes.description).toBe(100); // floored at min
  });

  it('falls back to minSize for a column with no measurement', () => {
    const sizes = distributeColumnWidths({ date: 120 }, LEAVES, 0, POLICY);
    expect(sizes).toEqual({ date: 120, payee: 100, description: 100, amount: 90 });
  });
});

describe('sameWidths', () => {
  it('is true for equal records regardless of key order', () => {
    expect(sameWidths({ a: 1, b: 2 }, { b: 2, a: 1 })).toBe(true);
  });

  it('is false when a width differs', () => {
    expect(sameWidths({ a: 1, b: 2 }, { a: 1, b: 3 })).toBe(false);
  });

  it('is false when the key sets differ', () => {
    expect(sameWidths({ a: 1 }, { a: 1, b: 2 })).toBe(false);
    expect(sameWidths({ a: 1, b: 2 }, { a: 1 })).toBe(false);
  });
});
