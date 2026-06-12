import { describe, it, expect } from 'vitest';
import {
  orderCategoriesHierarchically,
  headerCategoryIds,
  buildCategoryDropdownRows,
  type CategoryDropdownRow,
} from './categoryHierarchy';
import type { Category } from './api';

const optionNames = (rows: CategoryDropdownRow[]) =>
  rows.flatMap((r) => (r.kind === 'option' ? [r.option.name] : []));

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

describe('headerCategoryIds', () => {
  it('marks a category as a header only when a present child names it as parent', () => {
    const cats = [
      cat(1, 'Food'),
      cat(2, 'Groceries', { parent: 1 }),
      cat(3, 'Salary'),
    ];
    const headers = headerCategoryIds(cats);
    expect(headers.has(1)).toBe(true);
    expect(headers.has(3)).toBe(false);
  });

  it('ignores parent refs pointing at a missing category', () => {
    expect(headerCategoryIds([cat(2, 'Groceries', { parent: 999 })]).size).toBe(0);
  });
});

describe('buildCategoryDropdownRows', () => {
  it('leads with Uncategorized and keeps a flat list when there is no hierarchy', () => {
    const cats = [cat(1, 'Food', { sort: 0 }), cat(2, 'Salary', { sort: 1 })];
    const { items, rows } = buildCategoryDropdownRows(cats, '');
    expect(rows.every((r) => r.kind === 'option')).toBe(true);
    expect(optionNames(rows)).toEqual(['Uncategorized', 'Food', 'Salary']);
    // items and option rows stay index-aligned for Downshift navigation.
    expect(items.map((o) => o.name)).toEqual(['Uncategorized', 'Food', 'Salary']);
    rows.forEach((r) => {
      if (r.kind === 'option') expect(items[r.itemIndex]).toBe(r.option);
    });
  });

  it('renders parents as headers with their children indented, headers excluded from items', () => {
    const cats = [
      cat(1, 'Food', { sort: 0 }),
      cat(2, 'Groceries', { parent: 1, sort: 0 }),
      cat(3, 'Dining', { parent: 1, sort: 1 }),
      cat(4, 'Salary', { sort: 1 }),
    ];
    const { items, rows } = buildCategoryDropdownRows(cats, '');
    expect(
      rows.map((r) => (r.kind === 'header' ? `#${r.name}` : `${r.option.name}:${r.depth}`))
    ).toEqual(['Uncategorized:0', '#Food', 'Groceries:1', 'Dining:1', 'Salary:0']);
    // "Food" is a header, so it is not a selectable item.
    expect(items.map((o) => o.name)).toEqual(['Uncategorized', 'Groceries', 'Dining', 'Salary']);
  });

  it('keeps parent context when filtering: header shown only if a child matches', () => {
    const cats = [
      cat(1, 'Food', { sort: 0 }),
      cat(2, 'Groceries', { parent: 1, sort: 0 }),
      cat(3, 'Dining', { parent: 1, sort: 1 }),
      cat(4, 'Transport', { sort: 1 }),
      cat(5, 'Gas', { parent: 4, sort: 0 }),
    ];
    const { rows } = buildCategoryDropdownRows(cats, 'gro');
    expect(
      rows.map((r) => (r.kind === 'header' ? `#${r.name}` : r.option.name))
    ).toEqual(['Uncategorized', '#Food', 'Groceries']);
  });

  it('drops a childless top-level category that does not match the filter', () => {
    const cats = [cat(1, 'Food', { sort: 0 }), cat(2, 'Salary', { sort: 1 })];
    expect(optionNames(buildCategoryDropdownRows(cats, 'sal').rows)).toEqual([
      'Uncategorized',
      'Salary',
    ]);
  });
});
