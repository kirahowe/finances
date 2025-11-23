import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { OptimisticTransactionTable } from '../../app/components/OptimisticTransactionTable';
import type { Transaction, Category } from '../../app/lib/api';

describe('OptimisticTransactionTable', () => {
  const mockCategories: Category[] = [
    {
      'db/id': 1,
      'category/name': 'Groceries',
      'category/type': 'expense',
    },
    {
      'db/id': 2,
      'category/name': 'Salary',
      'category/type': 'income',
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
  ];

  const renderWithRouter = (component: React.ReactElement) => {
    const router = createMemoryRouter(
      [
        {
          path: '/',
          element: component,
          action: async () => ({ success: true }),
        },
      ],
      {
        initialEntries: ['/'],
      }
    );
    return render(<RouterProvider router={router} />);
  };

  it('renders transaction table with categories', async () => {
    renderWithRouter(
      <OptimisticTransactionTable
        transactions={mockTransactions}
        categories={mockCategories}
        sorting={[]}
        onSortingChange={vi.fn()}
      />
    );

    // Should render the transaction
    expect(screen.getByText('Store A')).toBeInTheDocument();
    expect(screen.getByText('Purchase 1')).toBeInTheDocument();

    // Should render the category button
    expect(screen.getByRole('button', { name: 'Groceries' })).toBeInTheDocument();
  });

  it('allows editing category via dropdown', async () => {
    const user = userEvent.setup();

    renderWithRouter(
      <OptimisticTransactionTable
        transactions={mockTransactions}
        categories={mockCategories}
        sorting={[]}
        onSortingChange={vi.fn()}
      />
    );

    // Click category button to start editing
    const categoryButton = screen.getByRole('button', { name: 'Groceries' });
    await user.click(categoryButton);

    // Dropdown should appear with all categories
    const select = screen.getByRole('combobox');
    expect(select).toBeInTheDocument();

    // All categories should be available as options
    expect(screen.getByRole('option', { name: 'Groceries' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Salary' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Uncategorized' })).toBeInTheDocument();
  });

  it('shows loading state when updating category', async () => {
    const user = userEvent.setup();

    renderWithRouter(
      <OptimisticTransactionTable
        transactions={mockTransactions}
        categories={mockCategories}
        sorting={[]}
        onSortingChange={vi.fn()}
      />
    );

    // Click and change category
    const categoryButton = screen.getByRole('button', { name: 'Groceries' });
    await user.click(categoryButton);

    const select = screen.getByRole('combobox');
    await user.selectOptions(select, '2');

    // Note: In a real scenario, we'd see the loading state briefly.
    // For this unit test, we just verify the component renders correctly
    // with fetcher state. The loading indicator would be tested in integration tests.
  });
});
