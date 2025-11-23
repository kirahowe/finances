import { describe, it, expect } from 'vitest';
import {
  calculateSortOrderUpdates,
  optimizeSortOrderUpdates,
} from '../../app/lib/categoryReorder';
import type { Category } from '../../app/lib/api';

const createMockCategory = (id: number, name: string, sortOrder?: number): Category => ({
  'db/id': id,
  'category/name': name,
  'category/type': 'expense',
  'category/ident': `category/${name.toLowerCase()}`,
  ...(sortOrder !== undefined && { 'category/sort-order': sortOrder }),
});

describe('categoryReorder', () => {

  describe('calculateSortOrderUpdates', () => {
    it('calculates sort order based on array position', () => {
      const categories = [
        createMockCategory(3, 'C'),
        createMockCategory(1, 'A'),
        createMockCategory(2, 'B'),
      ];

      const updates = calculateSortOrderUpdates(categories);

      expect(updates).toEqual([
        { id: 3, sortOrder: 0 },
        { id: 1, sortOrder: 1 },
        { id: 2, sortOrder: 2 },
      ]);
    });

    it('handles empty array', () => {
      const updates = calculateSortOrderUpdates([]);
      expect(updates).toEqual([]);
    });

    it('handles single category', () => {
      const categories = [createMockCategory(1, 'A')];
      const updates = calculateSortOrderUpdates(categories);

      expect(updates).toEqual([{ id: 1, sortOrder: 0 }]);
    });
  });

  describe('optimizeSortOrderUpdates', () => {
    it('only includes categories with changed sort order', () => {
      const original = [
        createMockCategory(1, 'A', 0),
        createMockCategory(2, 'B', 1),
        createMockCategory(3, 'C', 2),
      ];

      const updates = [
        { id: 2, sortOrder: 0 },
        { id: 1, sortOrder: 1 },
        { id: 3, sortOrder: 2 },
      ];

      const optimized = optimizeSortOrderUpdates(updates, original);

      // First two changed, last one stayed the same
      expect(optimized).toEqual([
        { id: 2, sortOrder: 0 },
        { id: 1, sortOrder: 1 },
      ]);
    });

    it('includes all updates when all sort orders changed', () => {
      const original = [
        createMockCategory(1, 'A', 0),
        createMockCategory(2, 'B', 1),
        createMockCategory(3, 'C', 2),
      ];

      const updates = [
        { id: 3, sortOrder: 0 },
        { id: 1, sortOrder: 1 },
        { id: 2, sortOrder: 2 },
      ];

      const optimized = optimizeSortOrderUpdates(updates, original);

      expect(optimized).toEqual(updates);
    });

    it('includes updates for categories without previous sort order', () => {
      const original = [
        createMockCategory(1, 'A'), // No sort order
        createMockCategory(2, 'B', 1),
      ];

      const updates = [
        { id: 1, sortOrder: 0 },
        { id: 2, sortOrder: 1 },
      ];

      const optimized = optimizeSortOrderUpdates(updates, original);

      // First one should be included because it didn't have a sort order before
      expect(optimized).toEqual([{ id: 1, sortOrder: 0 }]);
    });

    it('returns empty array when no changes', () => {
      const original = [
        createMockCategory(1, 'A', 0),
        createMockCategory(2, 'B', 1),
      ];

      const updates = [
        { id: 1, sortOrder: 0 },
        { id: 2, sortOrder: 1 },
      ];

      const optimized = optimizeSortOrderUpdates(updates, original);

      expect(optimized).toEqual([]);
    });
  });
});
