import type { Category } from './api';

/**
 * Filters categories by name (case-insensitive)
 */
export function filterCategories(
  categories: Category[],
  filter: string
): Category[] {
  if (!filter) {
    return categories;
  }

  const lowerFilter = filter.toLowerCase();
  return categories.filter((category) =>
    category['category/name'].toLowerCase().includes(lowerFilter)
  );
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
