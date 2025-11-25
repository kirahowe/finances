import { describe, it, expect } from 'vitest';
import {
  calculateTotalPages,
  calculateVisiblePages,
  createPaginationControls,
  goToPage,
  goToFirstPage,
  goToLastPage,
  goToPreviousPage,
  goToNextPage,
  changePageSize,
  type PaginationState,
} from '../../app/lib/pagination';

describe('pagination', () => {
  describe('calculateTotalPages', () => {
    it('calculates correct number of pages', () => {
      expect(calculateTotalPages(100, 25)).toBe(4);
      expect(calculateTotalPages(101, 25)).toBe(5);
      expect(calculateTotalPages(99, 25)).toBe(4);
    });

    it('handles edge cases', () => {
      expect(calculateTotalPages(0, 25)).toBe(0);
      expect(calculateTotalPages(1, 25)).toBe(1);
      expect(calculateTotalPages(25, 25)).toBe(1);
    });

    it('handles invalid page size', () => {
      expect(calculateTotalPages(100, 0)).toBe(0);
      expect(calculateTotalPages(100, -1)).toBe(0);
    });
  });

  describe('calculateVisiblePages', () => {
    it('shows all pages when total is less than max', () => {
      expect(calculateVisiblePages(0, 3, 5)).toEqual([0, 1, 2]);
      expect(calculateVisiblePages(1, 3, 5)).toEqual([0, 1, 2]);
    });

    it('shows sliding window centered on current page', () => {
      // Current page 5, total 10, show 5 pages: [3, 4, 5, 6, 7]
      expect(calculateVisiblePages(5, 10, 5)).toEqual([3, 4, 5, 6, 7]);
    });

    it('adjusts window when near the start', () => {
      expect(calculateVisiblePages(0, 10, 5)).toEqual([0, 1, 2, 3, 4]);
      expect(calculateVisiblePages(1, 10, 5)).toEqual([0, 1, 2, 3, 4]);
    });

    it('adjusts window when near the end', () => {
      expect(calculateVisiblePages(9, 10, 5)).toEqual([5, 6, 7, 8, 9]);
      expect(calculateVisiblePages(8, 10, 5)).toEqual([5, 6, 7, 8, 9]);
    });

    it('handles empty pages', () => {
      expect(calculateVisiblePages(0, 0, 5)).toEqual([]);
    });
  });

  describe('createPaginationControls', () => {
    it('creates controls for first page', () => {
      const state: PaginationState = {
        currentPage: 0,
        pageSize: 25,
        totalItems: 100,
      };

      const controls = createPaginationControls(state);

      expect(controls.totalPages).toBe(4);
      expect(controls.canGoFirst).toBe(false);
      expect(controls.canGoPrevious).toBe(false);
      expect(controls.canGoNext).toBe(true);
      expect(controls.canGoLast).toBe(true);
      expect(controls.visiblePageNumbers).toEqual([0, 1, 2, 3]);
    });

    it('creates controls for middle page', () => {
      const state: PaginationState = {
        currentPage: 5,
        pageSize: 25,
        totalItems: 250,
      };

      const controls = createPaginationControls(state);

      expect(controls.totalPages).toBe(10);
      expect(controls.canGoFirst).toBe(true);
      expect(controls.canGoPrevious).toBe(true);
      expect(controls.canGoNext).toBe(true);
      expect(controls.canGoLast).toBe(true);
      expect(controls.visiblePageNumbers).toEqual([3, 4, 5, 6, 7]);
    });

    it('creates controls for last page', () => {
      const state: PaginationState = {
        currentPage: 3,
        pageSize: 25,
        totalItems: 100,
      };

      const controls = createPaginationControls(state);

      expect(controls.totalPages).toBe(4);
      expect(controls.canGoFirst).toBe(true);
      expect(controls.canGoPrevious).toBe(true);
      expect(controls.canGoNext).toBe(false);
      expect(controls.canGoLast).toBe(false);
    });
  });

  describe('goToPage', () => {
    it('navigates to valid page', () => {
      const state: PaginationState = {
        currentPage: 0,
        pageSize: 25,
        totalItems: 100,
      };

      expect(goToPage(state, 2)).toBe(2);
    });

    it('clamps to lower bound', () => {
      const state: PaginationState = {
        currentPage: 0,
        pageSize: 25,
        totalItems: 100,
      };

      expect(goToPage(state, -1)).toBe(0);
    });

    it('clamps to upper bound', () => {
      const state: PaginationState = {
        currentPage: 0,
        pageSize: 25,
        totalItems: 100,
      };

      expect(goToPage(state, 10)).toBe(3);
    });
  });

  describe('goToFirstPage', () => {
    it('returns 0', () => {
      expect(goToFirstPage()).toBe(0);
    });
  });

  describe('goToLastPage', () => {
    it('returns last page index', () => {
      const state: PaginationState = {
        currentPage: 0,
        pageSize: 25,
        totalItems: 100,
      };

      expect(goToLastPage(state)).toBe(3);
    });

    it('handles empty results', () => {
      const state: PaginationState = {
        currentPage: 0,
        pageSize: 25,
        totalItems: 0,
      };

      expect(goToLastPage(state)).toBe(0);
    });
  });

  describe('goToPreviousPage', () => {
    it('goes to previous page', () => {
      expect(goToPreviousPage(2)).toBe(1);
    });

    it('clamps at first page', () => {
      expect(goToPreviousPage(0)).toBe(0);
    });
  });

  describe('goToNextPage', () => {
    it('goes to next page', () => {
      const state: PaginationState = {
        currentPage: 1,
        pageSize: 25,
        totalItems: 100,
      };

      expect(goToNextPage(state)).toBe(2);
    });

    it('clamps at last page', () => {
      const state: PaginationState = {
        currentPage: 3,
        pageSize: 25,
        totalItems: 100,
      };

      expect(goToNextPage(state)).toBe(3);
    });
  });

  describe('changePageSize', () => {
    it('changes page size and resets to first page', () => {
      const state: PaginationState = {
        currentPage: 0,
        pageSize: 25,
        totalItems: 100,
      };

      const result = changePageSize(state, 50);

      expect(result.pageSize).toBe(50);
      expect(result.page).toBe(0);
    });

    it('adjusts current page if it becomes out of bounds', () => {
      const state: PaginationState = {
        currentPage: 3,
        pageSize: 25,
        totalItems: 100,
      };

      // Changing to 50 per page means only 2 pages total
      // Page 3 is now out of bounds, should adjust to page 1
      const result = changePageSize(state, 50);

      expect(result.pageSize).toBe(50);
      expect(result.page).toBe(1);
    });

    it('throws error for invalid page size', () => {
      const state: PaginationState = {
        currentPage: 0,
        pageSize: 25,
        totalItems: 100,
      };

      expect(() => changePageSize(state, 0)).toThrow('Page size must be greater than 0');
      expect(() => changePageSize(state, -1)).toThrow('Page size must be greater than 0');
    });
  });
});
