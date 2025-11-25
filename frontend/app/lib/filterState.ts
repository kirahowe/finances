/**
 * Pure functions for managing filter state
 * Filters are represented as a map of field names to arrays of selected values
 */

export type FilterValue = string | number;
export type FilterState = Record<string, FilterValue[]>;

/**
 * Serialize filter state to URL query string format
 * Example: { account: [1, 2], category: [3] } => "account=1,2&category=3"
 */
export function serializeFilters(filters: FilterState): string {
  const params = new URLSearchParams();

  Object.entries(filters).forEach(([key, values]) => {
    if (values.length > 0) {
      params.set(key, values.join(','));
    }
  });

  return params.toString();
}

/**
 * Parse filter state from URL query string
 * Example: "account=1,2&category=3" => { account: [1, 2], category: [3] }
 */
export function parseFilters(queryString: string | null): FilterState {
  if (!queryString) {
    return {};
  }

  const params = new URLSearchParams(queryString);
  const filters: FilterState = {};

  params.forEach((value, key) => {
    const values = value.split(',').map(v => {
      // Try to parse as number, otherwise keep as string
      const num = Number(v);
      return isNaN(num) ? v : num;
    });
    filters[key] = values;
  });

  return filters;
}

/**
 * Add a value to a filter field
 */
export function addFilterValue(
  filters: FilterState,
  field: string,
  value: FilterValue
): FilterState {
  const currentValues = filters[field] || [];

  // Don't add if already present
  if (currentValues.includes(value)) {
    return filters;
  }

  return {
    ...filters,
    [field]: [...currentValues, value],
  };
}

/**
 * Remove a value from a filter field
 */
export function removeFilterValue(
  filters: FilterState,
  field: string,
  value: FilterValue
): FilterState {
  const currentValues = filters[field] || [];
  const newValues = currentValues.filter(v => v !== value);

  if (newValues.length === 0) {
    // Remove the field entirely if no values left
    const { [field]: _, ...rest } = filters;
    return rest;
  }

  return {
    ...filters,
    [field]: newValues,
  };
}

/**
 * Toggle a value in a filter field (add if not present, remove if present)
 */
export function toggleFilterValue(
  filters: FilterState,
  field: string,
  value: FilterValue
): FilterState {
  const currentValues = filters[field] || [];

  if (currentValues.includes(value)) {
    return removeFilterValue(filters, field, value);
  } else {
    return addFilterValue(filters, field, value);
  }
}

/**
 * Clear all values for a specific filter field
 */
export function clearFilterField(
  filters: FilterState,
  field: string
): FilterState {
  const { [field]: _, ...rest } = filters;
  return rest;
}

/**
 * Clear all filters
 */
export function clearAllFilters(): FilterState {
  return {};
}

/**
 * Check if a filter field has any values selected
 */
export function hasFilterValues(filters: FilterState, field: string): boolean {
  return (filters[field]?.length || 0) > 0;
}

/**
 * Get the count of selected values for a filter field
 */
export function getFilterValueCount(filters: FilterState, field: string): number {
  return filters[field]?.length || 0;
}

/**
 * Check if any filters are active
 */
export function hasActiveFilters(filters: FilterState): boolean {
  return Object.keys(filters).length > 0;
}
