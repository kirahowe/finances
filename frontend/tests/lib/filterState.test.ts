import { describe, it, expect } from 'vitest';
import {
  serializeFilters,
  parseFilters,
  addFilterValue,
  removeFilterValue,
  toggleFilterValue,
  clearFilterField,
  clearAllFilters,
  hasFilterValues,
  getFilterValueCount,
  hasActiveFilters,
  type FilterState,
} from '../../app/lib/filterState';

describe('filterState', () => {
  describe('serializeFilters', () => {
    it('serializes empty filters to empty string', () => {
      expect(serializeFilters({})).toBe('');
    });

    it('serializes single field with single value', () => {
      const filters: FilterState = { account: [1] };
      expect(serializeFilters(filters)).toBe('account=1');
    });

    it('serializes single field with multiple values', () => {
      const filters: FilterState = { account: [1, 2, 3] };
      expect(serializeFilters(filters)).toBe('account=1%2C2%2C3');
    });

    it('serializes multiple fields', () => {
      const filters: FilterState = { account: [1, 2], category: [3] };
      const result = serializeFilters(filters);
      // Order may vary, check both possibilities
      expect(result === 'account=1%2C2&category=3' || result === 'category=3&account=1%2C2').toBe(true);
    });

    it('omits fields with empty arrays', () => {
      const filters: FilterState = { account: [1], category: [] };
      expect(serializeFilters(filters)).toBe('account=1');
    });

    it('handles string values', () => {
      const filters: FilterState = { status: ['active', 'pending'] };
      expect(serializeFilters(filters)).toBe('status=active%2Cpending');
    });
  });

  describe('parseFilters', () => {
    it('parses null to empty object', () => {
      expect(parseFilters(null)).toEqual({});
    });

    it('parses empty string to empty object', () => {
      expect(parseFilters('')).toEqual({});
    });

    it('parses single field with single numeric value', () => {
      const result = parseFilters('account=1');
      expect(result).toEqual({ account: [1] });
    });

    it('parses single field with multiple numeric values', () => {
      const result = parseFilters('account=1,2,3');
      expect(result).toEqual({ account: [1, 2, 3] });
    });

    it('parses multiple fields', () => {
      const result = parseFilters('account=1,2&category=3');
      expect(result).toEqual({ account: [1, 2], category: [3] });
    });

    it('parses string values', () => {
      const result = parseFilters('status=active,pending');
      expect(result).toEqual({ status: ['active', 'pending'] });
    });

    it('handles mixed numeric and string values', () => {
      const result = parseFilters('id=1,2&name=test');
      expect(result).toEqual({ id: [1, 2], name: ['test'] });
    });
  });

  describe('addFilterValue', () => {
    it('adds value to empty filter', () => {
      const result = addFilterValue({}, 'account', 1);
      expect(result).toEqual({ account: [1] });
    });

    it('adds value to existing field', () => {
      const filters: FilterState = { account: [1] };
      const result = addFilterValue(filters, 'account', 2);
      expect(result).toEqual({ account: [1, 2] });
    });

    it('does not add duplicate value', () => {
      const filters: FilterState = { account: [1, 2] };
      const result = addFilterValue(filters, 'account', 2);
      expect(result).toEqual({ account: [1, 2] });
    });

    it('adds new field to existing filters', () => {
      const filters: FilterState = { account: [1] };
      const result = addFilterValue(filters, 'category', 3);
      expect(result).toEqual({ account: [1], category: [3] });
    });

    it('does not mutate original filter', () => {
      const filters: FilterState = { account: [1] };
      const result = addFilterValue(filters, 'account', 2);
      expect(filters).toEqual({ account: [1] });
      expect(result).toEqual({ account: [1, 2] });
    });
  });

  describe('removeFilterValue', () => {
    it('removes value from filter', () => {
      const filters: FilterState = { account: [1, 2] };
      const result = removeFilterValue(filters, 'account', 2);
      expect(result).toEqual({ account: [1] });
    });

    it('removes field when last value is removed', () => {
      const filters: FilterState = { account: [1] };
      const result = removeFilterValue(filters, 'account', 1);
      expect(result).toEqual({});
    });

    it('keeps other fields when removing from one field', () => {
      const filters: FilterState = { account: [1, 2], category: [3] };
      const result = removeFilterValue(filters, 'account', 2);
      expect(result).toEqual({ account: [1], category: [3] });
    });

    it('handles removing non-existent value', () => {
      const filters: FilterState = { account: [1] };
      const result = removeFilterValue(filters, 'account', 2);
      expect(result).toEqual({ account: [1] });
    });

    it('handles removing from non-existent field', () => {
      const filters: FilterState = { account: [1] };
      const result = removeFilterValue(filters, 'category', 2);
      expect(result).toEqual({ account: [1] });
    });

    it('does not mutate original filter', () => {
      const filters: FilterState = { account: [1, 2] };
      const result = removeFilterValue(filters, 'account', 2);
      expect(filters).toEqual({ account: [1, 2] });
      expect(result).toEqual({ account: [1] });
    });
  });

  describe('toggleFilterValue', () => {
    it('adds value when not present', () => {
      const filters: FilterState = { account: [1] };
      const result = toggleFilterValue(filters, 'account', 2);
      expect(result).toEqual({ account: [1, 2] });
    });

    it('removes value when present', () => {
      const filters: FilterState = { account: [1, 2] };
      const result = toggleFilterValue(filters, 'account', 2);
      expect(result).toEqual({ account: [1] });
    });

    it('adds value to empty filter', () => {
      const result = toggleFilterValue({}, 'account', 1);
      expect(result).toEqual({ account: [1] });
    });
  });

  describe('clearFilterField', () => {
    it('removes field from filters', () => {
      const filters: FilterState = { account: [1, 2], category: [3] };
      const result = clearFilterField(filters, 'account');
      expect(result).toEqual({ category: [3] });
    });

    it('handles clearing non-existent field', () => {
      const filters: FilterState = { account: [1] };
      const result = clearFilterField(filters, 'category');
      expect(result).toEqual({ account: [1] });
    });

    it('returns empty object when clearing last field', () => {
      const filters: FilterState = { account: [1] };
      const result = clearFilterField(filters, 'account');
      expect(result).toEqual({});
    });
  });

  describe('clearAllFilters', () => {
    it('returns empty object', () => {
      expect(clearAllFilters()).toEqual({});
    });
  });

  describe('hasFilterValues', () => {
    it('returns true when field has values', () => {
      const filters: FilterState = { account: [1] };
      expect(hasFilterValues(filters, 'account')).toBe(true);
    });

    it('returns false when field has no values', () => {
      const filters: FilterState = { account: [] };
      expect(hasFilterValues(filters, 'account')).toBe(false);
    });

    it('returns false when field does not exist', () => {
      const filters: FilterState = { account: [1] };
      expect(hasFilterValues(filters, 'category')).toBe(false);
    });
  });

  describe('getFilterValueCount', () => {
    it('returns count of values for field', () => {
      const filters: FilterState = { account: [1, 2, 3] };
      expect(getFilterValueCount(filters, 'account')).toBe(3);
    });

    it('returns 0 for field with no values', () => {
      const filters: FilterState = { account: [] };
      expect(getFilterValueCount(filters, 'account')).toBe(0);
    });

    it('returns 0 for non-existent field', () => {
      const filters: FilterState = { account: [1] };
      expect(getFilterValueCount(filters, 'category')).toBe(0);
    });
  });

  describe('hasActiveFilters', () => {
    it('returns true when filters exist', () => {
      const filters: FilterState = { account: [1] };
      expect(hasActiveFilters(filters)).toBe(true);
    });

    it('returns false when no filters exist', () => {
      expect(hasActiveFilters({})).toBe(false);
    });
  });
});
