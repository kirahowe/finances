/**
 * Pure functions for extracting filter options from data
 * These functions are generic and work with any data structure
 */

import type { FilterValue } from './filterState';

export interface FilterOption {
  value: FilterValue;
  label: string;
  count?: number;
}

/**
 * Extract unique filter options from an array of items
 * Uses an accessor function to get the value from each item
 */
export function extractFilterOptions<T>(
  items: T[],
  accessor: (item: T) => FilterValue | null | undefined,
  labelAccessor?: (item: T) => string
): FilterOption[] {
  const optionMap = new Map<FilterValue, { label: string; count: number }>();

  items.forEach(item => {
    const value = accessor(item);
    if (value !== null && value !== undefined) {
      const label = labelAccessor ? labelAccessor(item) : String(value);
      const existing = optionMap.get(value);

      if (existing) {
        existing.count += 1;
      } else {
        optionMap.set(value, { label, count: 1 });
      }
    }
  });

  return Array.from(optionMap.entries())
    .map(([value, { label, count }]) => ({ value, label, count }))
    .sort((a, b) => a.label.localeCompare(b.label));
}

/**
 * Filter options by a search query
 */
export function filterOptionsByQuery(
  options: FilterOption[],
  query: string
): FilterOption[] {
  if (!query.trim()) {
    return options;
  }

  const lowerQuery = query.toLowerCase();
  return options.filter(option =>
    option.label.toLowerCase().includes(lowerQuery)
  );
}

/**
 * Apply filters to a dataset
 * Returns items that match ALL active filters (AND logic within each filter, OR logic across values)
 */
export function applyFilters<T>(
  items: T[],
  filters: Record<string, FilterValue[]>,
  accessors: Record<string, (item: T) => FilterValue | null | undefined>
): T[] {
  // If no filters, return all items
  if (Object.keys(filters).length === 0) {
    return items;
  }

  return items.filter(item => {
    // Item must match ALL filter fields (AND logic)
    return Object.entries(filters).every(([field, values]) => {
      if (values.length === 0) {
        return true; // Empty filter doesn't restrict
      }

      const accessor = accessors[field];
      if (!accessor) {
        return true; // Unknown field doesn't restrict
      }

      const itemValue = accessor(item);
      if (itemValue === null || itemValue === undefined) {
        return false; // Item missing this field doesn't match
      }

      // Item must match ANY of the values for this field (OR logic)
      return values.includes(itemValue);
    });
  });
}
