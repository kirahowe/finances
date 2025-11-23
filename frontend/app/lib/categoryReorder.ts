import type { Category } from './api';

/**
 * Category-specific reordering utilities.
 *
 * Focused on:
 * - Calculating sort order updates for categories
 * - Optimizing batch updates
 * - Pure functions with no side effects
 */

/**
 * Represents a batch of sort order updates to be sent to the API
 */
export interface SortOrderUpdate {
  id: number;
  sortOrder: number;
}

/**
 * Calculates the sort order updates needed after a reorder operation.
 * Returns all categories with their new sort order based on array position.
 * Pure function - no side effects.
 */
export function calculateSortOrderUpdates(
  reorderedCategories: Category[]
): SortOrderUpdate[] {
  return reorderedCategories.map((category, index) => ({
    id: category['db/id'],
    sortOrder: index,
  }));
}

/**
 * Optimizes sort order updates by only including changed values.
 * Pure function - no side effects.
 */
export function optimizeSortOrderUpdates(
  updates: SortOrderUpdate[],
  originalCategories: Category[]
): SortOrderUpdate[] {
  return updates.filter((update) => {
    const originalCategory = originalCategories.find(c => c['db/id'] === update.id);
    const originalSortOrder = originalCategory?.['category/sort-order'];
    // Include if sort order is different or was undefined
    return originalSortOrder !== update.sortOrder;
  });
}
