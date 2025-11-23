import { describe, it, expect } from 'vitest';
import {
  filterCategories,
  getSelectedIndex,
  getNextIndex,
  getPreviousIndex,
} from '../../app/lib/categoryFiltering';
import type { Category } from '../../app/lib/api';

describe('categoryFiltering', () => {
  const mockCategories: Category[] = [
    {
      'db/id': 1,
      'category/name': 'Groceries',
      'category/type': 'expense',
    },
    {
      'db/id': 2,
      'category/name': 'Gas',
      'category/type': 'expense',
    },
    {
      'db/id': 3,
      'category/name': 'Salary',
      'category/type': 'income',
    },
    {
      'db/id': 4,
      'category/name': 'Gym Membership',
      'category/type': 'expense',
    },
  ];

  describe('filterCategories', () => {
    it('returns all categories when filter is empty', () => {
      const result = filterCategories(mockCategories, '');
      expect(result).toEqual(mockCategories);
    });

    it('filters categories by name (case-insensitive)', () => {
      const result = filterCategories(mockCategories, 'gro');
      expect(result).toHaveLength(1);
      expect(result[0]['category/name']).toBe('Groceries');
    });

    it('filters categories with multiple matches', () => {
      const result = filterCategories(mockCategories, 'g');
      expect(result).toHaveLength(3);
      expect(result.map((c) => c['category/name'])).toEqual([
        'Groceries',
        'Gas',
        'Gym Membership',
      ]);
    });

    it('returns empty array when no matches found', () => {
      const result = filterCategories(mockCategories, 'xyz');
      expect(result).toHaveLength(0);
    });

    it('handles special regex characters in filter', () => {
      const categories: Category[] = [
        {
          'db/id': 1,
          'category/name': 'Test (Special)',
          'category/type': 'expense',
        },
      ];
      const result = filterCategories(categories, '(');
      expect(result).toHaveLength(1);
    });
  });

  describe('getSelectedIndex', () => {
    it('returns the index of the selected category', () => {
      const result = getSelectedIndex(mockCategories, 2);
      expect(result).toBe(1);
    });

    it('returns -1 when category is not found', () => {
      const result = getSelectedIndex(mockCategories, 999);
      expect(result).toBe(-1);
    });

    it('returns -1 when categoryId is null', () => {
      const result = getSelectedIndex(mockCategories, null);
      expect(result).toBe(-1);
    });
  });

  describe('getNextIndex', () => {
    it('returns the next index when not at the end', () => {
      const result = getNextIndex(1, 4);
      expect(result).toBe(2);
    });

    it('wraps to 0 when at the end', () => {
      const result = getNextIndex(3, 4);
      expect(result).toBe(0);
    });

    it('handles single item list', () => {
      const result = getNextIndex(0, 1);
      expect(result).toBe(0);
    });

    it('handles empty list', () => {
      const result = getNextIndex(-1, 0);
      expect(result).toBe(-1);
    });
  });

  describe('getPreviousIndex', () => {
    it('returns the previous index when not at the beginning', () => {
      const result = getPreviousIndex(2, 4);
      expect(result).toBe(1);
    });

    it('wraps to end when at the beginning', () => {
      const result = getPreviousIndex(0, 4);
      expect(result).toBe(3);
    });

    it('handles single item list', () => {
      const result = getPreviousIndex(0, 1);
      expect(result).toBe(0);
    });

    it('handles empty list', () => {
      const result = getPreviousIndex(-1, 0);
      expect(result).toBe(-1);
    });
  });
});
