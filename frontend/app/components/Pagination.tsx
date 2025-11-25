import {
  createPaginationControls,
  goToFirstPage,
  goToLastPage,
  goToPreviousPage,
  goToNextPage,
  goToPage,
  changePageSize,
  PAGE_SIZE_OPTIONS,
  type PageSize,
  type PaginationState,
} from '../lib/pagination';

interface PaginationProps {
  currentPage: number;
  pageSize: PageSize;
  totalItems: number;
  onPageChange: (page: number) => void;
  onPageSizeChange: (pageSize: PageSize) => void;
}

export function Pagination({
  currentPage,
  pageSize,
  totalItems,
  onPageChange,
  onPageSizeChange,
}: PaginationProps) {
  const state: PaginationState = {
    currentPage,
    pageSize,
    totalItems,
  };

  const controls = createPaginationControls(state);

  const handlePageSizeChange = (newPageSize: PageSize) => {
    const result = changePageSize(state, newPageSize);
    onPageSizeChange(result.pageSize as PageSize);
    if (result.page !== currentPage) {
      onPageChange(result.page);
    }
  };

  return (
    <div className="pagination">
      {/* Left side: Page size buttons */}
      <div className="pagination-size-controls">
        {PAGE_SIZE_OPTIONS.map((size) => (
          <button
            key={size}
            className={`button ${
              size === pageSize ? 'button-primary' : 'button-secondary'
            } pagination-size-button`}
            onClick={() => handlePageSizeChange(size)}
          >
            {size}
          </button>
        ))}
      </div>

      {/* Right side: Page navigation */}
      <div className="pagination-navigation">
        {/* First and Previous buttons */}
        <button
          className="button button-secondary pagination-nav-button"
          onClick={() => onPageChange(goToFirstPage())}
          disabled={!controls.canGoFirst}
          title="First page"
        >
          «
        </button>
        <button
          className="button button-secondary pagination-nav-button"
          onClick={() => onPageChange(goToPreviousPage(currentPage))}
          disabled={!controls.canGoPrevious}
          title="Previous page"
        >
          ‹
        </button>

        {/* Page number buttons */}
        {controls.visiblePageNumbers.map((pageNum) => (
          <button
            key={pageNum}
            className={`button ${
              pageNum === currentPage ? 'button-primary' : 'button-secondary'
            } pagination-page-button`}
            onClick={() => onPageChange(goToPage(state, pageNum))}
          >
            {pageNum + 1}
          </button>
        ))}

        {/* Next and Last buttons */}
        <button
          className="button button-secondary pagination-nav-button"
          onClick={() => onPageChange(goToNextPage(state))}
          disabled={!controls.canGoNext}
          title="Next page"
        >
          ›
        </button>
        <button
          className="button button-secondary pagination-nav-button"
          onClick={() => onPageChange(goToLastPage(state))}
          disabled={!controls.canGoLast}
          title="Last page"
        >
          »
        </button>
      </div>
    </div>
  );
}
