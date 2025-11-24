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

    it('ignores whitespace when filtering - matches across spaces', () => {
      const categories: Category[] = [
        {
          'db/id': 1,
          'category/name': 'Work Income',
          'category/type': 'income',
        },
        {
          'db/id': 2,
          'category/name': 'Food - Groceries',
          'category/type': 'expense',
        },
        {
          'db/id': 3,
          'category/name': 'Gym Membership',
          'category/type': 'expense',
        },
      ];

      // "workinc" should match "Work Income"
      const result1 = filterCategories(categories, 'workinc');
      expect(result1).toHaveLength(1);
      expect(result1[0]['category/name']).toBe('Work Income');

      // "foodgroceries" should match "Food - Groceries"
      const result2 = filterCategories(categories, 'foodgroceries');
      expect(result2).toHaveLength(1);
      expect(result2[0]['category/name']).toBe('Food - Groceries');

      // "gymmem" should match "Gym Membership"
      const result3 = filterCategories(categories, 'gymmem');
      expect(result3).toHaveLength(1);
      expect(result3[0]['category/name']).toBe('Gym Membership');
    });

    it('whitespace-insensitive filtering still matches partial words', () => {
      const categories: Category[] = [
        {
          'db/id': 1,
          'category/name': 'Work Income',
          'category/type': 'income',
        },
      ];

      // "work" should still match "Work Income"
      const result = filterCategories(categories, 'work');
      expect(result).toHaveLength(1);
      expect(result[0]['category/name']).toBe('Work Income');
    });

    it('whitespace-insensitive filtering is case insensitive', () => {
      const categories: Category[] = [
        {
          'db/id': 1,
          'category/name': 'Work Income',
          'category/type': 'income',
        },
      ];

      // "WORKINC" should match "Work Income"
      const result = filterCategories(categories, 'WORKINC');
      expect(result).toHaveLength(1);
      expect(result[0]['category/name']).toBe('Work Income');
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
