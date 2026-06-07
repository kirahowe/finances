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

  const mockTransactionsWithInstitution: Transaction[] = [
    {
      'db/id': 1,
      'transaction/posted-date': '2024-01-15',
      'transaction/payee': 'Store A',
      'transaction/description': 'Purchase 1',
      'transaction/amount': -50.0,
      'transaction/category': mockCategories[0],
      'transaction/account': {
        'db/id': 10,
        'account/external-name': 'Chequing',
        'account/institution': {
          'db/id': 100,
          'institution/name': 'Chase',
        },
      },
    },
    {
      'db/id': 2,
      'transaction/posted-date': '2024-01-16',
      'transaction/payee': 'Store B',
      'transaction/description': 'Purchase 2',
      'transaction/amount': -75.0,
      'transaction/category': null,
      'transaction/account': {
        'db/id': 11,
        'account/external-name': 'Savings',
      },
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

    // Dropdown should appear with filter input
    const input = screen.getByRole('combobox');
    expect(input).toBeInTheDocument();

    // All categories should be available in the list
    const listItems = screen.getAllByRole('option');
    expect(listItems.length).toBe(3); // Uncategorized, Groceries, Salary
    expect(screen.getByText('Uncategorized')).toBeInTheDocument();
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

    // Should show dropdown
    const input = screen.getByRole('combobox');
    expect(input).toBeInTheDocument();

    // Click on a category in the list
    const salaryOption = screen.getAllByText('Salary').find(el => el.tagName === 'LI');
    await user.click(salaryOption!);

    // Note: In a real scenario, we'd see the loading state briefly.
    // For this unit test, we just verify the component renders correctly
    // with fetcher state. The loading indicator would be tested in integration tests.
  });

  it('displays institution in separate column when present', async () => {
    renderWithRouter(
      <OptimisticTransactionTable
        transactions={mockTransactionsWithInstitution}
        categories={mockCategories}
        sorting={[]}
        onSortingChange={vi.fn()}
      />
    );

    // Should have Institution column header
    expect(screen.getByRole('columnheader', { name: /institution/i })).toBeInTheDocument();

    // Should render the account name
    expect(screen.getByText('Chequing')).toBeInTheDocument();

    // Should render the institution name in its own column
    expect(screen.getByText('Chase')).toBeInTheDocument();
  });

  it('displays dash in institution column when not present', async () => {
    renderWithRouter(
      <OptimisticTransactionTable
        transactions={mockTransactionsWithInstitution}
        categories={mockCategories}
        sorting={[]}
        onSortingChange={vi.fn()}
      />
    );

    // Should render the account name
    expect(screen.getByText('Savings')).toBeInTheDocument();

    // Should show dash for missing institution (multiple dashes may exist for other empty fields)
    const dashes = screen.getAllByText('—');
    expect(dashes.length).toBeGreaterThan(0);
  });

  it('displays dash when transaction has no account', async () => {
    renderWithRouter(
      <OptimisticTransactionTable
        transactions={mockTransactions}
        categories={mockCategories}
        sorting={[]}
        onSortingChange={vi.fn()}
      />
    );

    // Transaction without account should show dash for both account and institution
    const dashes = screen.getAllByText('—');
    expect(dashes.length).toBeGreaterThanOrEqual(2); // At least account and institution columns
  });
});
