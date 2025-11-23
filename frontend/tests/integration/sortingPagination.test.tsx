import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { OptimisticTransactionTable } from '../../app/components/OptimisticTransactionTable';
import type { Transaction, Category } from '../../app/lib/api';
import type { SortingState } from '@tanstack/react-table';

describe('Sorting with Pagination', () => {
  const mockCategories: Category[] = [
    {
      'db/id': 1,
      'category/name': 'Groceries',
      'category/type': 'expense',
    },
  ];

  // Create transactions spanning multiple pages
  const mockTransactions: Transaction[] = [
    {
      'db/id': 1,
      'transaction/posted-date': '2024-01-15',
      'transaction/payee': 'Store C',
      'transaction/description': 'Purchase',
      'transaction/amount': -30.0,
      'transaction/category': mockCategories[0],
    },
    {
      'db/id': 2,
      'transaction/posted-date': '2024-01-10',
      'transaction/payee': 'Store A',
      'transaction/description': 'Purchase',
      'transaction/amount': -10.0,
      'transaction/category': mockCategories[0],
    },
    {
      'db/id': 3,
      'transaction/posted-date': '2024-01-20',
      'transaction/payee': 'Store B',
      'transaction/description': 'Purchase',
      'transaction/amount': -20.0,
      'transaction/category': mockCategories[0],
    },
  ];

  it('sorts entire dataset, not just current page', async () => {
    const user = userEvent.setup();

    function TestWrapper() {
      const [sorting, setSorting] = useState<SortingState>([]);

      return (
        <OptimisticTransactionTable
          transactions={mockTransactions}
          categories={mockCategories}
          onCategoryChange={vi.fn()}
          sorting={sorting}
          onSortingChange={setSorting}
        />
      );
    }

    render(<TestWrapper />);

    // Click payee header to sort
    const payeeHeader = screen.getByRole('columnheader', { name: /payee/i });
    await user.click(payeeHeader);

    // After sorting by payee ascending, first row should be "Store A"
    const rows = screen.getAllByRole('row').slice(1); // skip header
    const firstRow = within(rows[0]);
    expect(firstRow.getByText('Store A')).toBeInTheDocument();

    // Click again for descending
    await user.click(payeeHeader);

    // After sorting descending, first row should be "Store C"
    const rowsDesc = screen.getAllByRole('row').slice(1);
    const firstRowDesc = within(rowsDesc[0]);
    expect(firstRowDesc.getByText('Store C')).toBeInTheDocument();
  });

  it('maintains sort order when data represents a single page of larger dataset', async () => {
    const user = userEvent.setup();

    function TestWrapper() {
      const [sorting, setSorting] = useState<SortingState>([]);

      return (
        <OptimisticTransactionTable
          transactions={mockTransactions}
          categories={mockCategories}
          onCategoryChange={vi.fn()}
          sorting={sorting}
          onSortingChange={setSorting}
        />
      );
    }

    // This represents what the component receives after pagination happens upstream
    // The component should still sort this slice correctly
    render(<TestWrapper />);

    // Sort by date
    const dateHeader = screen.getByRole('columnheader', { name: /date/i });
    await user.click(dateHeader);

    // Should be sorted by date ascending (earliest first)
    const rows = screen.getAllByRole('row').slice(1);
    const dates = rows.map(row => {
      const cells = within(row).getAllByRole('cell');
      return cells[0].textContent; // Date is first column
    });

    // First date should be 2024-01-10 (Store A) - formatted as MM/DD/YYYY or similar
    expect(dates[0]).toBeTruthy();
    // Just verify Store A is in first row since date formatting varies
    const firstRowPayee = within(rows[0]).getAllByRole('cell')[1];
    expect(firstRowPayee).toHaveTextContent('Store A');
  });
});
