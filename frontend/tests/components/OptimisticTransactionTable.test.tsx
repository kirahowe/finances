import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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

  let onCategoryChange: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    onCategoryChange = vi.fn();
  });

  it('updates UI immediately when category is changed (optimistic update)', async () => {
    const user = userEvent.setup();

    render(
      <OptimisticTransactionTable
        transactions={mockTransactions}
        categories={mockCategories}
        onCategoryChange={onCategoryChange}
      />
    );

    // Click category button to start editing
    const categoryButton = screen.getByRole('button', { name: 'Groceries' });
    await user.click(categoryButton);

    // Select new category
    const select = screen.getByRole('combobox');
    await user.selectOptions(select, '2'); // Select Salary

    // UI should update immediately (optimistic)
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Salary' })).toBeInTheDocument();
    });

    // Callback should be called
    expect(onCategoryChange).toHaveBeenCalledWith(1, 2, expect.any(Function));
  });

  it('rolls back to original category if update fails', async () => {
    const user = userEvent.setup();

    render(
      <OptimisticTransactionTable
        transactions={mockTransactions}
        categories={mockCategories}
        onCategoryChange={onCategoryChange}
      />
    );

    // Click category button
    const categoryButton = screen.getByRole('button', { name: 'Groceries' });
    await user.click(categoryButton);

    // Select new category
    const select = screen.getByRole('combobox');
    await user.selectOptions(select, '2');

    // Get the rollback function that was passed to onCategoryChange
    const rollbackFn = onCategoryChange.mock.calls[0][2];

    // Simulate failure by calling rollback
    rollbackFn();

    // UI should roll back to original category
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Groceries' })).toBeInTheDocument();
    });
  });

  it('keeps new category if update succeeds', async () => {
    const user = userEvent.setup();

    render(
      <OptimisticTransactionTable
        transactions={mockTransactions}
        categories={mockCategories}
        onCategoryChange={onCategoryChange}
      />
    );

    // Click and change category
    const categoryButton = screen.getByRole('button', { name: 'Groceries' });
    await user.click(categoryButton);

    const select = screen.getByRole('combobox');
    await user.selectOptions(select, '2');

    // Wait for optimistic update
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Salary' })).toBeInTheDocument();
    });

    // Don't call rollback (simulating success)
    // Category should remain as Salary
    expect(screen.getByRole('button', { name: 'Salary' })).toBeInTheDocument();
  });
});
