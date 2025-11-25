/**
 * Pure functions for pagination logic
 */

export interface PaginationState {
  currentPage: number;
  pageSize: number;
  totalItems: number;
}

export interface PaginationControls {
  totalPages: number;
  canGoFirst: boolean;
  canGoPrevious: boolean;
  canGoNext: boolean;
  canGoLast: boolean;
  visiblePageNumbers: number[];
}

/**
 * Calculate total number of pages
 */
export function calculateTotalPages(totalItems: number, pageSize: number): number {
  if (pageSize <= 0) return 0;
  return Math.ceil(totalItems / pageSize);
}

/**
 * Calculate which page numbers should be visible in the pagination UI
 * Shows up to 5 page numbers centered around the current page
 */
export function calculateVisiblePages(
  currentPage: number,
  totalPages: number,
  maxVisible: number = 5
): number[] {
  if (totalPages === 0) return [];
  if (totalPages <= maxVisible) {
    // Show all pages
    return Array.from({ length: totalPages }, (_, i) => i);
  }

  // Show a sliding window of pages centered on current page
  let start = Math.max(0, currentPage - Math.floor(maxVisible / 2));
  let end = start + maxVisible;

  // Adjust if we're near the end
  if (end > totalPages) {
    end = totalPages;
    start = Math.max(0, end - maxVisible);
  }

  return Array.from({ length: end - start }, (_, i) => start + i);
}

/**
 * Create pagination controls based on current state
 */
export function createPaginationControls(state: PaginationState): PaginationControls {
  const totalPages = calculateTotalPages(state.totalItems, state.pageSize);

  return {
    totalPages,
    canGoFirst: state.currentPage > 0,
    canGoPrevious: state.currentPage > 0,
    canGoNext: state.currentPage < totalPages - 1,
    canGoLast: state.currentPage < totalPages - 1,
    visiblePageNumbers: calculateVisiblePages(state.currentPage, totalPages),
  };
}

/**
 * Navigate to a specific page (with bounds checking)
 */
export function goToPage(state: PaginationState, page: number): number {
  const totalPages = calculateTotalPages(state.totalItems, state.pageSize);
  return Math.max(0, Math.min(page, totalPages - 1));
}

/**
 * Navigate to first page
 */
export function goToFirstPage(): number {
  return 0;
}

/**
 * Navigate to last page
 */
export function goToLastPage(state: PaginationState): number {
  const totalPages = calculateTotalPages(state.totalItems, state.pageSize);
  return Math.max(0, totalPages - 1);
}

/**
 * Navigate to previous page
 */
export function goToPreviousPage(currentPage: number): number {
  return Math.max(0, currentPage - 1);
}

/**
 * Navigate to next page
 */
export function goToNextPage(state: PaginationState): number {
  const totalPages = calculateTotalPages(state.totalItems, state.pageSize);
  return Math.min(state.currentPage + 1, totalPages - 1);
}

/**
 * Change page size and adjust current page if needed
 */
export function changePageSize(
  state: PaginationState,
  newPageSize: number
): { page: number; pageSize: number } {
  if (newPageSize <= 0) {
    throw new Error('Page size must be greater than 0');
  }

  const newTotalPages = calculateTotalPages(state.totalItems, newPageSize);
  const newPage = Math.min(state.currentPage, Math.max(0, newTotalPages - 1));

  return {
    page: newPage,
    pageSize: newPageSize,
  };
}

/**
 * Available page size options
 */
export const PAGE_SIZE_OPTIONS = [25, 50, 100, 250] as const;
export type PageSize = typeof PAGE_SIZE_OPTIONS[number];
