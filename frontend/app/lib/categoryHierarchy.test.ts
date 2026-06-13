import { describe, it, expect } from 'vitest';
import {
  orderCategoriesHierarchically,
  categoriesWithChildren,
  buildCategoryDropdownModel,
  type CategoryDropdownEntry,
} from './categoryHierarchy';
import type { Category } from './api';

const optionNames = (entries: CategoryDropdownEntry[]) => entries.map((e) => e.option.name);

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

describe('categoriesWithChildren', () => {
  it('marks a category as a parent only when a present child names it', () => {
    const cats = [cat(1, 'Food'), cat(2, 'Groceries', { parent: 1 }), cat(3, 'Salary')];
    const parents = categoriesWithChildren(cats);
    expect(parents.has(1)).toBe(true);
    expect(parents.has(3)).toBe(false);
  });

  it('ignores parent refs pointing at a missing category', () => {
    expect(categoriesWithChildren([cat(2, 'Groceries', { parent: 999 })]).size).toBe(0);
  });
});

describe('buildCategoryDropdownModel', () => {
  it('leads with Uncategorized and keeps a flat list when there is no hierarchy', () => {
    const cats = [cat(1, 'Food', { sort: 0 }), cat(2, 'Salary', { sort: 1 })];
    const { entries } = buildCategoryDropdownModel(cats, '');
    expect(optionNames(entries)).toEqual(['Uncategorized', 'Food', 'Salary']);
    expect(entries.every((e) => !e.isParent)).toBe(true);
  });

  it('renders parents and children as selectable rows, parents flagged and children at depth 1', () => {
    const cats = [
      cat(1, 'Food', { sort: 0 }),
      cat(2, 'Groceries', { parent: 1, sort: 0 }),
      cat(3, 'Dining', { parent: 1, sort: 1 }),
      cat(4, 'Salary', { sort: 1 }),
    ];
    const { entries } = buildCategoryDropdownModel(cats, '');
    expect(entries.map((e) => `${e.option.name}:${e.depth}${e.isParent ? ':parent' : ''}`)).toEqual([
      'Uncategorized:0',
      'Food:0:parent',
      'Groceries:1',
      'Dining:1',
      'Salary:0',
    ]);
    // Every category, parents included, is a selectable item.
    expect(optionNames(entries)).toContain('Food');
  });

  it('keeps parent context when filtering: a matching child still shows under its parent', () => {
    const cats = [
      cat(1, 'Food', { sort: 0 }),
      cat(2, 'Groceries', { parent: 1, sort: 0 }),
      cat(3, 'Dining', { parent: 1, sort: 1 }),
      cat(4, 'Transport', { sort: 1 }),
      cat(5, 'Gas', { parent: 4, sort: 0 }),
    ];
    const { entries, firstMatchIndex } = buildCategoryDropdownModel(cats, 'gro');
    expect(optionNames(entries)).toEqual(['Uncategorized', 'Food', 'Groceries']);
    // The context parent (Food) does not match, so the highlight lands on the
    // actual match (Groceries at index 2), not the parent.
    expect(firstMatchIndex).toBe(2);
  });

  it('shows a parent on its own when the parent name matches but no child does', () => {
    const cats = [
      cat(1, 'Food', { sort: 0 }),
      cat(2, 'Groceries', { parent: 1, sort: 0 }),
    ];
    const { entries, firstMatchIndex } = buildCategoryDropdownModel(cats, 'food');
    expect(optionNames(entries)).toEqual(['Uncategorized', 'Food']);
    expect(firstMatchIndex).toBe(1);
  });

  it('drops a childless top-level category that does not match the filter', () => {
    const cats = [cat(1, 'Food', { sort: 0 }), cat(2, 'Salary', { sort: 1 })];
    expect(optionNames(buildCategoryDropdownModel(cats, 'sal').entries)).toEqual([
      'Uncategorized',
      'Salary',
    ]);
  });
});
