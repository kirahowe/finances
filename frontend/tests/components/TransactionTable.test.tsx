import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { TransactionTable } from '../../app/components/TransactionTable';
import type { Transaction, Category } from '../../app/lib/api';
import type { SortingState } from '@tanstack/react-table';

describe('TransactionTable', () => {
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
    {
      'db/id': 2,
      'transaction/posted-date': '2024-01-10',
      'transaction/payee': 'Store B',
      'transaction/description': 'Purchase 2',
      'transaction/amount': -100.0,
      'transaction/category': mockCategories[0],
    },
    {
      'db/id': 3,
      'transaction/posted-date': '2024-01-20',
      'transaction/payee': 'Employer',
      'transaction/description': 'Paycheck',
      'transaction/amount': 2000.0,
      'transaction/category': mockCategories[1],
    },
  ];

  it('renders transaction data in table', () => {
    render(
      <TransactionTable
        transactions={mockTransactions}
        categories={mockCategories}
        onCategoryChange={vi.fn()}
        sorting={[]}
        onSortingChange={vi.fn()}
      />
    );

    expect(screen.getByText('Store A')).toBeInTheDocument();
    expect(screen.getByText('Store B')).toBeInTheDocument();
    expect(screen.getByText('Employer')).toBeInTheDocument();
  });

  it('has sortable column headers', () => {
    render(
      <TransactionTable
        transactions={mockTransactions}
        categories={mockCategories}
        onCategoryChange={vi.fn()}
        sorting={[]}
        onSortingChange={vi.fn()}
      />
    );

    // Check that column headers are present and clickable
    const dateHeader = screen.getByRole('columnheader', { name: /date/i });
    const payeeHeader = screen.getByRole('columnheader', { name: /payee/i });
    const amountHeader = screen.getByRole('columnheader', { name: /amount/i });

    expect(dateHeader).toBeInTheDocument();
    expect(payeeHeader).toBeInTheDocument();
    expect(amountHeader).toBeInTheDocument();
  });

  it('calls onSortingChange when column header is clicked', async () => {
    const user = userEvent.setup();
    const onSortingChange = vi.fn();

    render(
      <TransactionTable
        transactions={mockTransactions}
        categories={mockCategories}
        onCategoryChange={vi.fn()}
        sorting={[]}
        onSortingChange={onSortingChange}
      />
    );

    const dateHeader = screen.getByRole('columnheader', { name: /date/i });
    await user.click(dateHeader);

    // Should call onSortingChange with new sorting state
    expect(onSortingChange).toHaveBeenCalled();
    const callArg = onSortingChange.mock.calls[0][0];
    // TanStack Table passes an updater function, we need to call it with current state
    const newSorting = typeof callArg === 'function' ? callArg([]) : callArg;
    expect(newSorting).toEqual([{ id: 'date', desc: false }]);
  });

  it('displays sort indicators based on sorting prop', () => {
    const { rerender } = render(
      <TransactionTable
        transactions={mockTransactions}
        categories={mockCategories}
        onCategoryChange={vi.fn()}
        sorting={[{ id: 'date', desc: false }]}
        onSortingChange={vi.fn()}
      />
    );

    const dateHeader = screen.getByRole('columnheader', { name: /date/i });
    expect(dateHeader.textContent).toContain('↑');

    // Change to descending
    rerender(
      <TransactionTable
        transactions={mockTransactions}
        categories={mockCategories}
        onCategoryChange={vi.fn()}
        sorting={[{ id: 'date', desc: true }]}
        onSortingChange={vi.fn()}
      />
    );

    expect(dateHeader.textContent).toContain('↓');
  });

  it('sorts data according to sorting prop', () => {
    render(
      <TransactionTable
        transactions={mockTransactions}
        categories={mockCategories}
        onCategoryChange={vi.fn()}
        sorting={[{ id: 'date', desc: false }]}
        onSortingChange={vi.fn()}
      />
    );

    // With ascending date sort, Store B (2024-01-10) should be first
    const rows = screen.getAllByRole('row').slice(1);
    const firstRow = within(rows[0]);
    expect(firstRow.getByText('Store B')).toBeInTheDocument();
  });

  it('allows category editing when category button is clicked', async () => {
    const user = userEvent.setup();
    const onCategoryChange = vi.fn();

    render(
      <TransactionTable
        transactions={mockTransactions}
        categories={mockCategories}
        onCategoryChange={onCategoryChange}
        sorting={[]}
        onSortingChange={vi.fn()}
      />
    );

    // Find and click a category button
    const categoryButtons = screen.getAllByRole('button', { name: /Groceries|Salary/i });
    await user.click(categoryButtons[0]);

    // Should show a dropdown with input and list
    const input = screen.getByRole('textbox');
    expect(input).toBeInTheDocument();
    expect(input).toHaveFocus();

    // Should show category list (check for list items, not all text occurrences)
    const listItems = screen.getAllByRole('listitem');
    expect(listItems.length).toBeGreaterThanOrEqual(3);
    expect(screen.getByText('Uncategorized')).toBeInTheDocument();
  });
});
