import type { Category } from './api';

/**
 * Normalizes a string for filtering by removing all non-alphanumeric characters and converting to lowercase
 */
function normalizeForFiltering(text: string): string {
  return text.toLowerCase().replace(/[^a-z0-9]/g, '');
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
 * Whether a single category matches the filter. A blank filter matches everything.
 */
export function categoryMatchesFilter(category: Category, filter: string): boolean {
  return textMatchesFilter(category['category/name'], filter);
}
