import { describe, it, expect } from 'vitest';
import { orderCategoriesHierarchically } from './categoryHierarchy';
import type { Category } from './api';

function cat(
  id: number,
  name: string,
  opts: { parent?: number; sort?: number } = {}
): Category {
  return {
    'db/id': id,
    'category/name': name,
    'category/type': 'expense',
    ...(opts.parent !== undefined ? { 'category/parent': { 'db/id': opts.parent } } : {}),
    ...(opts.sort !== undefined ? { 'category/sort-order': opts.sort } : {}),
  };
}

describe('orderCategoriesHierarchically', () => {
  it('places children directly after their parent at depth 1', () => {
    const cats = [
      cat(1, 'Food', { sort: 0 }),
      cat(2, 'Groceries', { parent: 1, sort: 0 }),
      cat(3, 'Dining', { parent: 1, sort: 1 }),
      cat(4, 'Transport', { sort: 1 }),
      cat(5, 'Gas', { parent: 4, sort: 0 }),
    ];
    const result = orderCategoriesHierarchically(cats);
    expect(result.map((n) => [n.category['category/name'], n.depth])).toEqual([
      ['Food', 0],
      ['Groceries', 1],
      ['Dining', 1],
      ['Transport', 0],
      ['Gas', 1],
    ]);
  });

  it('orders top-level and children by sort-order', () => {
    const cats = [
      cat(4, 'Transport', { sort: 1 }),
      cat(1, 'Food', { sort: 0 }),
      cat(3, 'Dining', { parent: 1, sort: 1 }),
      cat(2, 'Groceries', { parent: 1, sort: 0 }),
    ];
    const result = orderCategoriesHierarchically(cats);
    expect(result.map((n) => n.category['category/name'])).toEqual([
      'Food',
      'Groceries',
      'Dining',
      'Transport',
    ]);
  });

  it('treats categories with no parent as top-level', () => {
    const cats = [cat(1, 'Food'), cat(2, 'Salary')];
    const result = orderCategoriesHierarchically(cats);
    expect(result.every((n) => n.depth === 0)).toBe(true);
    expect(result).toHaveLength(2);
  });

  it('treats a parent ref pointing at a missing category as top-level', () => {
    const cats = [cat(2, 'Groceries', { parent: 999 })];
    const result = orderCategoriesHierarchically(cats);
    expect(result).toEqual([{ category: cats[0], depth: 0 }]);
  });
});
