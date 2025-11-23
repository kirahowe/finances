import { describe, it, expect } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider, useSearchParams } from 'react-router';
import { OptimisticTransactionTable } from '../../app/components/OptimisticTransactionTable';
import type { Transaction, Category } from '../../app/lib/api';
import { parseSortingState, serializeSortingState } from '../../app/lib/sortingState';
import type { SortingState } from '@tanstack/react-table';

describe('URL Sorting State Persistence', () => {
  const mockCategories: Category[] = [
    {
      'db/id': 1,
      'category/name': 'Groceries',
      'category/type': 'expense',
    },
  ];

  const mockTransactions: Transaction[] = [
    {
      'db/id': 1,
      'transaction/posted-date': '2024-01-15',
      'transaction/payee': 'Store A',
      'transaction/description': 'Purchase 1',
      'transaction/amount': -50.0,
      'transaction/category': mockCategories[0],
    },
    {
      'db/id': 2,
      'transaction/posted-date': '2024-01-10',
      'transaction/payee': 'Store B',
      'transaction/description': 'Purchase 2',
      'transaction/amount': -100.0,
      'transaction/category': mockCategories[0],
    },
  ];

  function TestComponent() {
    const [searchParams, setSearchParams] = useSearchParams();
    const sorting = parseSortingState(searchParams.get('sort'));

    const handleSortingChange = (updaterOrValue: SortingState | ((old: SortingState) => SortingState)) => {
      const newSorting = typeof updaterOrValue === 'function'
        ? updaterOrValue(sorting)
        : updaterOrValue;

      const serialized = serializeSortingState(newSorting);
      setSearchParams((prev) => {
        const newParams = new URLSearchParams(prev);
        if (serialized) {
          newParams.set('sort', serialized);
        } else {
          newParams.delete('sort');
        }
        return newParams;
      });
    };

    return (
      <>
        <div data-testid="current-url">{searchParams.toString()}</div>
        <OptimisticTransactionTable
          transactions={mockTransactions}
          categories={mockCategories}
          sorting={sorting}
          onSortingChange={handleSortingChange}
        />
      </>
    );
  }

  const renderWithRouter = (initialUrl = '/') => {
    const router = createMemoryRouter(
      [
        {
          path: '/',
          element: <TestComponent />,
          action: async () => ({ success: true }),
        },
      ],
      {
        initialEntries: [initialUrl],
      }
    );
    return render(<RouterProvider router={router} />);
  };

  it('updates URL when sorting changes', async () => {
    const user = userEvent.setup();
    renderWithRouter();

    // Initially, URL should have no sort param
    let urlDisplay = screen.getByTestId('current-url');
    expect(urlDisplay.textContent).toBe('');

    // Click date header to sort
    const dateHeader = screen.getByRole('columnheader', { name: /date/i });
    await user.click(dateHeader);

    // URL should now have sort parameter
    await waitFor(() => {
      urlDisplay = screen.getByTestId('current-url');
      expect(urlDisplay.textContent).toContain('sort=date');
      expect(urlDisplay.textContent).toContain('asc');
    });
  });

  it('restores sorting state from URL on page load', () => {
    renderWithRouter('/?sort=amount:desc');

    // Amount header should show descending indicator
    const amountHeader = screen.getByRole('columnheader', { name: /amount/i });
    expect(amountHeader.textContent).toContain('↓');
  });

  it('preserves sorting across page refreshes (via URL)', () => {
    // First render with sorting
    const { unmount } = renderWithRouter('/?sort=payee:asc');

    // Verify sorting is applied
    let payeeHeader = screen.getByRole('columnheader', { name: /payee/i });
    expect(payeeHeader.textContent).toContain('↑');

    // Unmount (simulate page unload)
    unmount();

    // Re-render with same URL (simulate page reload)
    renderWithRouter('/?sort=payee:asc');

    // Sorting should still be applied
    payeeHeader = screen.getByRole('columnheader', { name: /payee/i });
    expect(payeeHeader.textContent).toContain('↑');
  });

  it('supports multiple column sorting in URL', () => {
    renderWithRouter('/?sort=date:desc,amount:asc');

    // Both headers should show sort indicators
    const dateHeader = screen.getByRole('columnheader', { name: /date/i });
    const amountHeader = screen.getByRole('columnheader', { name: /amount/i });

    expect(dateHeader.textContent).toContain('↓');
    expect(amountHeader.textContent).toContain('↑');
  });

  it('removes sort param from URL when sorting is cleared', async () => {
    const user = userEvent.setup();
    renderWithRouter('/?sort=date:asc');

    // Initially sorted
    let urlDisplay = screen.getByTestId('current-url');
    expect(urlDisplay.textContent).toContain('sort=date');
    expect(urlDisplay.textContent).toContain('asc');

    // Click date header twice to cycle through asc -> desc -> no sort
    const dateHeader = screen.getByRole('columnheader', { name: /date/i });
    await user.click(dateHeader); // asc -> desc
    await user.click(dateHeader); // desc -> no sort

    // URL should no longer have sort param
    await waitFor(() => {
      urlDisplay = screen.getByTestId('current-url');
      expect(urlDisplay.textContent).not.toContain('sort=');
    });
  });

  it('preserves other URL params when updating sort', async () => {
    const user = userEvent.setup();
    renderWithRouter('/?view=transactions&page=2');

    // Add sorting
    const dateHeader = screen.getByRole('columnheader', { name: /date/i });
    await user.click(dateHeader);

    // URL should have both view, page, and sort params
    await waitFor(() => {
      const urlDisplay = screen.getByTestId('current-url');
      const urlText = urlDisplay.textContent || '';
      expect(urlText).toContain('view=transactions');
      expect(urlText).toContain('page=2');
      expect(urlText).toContain('sort=date');
      expect(urlText).toContain('asc');
    });
  });
});
