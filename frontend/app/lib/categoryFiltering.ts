import type { Category } from './api';

/**
 * Normalizes a string for filtering by removing all non-alphanumeric characters and converting to lowercase
 */
function normalizeForFiltering(text: string): string {
  return text.toLowerCase().replace(/[^a-z0-9]/g, '');
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
  return categories.filter((category) => {
    const normalizedName = normalizeForFiltering(category['category/name']);
    return normalizedName.includes(normalizedQuery);
  });
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

/**
 * Gets the next index in the list, wrapping around to 0 at the end
 */
export function getNextIndex(currentIndex: number, listLength: number): number {
  if (listLength === 0) {
    return -1;
  }

  if (currentIndex === -1) {
    return 0;
  }

  return (currentIndex + 1) % listLength;
}

/**
 * Gets the previous index in the list, wrapping around to the end at the beginning
 */
export function getPreviousIndex(
  currentIndex: number,
  listLength: number
): number {
  if (listLength === 0) {
    return -1;
  }

  if (currentIndex === -1) {
    return listLength - 1;
  }

  if (currentIndex === 0) {
    return listLength - 1;
  }

  return currentIndex - 1;
}
