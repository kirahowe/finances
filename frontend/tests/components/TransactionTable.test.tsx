import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { TransactionTable } from '../../app/components/TransactionTable';
import type { Transaction, Category } from '../../app/lib/api';

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

  it('sorts by date when date header is clicked', async () => {
    const user = userEvent.setup();

    render(
      <TransactionTable
        transactions={mockTransactions}
        categories={mockCategories}
        onCategoryChange={vi.fn()}
      />
    );

    const dateHeader = screen.getByRole('columnheader', { name: /date/i });

    // Get all rows before sorting
    const rowsBefore = screen.getAllByRole('row').slice(1); // skip header row
    const firstRowBefore = within(rowsBefore[0]).getByRole('cell', { name: /Store A/i });
    expect(firstRowBefore).toBeInTheDocument();

    // Click to sort ascending (earliest first)
    await user.click(dateHeader);

    // Check for sort indicator
    expect(dateHeader.textContent).toMatch(/[↑↓]/);

    // Get all rows after sorting
    const rowsAfter = screen.getAllByRole('row').slice(1);
    const firstRowAfter = within(rowsAfter[0]);

    // Store B should be first (2024-01-10 is earliest)
    expect(firstRowAfter.getByText('Store B')).toBeInTheDocument();
  });

  it('sorts by amount when amount header is clicked', async () => {
    const user = userEvent.setup();

    render(
      <TransactionTable
        transactions={mockTransactions}
        categories={mockCategories}
        onCategoryChange={vi.fn()}
      />
    );

    const amountHeader = screen.getByRole('columnheader', { name: /amount/i });

    // Click to sort (first click is descending: largest to smallest)
    await user.click(amountHeader);

    // Check for sort indicator
    expect(amountHeader.textContent).toMatch(/[↑↓]/);

    // Get first row - should be Employer with 2000 (largest amount)
    const rows = screen.getAllByRole('row').slice(1);
    const cells = within(rows[0]).getAllByRole('cell');
    const payeeCell = cells[1]; // Payee is second column
    expect(payeeCell).toHaveTextContent('Employer');

    // Click again to sort ascending (smallest to largest)
    await user.click(amountHeader);

    // Now Store B with -100 should be first
    const rowsAfterSecondClick = screen.getAllByRole('row').slice(1);
    const cellsAfterSecondClick = within(rowsAfterSecondClick[0]).getAllByRole('cell');
    const payeeCellAfterSecondClick = cellsAfterSecondClick[1];
    expect(payeeCellAfterSecondClick).toHaveTextContent('Store B');
  });

  it('toggles sort direction on subsequent clicks', async () => {
    const user = userEvent.setup();

    render(
      <TransactionTable
        transactions={mockTransactions}
        categories={mockCategories}
        onCategoryChange={vi.fn()}
      />
    );

    const payeeHeader = screen.getByRole('columnheader', { name: /payee/i });

    // First click - ascending
    await user.click(payeeHeader);
    let rows = screen.getAllByRole('row').slice(1);
    let firstRow = within(rows[0]);
    expect(firstRow.getByText('Employer')).toBeInTheDocument(); // E comes first

    // Second click - descending
    await user.click(payeeHeader);
    rows = screen.getAllByRole('row').slice(1);
    firstRow = within(rows[0]);
    expect(firstRow.getByText('Store B')).toBeInTheDocument(); // S comes first descending
  });

  it('allows category editing when category button is clicked', async () => {
    const user = userEvent.setup();
    const onCategoryChange = vi.fn();

    render(
      <TransactionTable
        transactions={mockTransactions}
        categories={mockCategories}
        onCategoryChange={onCategoryChange}
      />
    );

    // Find and click a category button
    const categoryButtons = screen.getAllByRole('button', { name: /Groceries|Salary/i });
    await user.click(categoryButtons[0]);

    // Should show a dropdown/select
    const select = screen.getByRole('combobox');
    expect(select).toBeInTheDocument();
  });
});
