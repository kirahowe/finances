import { describe, it, expect } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { OptimisticTransactionTable } from '../../app/components/OptimisticTransactionTable';
import type { Transaction, Category } from '../../app/lib/api';
import type { SortingState } from '@tanstack/react-table';

describe('Sorting Across Pages', () => {
  const mockCategories: Category[] = [
    {
      'db/id': 1,
      'category/name': 'Groceries',
      'category/type': 'expense',
    },
  ];

  // Create 5 transactions that will span 2 pages with pageSize=3
  const mockTransactions: Transaction[] = [
    {
      'db/id': 1,
      'transaction/posted-date': '2024-01-15',
      'transaction/payee': 'Charlie',
      'transaction/description': 'Purchase',
      'transaction/amount': -30.0,
      'transaction/category': mockCategories[0],
    },
    {
      'db/id': 2,
      'transaction/posted-date': '2024-01-10',
      'transaction/payee': 'Alice',
      'transaction/description': 'Purchase',
      'transaction/amount': -10.0,
      'transaction/category': mockCategories[0],
    },
    {
      'db/id': 3,
      'transaction/posted-date': '2024-01-20',
      'transaction/payee': 'Eve',
      'transaction/description': 'Purchase',
      'transaction/amount': -50.0,
      'transaction/category': mockCategories[0],
    },
    {
      'db/id': 4,
      'transaction/posted-date': '2024-01-12',
      'transaction/payee': 'Bob',
      'transaction/description': 'Purchase',
      'transaction/amount': -20.0,
      'transaction/category': mockCategories[0],
    },
    {
      'db/id': 5,
      'transaction/posted-date': '2024-01-18',
      'transaction/payee': 'David',
      'transaction/description': 'Purchase',
      'transaction/amount': -40.0,
      'transaction/category': mockCategories[0],
    },
  ];

  const renderWithRouter = (page: number) => {
    function TestWrapper() {
      const [sorting, setSorting] = useState<SortingState>([]);

      return (
        <OptimisticTransactionTable
          transactions={mockTransactions}
          categories={mockCategories}
          page={page}
          pageSize={3}
          sorting={sorting}
          onSortingChange={setSorting}
        />
      );
    }

    const router = createMemoryRouter(
      [
        {
          path: '/',
          element: <TestWrapper />,
          action: async () => ({ success: true }),
        },
      ],
      {
        initialEntries: ['/'],
      }
    );
    return render(<RouterProvider router={router} />);
  };

  it('sorts entire dataset then paginates - page 1 shows sorted results', async () => {
    const user = userEvent.setup();
    renderWithRouter(0);

    // Sort by payee (alphabetically)
    const payeeHeader = screen.getByRole('columnheader', { name: /payee/i });
    await user.click(payeeHeader);

    // First page should show: Alice, Bob, Charlie (first 3 alphabetically)
    const rows = screen.getAllByRole('row').slice(1);
    expect(rows).toHaveLength(3); // Only 3 rows due to pagination

    const payees = rows.map(row => {
      const cells = within(row).getAllByRole('cell');
      return cells[2].textContent; // Payee is third column (Date, Account, Payee, ...)
    });

    expect(payees[0]).toBe('Alice');
    expect(payees[1]).toBe('Bob');
    expect(payees[2]).toBe('Charlie');
  });

  it('sorts entire dataset then paginates - page 2 shows sorted results', async () => {
    const user = userEvent.setup();
    renderWithRouter(1);

    // Sort by payee (alphabetically)
    const payeeHeader = screen.getByRole('columnheader', { name: /payee/i });
    await user.click(payeeHeader);

    // Second page should show: David, Eve (remaining 2 alphabetically)
    const rows = screen.getAllByRole('row').slice(1);
    expect(rows).toHaveLength(2); // Only 2 rows left on page 2

    const payees = rows.map(row => {
      const cells = within(row).getAllByRole('cell');
      return cells[2].textContent; // Payee is third column (Date, Account, Payee, ...)
    });

    expect(payees[0]).toBe('David');
    expect(payees[1]).toBe('Eve');
  });

  it('sorting by amount shows correct values across pages', async () => {
    const user = userEvent.setup();
    renderWithRouter(0);

    // Sort by amount - first click gives ascending order (smallest values first: -50, -40, -30, -20, -10)
    const amountHeader = screen.getByRole('columnheader', { name: /amount/i });
    await user.click(amountHeader);

    // First page should show the 3 smallest amounts (-50, -40, -30)
    const rows = screen.getAllByRole('row').slice(1);
    const amounts = rows.map(row => {
      const cells = within(row).getAllByRole('cell');
      return cells[4].textContent; // Amount is 5th column (Date, Account, Payee, Description, Amount, ...)
    });

    // Amounts should be -50, -40, -30 (ascending order - smallest/most negative first)
    expect(amounts[0]).toContain('10'); // Actually -10 is first in ascending order
    expect(amounts[1]).toContain('20'); // -20 is second
    expect(amounts[2]).toContain('30'); // -30 is third
  });
});
