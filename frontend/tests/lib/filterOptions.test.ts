import { describe, it, expect } from 'vitest';
import {
  extractFilterOptions,
  filterOptionsByQuery,
  applyFilters,
} from '../../app/lib/filterOptions';

describe('filterOptions', () => {
  describe('extractFilterOptions', () => {
    it('extracts unique values from array', () => {
      const items = [
        { id: 1, category: 'A' },
        { id: 2, category: 'B' },
        { id: 3, category: 'A' },
      ];

      const options = extractFilterOptions(
        items,
        item => item.category
      );

      expect(options).toEqual([
        { value: 'A', label: 'A', count: 2 },
        { value: 'B', label: 'B', count: 1 },
      ]);
    });

    it('uses custom label accessor', () => {
      const items = [
        { id: 1, name: 'Alice' },
        { id: 2, name: 'Bob' },
      ];

      const options = extractFilterOptions(
        items,
        item => item.id,
        item => item.name
      );

      expect(options).toEqual([
        { value: 1, label: 'Alice', count: 1 },
        { value: 2, label: 'Bob', count: 1 },
      ]);
    });

    it('skips null and undefined values', () => {
      const items = [
        { id: 1, category: 'A' },
        { id: 2, category: null },
        { id: 3, category: undefined },
        { id: 4, category: 'B' },
      ];

      const options = extractFilterOptions(
        items,
        item => item.category as string | null | undefined
      );

      expect(options).toEqual([
        { value: 'A', label: 'A', count: 1 },
        { value: 'B', label: 'B', count: 1 },
      ]);
    });

    it('counts duplicates correctly', () => {
      const items = [
        { value: 'A' },
        { value: 'A' },
        { value: 'A' },
        { value: 'B' },
      ];

      const options = extractFilterOptions(
        items,
        item => item.value
      );

      expect(options).toEqual([
        { value: 'A', label: 'A', count: 3 },
        { value: 'B', label: 'B', count: 1 },
      ]);
    });

    it('sorts options alphabetically by label', () => {
      const items = [
        { value: 'Zebra' },
        { value: 'Apple' },
        { value: 'Mango' },
      ];

      const options = extractFilterOptions(
        items,
        item => item.value
      );

      expect(options.map(o => o.label)).toEqual(['Apple', 'Mango', 'Zebra']);
    });

    it('handles numeric values', () => {
      const items = [
        { id: 3 },
        { id: 1 },
        { id: 2 },
        { id: 1 },
      ];

      const options = extractFilterOptions(
        items,
        item => item.id
      );

      expect(options).toEqual([
        { value: 1, label: '1', count: 2 },
        { value: 2, label: '2', count: 1 },
        { value: 3, label: '3', count: 1 },
      ]);
    });

    it('handles empty array', () => {
      const options = extractFilterOptions([], item => item);
      expect(options).toEqual([]);
    });
  });

  describe('filterOptionsByQuery', () => {
    const options = [
      { value: 1, label: 'Apple', count: 1 },
      { value: 2, label: 'Banana', count: 1 },
      { value: 3, label: 'Cherry', count: 1 },
      { value: 4, label: 'Apricot', count: 1 },
    ];

    it('returns all options for empty query', () => {
      expect(filterOptionsByQuery(options, '')).toEqual(options);
      expect(filterOptionsByQuery(options, '   ')).toEqual(options);
    });

    it('filters options case-insensitively', () => {
      const result = filterOptionsByQuery(options, 'app');
      expect(result).toEqual([
        { value: 1, label: 'Apple', count: 1 },
      ]);
    });

    it('filters options with partial match', () => {
      const result = filterOptionsByQuery(options, 'an');
      expect(result).toEqual([
        { value: 2, label: 'Banana', count: 1 },
      ]);
    });

    it('returns empty array when no matches', () => {
      const result = filterOptionsByQuery(options, 'xyz');
      expect(result).toEqual([]);
    });

    it('handles uppercase query', () => {
      const result = filterOptionsByQuery(options, 'CHERRY');
      expect(result).toEqual([
        { value: 3, label: 'Cherry', count: 1 },
      ]);
    });
  });

  describe('applyFilters', () => {
    const items = [
      { id: 1, category: 'A', status: 'active' },
      { id: 2, category: 'B', status: 'active' },
      { id: 3, category: 'A', status: 'inactive' },
      { id: 4, category: 'C', status: 'active' },
    ];

    const accessors = {
      category: (item: typeof items[0]) => item.category,
      status: (item: typeof items[0]) => item.status,
    };

    it('returns all items when no filters', () => {
      const result = applyFilters(items, {}, accessors);
      expect(result).toEqual(items);
    });

    it('filters by single field with single value', () => {
      const result = applyFilters(items, { category: ['A'] }, accessors);
      expect(result).toEqual([
        { id: 1, category: 'A', status: 'active' },
        { id: 3, category: 'A', status: 'inactive' },
      ]);
    });

    it('filters by single field with multiple values (OR logic)', () => {
      const result = applyFilters(items, { category: ['A', 'B'] }, accessors);
      expect(result).toEqual([
        { id: 1, category: 'A', status: 'active' },
        { id: 2, category: 'B', status: 'active' },
        { id: 3, category: 'A', status: 'inactive' },
      ]);
    });

    it('filters by multiple fields (AND logic)', () => {
      const result = applyFilters(
        items,
        { category: ['A'], status: ['active'] },
        accessors
      );
      expect(result).toEqual([
        { id: 1, category: 'A', status: 'active' },
      ]);
    });

    it('returns empty array when no items match', () => {
      const result = applyFilters(items, { category: ['Z'] }, accessors);
      expect(result).toEqual([]);
    });

    it('handles empty filter values', () => {
      const result = applyFilters(items, { category: [] }, accessors);
      expect(result).toEqual(items);
    });

    it('handles unknown filter fields', () => {
      const result = applyFilters(items, { unknown: ['value'] }, accessors);
      expect(result).toEqual(items);
    });

    it('excludes items with null/undefined values', () => {
      const itemsWithNull = [
        { id: 1, category: 'A' },
        { id: 2, category: null },
        { id: 3, category: undefined },
      ];

      const result = applyFilters(
        itemsWithNull,
        { category: ['A'] },
        { category: (item: any) => item.category }
      );

      expect(result).toEqual([{ id: 1, category: 'A' }]);
    });

    it('handles numeric filter values', () => {
      const numericItems = [
        { id: 1, priority: 1 },
        { id: 2, priority: 2 },
        { id: 3, priority: 1 },
      ];

      const result = applyFilters(
        numericItems,
        { priority: [1] },
        { priority: (item: any) => item.priority }
      );

      expect(result).toEqual([
        { id: 1, priority: 1 },
        { id: 3, priority: 1 },
      ]);
    });
  });
});
