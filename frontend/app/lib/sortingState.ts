import type { SortingState } from '@tanstack/react-table';

/**
 * Serializes a sorting state to a URL-friendly string format.
 * Format: "columnId:direction,columnId:direction,..."
 * Example: "date:desc,amount:asc"
 */
export function serializeSortingState(sorting: SortingState): string {
  if (sorting.length === 0) {
    return '';
  }

  return sorting
    .map((sort) => `${sort.id}:${sort.desc ? 'desc' : 'asc'}`)
    .join(',');
}

/**
 * Parses a URL sorting parameter back into a SortingState.
 * Returns default sort (date ascending) for invalid or empty input.
 */
export function parseSortingState(sortParam: string | null | undefined): SortingState {
  if (!sortParam || sortParam.trim() === '') {
    return [{ id: 'date', desc: false }]; // Default: sort by date ascending
  }

  const parts = sortParam.split(',');
  const result: SortingState = [];

  for (const part of parts) {
    const [id, direction] = part.split(':');

    if (!id || !direction) {
      continue; // Skip malformed parts
    }

    if (direction !== 'asc' && direction !== 'desc') {
      continue; // Skip invalid directions
    }

    result.push({
      id: id.trim(),
      desc: direction === 'desc',
    });
  }

  // If all parts were invalid, return default sort
  if (result.length === 0) {
    return [{ id: 'date', desc: false }];
  }

  return result;
}
