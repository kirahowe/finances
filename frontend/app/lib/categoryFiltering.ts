import type { Category } from './api';

/**
 * Normalizes a string for filtering by removing all non-alphanumeric characters and converting to lowercase
 */
function normalizeForFiltering(text: string): string {
  return text.toLowerCase().replace(/[^a-z0-9]/g, '');
}

function matchesQuery(category: Category, normalizedQuery: string): boolean {
  return normalizeForFiltering(category['category/name']).includes(normalizedQuery);
}

/**
 * Whether a piece of text matches the filter, using the same normalization as
 * category filtering. A blank filter matches everything.
 */
export function textMatchesFilter(text: string, filter: string): boolean {
  if (!filter.trim()) {
    return true;
  }
  return normalizeForFiltering(text).includes(normalizeForFiltering(filter));
}

/**
 * Whether a single category matches the filter. A blank filter matches everything
 * (unlike hasMatchingCategory, which only cares about a non-empty query).
 */
export function categoryMatchesFilter(category: Category, filter: string): boolean {
  return textMatchesFilter(category['category/name'], filter);
}

/**
 * Filters categories by name (case-insensitive and whitespace-insensitive)
 */
export function filterCategories(
  categories: Category[],
  filter: string
): Category[] {
  if (!filter.trim()) {
    return categories;
  }

  const normalizedQuery = normalizeForFiltering(filter);
  return categories.filter((category) => matchesQuery(category, normalizedQuery));
}

/**
 * Whether any category matches the filter. Cheaper than filterCategories when
 * only existence matters: it short-circuits on the first hit and builds no
 * array. An empty filter is treated as "no match to prefer" (not a wildcard).
 */
export function hasMatchingCategory(
  categories: Category[],
  filter: string
): boolean {
  if (!filter.trim()) {
    return false;
  }

  const normalizedQuery = normalizeForFiltering(filter);
  return categories.some((category) => matchesQuery(category, normalizedQuery));
}

/**
 * Gets the index of the selected category in the list
 */
export function getSelectedIndex(
  categories: Category[],
  categoryId: number | null
): number {
  if (categoryId === null) {
    return -1;
  }

  return categories.findIndex((cat) => cat['db/id'] === categoryId);
}
