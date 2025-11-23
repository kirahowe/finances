import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { TransactionTable } from '../../app/components/TransactionTable';
import type { Transaction, Category } from '../../app/lib/api';

describe('Rapid Categorization Workflow', () => {
  const mockCategories: Category[] = [
    {
      'db/id': 1,
      'category/name': 'Groceries',
      'category/type': 'expense',
    },
    {
      'db/id': 2,
      'category/name': 'Gas',
      'category/type': 'expense',
    },
    {
      'db/id': 3,
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
      'transaction/category': null,
    },
    {
      'db/id': 2,
      'transaction/posted-date': '2024-01-10',
      'transaction/payee': 'Store B',
      'transaction/description': 'Purchase 2',
      'transaction/amount': -100.0,
      'transaction/category': null,
    },
    {
      'db/id': 3,
      'transaction/posted-date': '2024-01-20',
      'transaction/payee': 'Employer',
      'transaction/description': 'Paycheck',
      'transaction/amount': 2000.0,
      'transaction/category': null,
    },
  ];

  it('allows filtering categories by typing', async () => {
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

    // Click first uncategorized transaction
    const categoryButtons = screen.getAllByRole('button', { name: 'Uncategorized' });
    await user.click(categoryButtons[0]);

    // Type to filter categories
    const input = screen.getByRole('textbox');
    await user.type(input, 'gro');

    // Should only show Groceries
    const listItems = screen.getAllByRole('listitem');
    expect(listItems).toHaveLength(2); // Uncategorized + Groceries
    expect(screen.getByText('Groceries')).toBeInTheDocument();
    expect(screen.queryByText('Gas')).not.toBeInTheDocument();
    expect(screen.queryByText('Salary')).not.toBeInTheDocument();
  });

  it('allows keyboard navigation with arrow keys', async () => {
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

    // Click first uncategorized transaction
    const categoryButtons = screen.getAllByRole('button', { name: 'Uncategorized' });
    await user.click(categoryButtons[0]);

    const input = screen.getByRole('textbox');

    // Press ArrowDown to highlight first item (Uncategorized)
    await user.type(input, '{ArrowDown}');

    let highlightedItem = screen.getAllByRole('listitem').find(item =>
      item.className.includes('highlighted')
    );
    expect(highlightedItem).toHaveTextContent('Uncategorized');

    // Press ArrowDown again to highlight Groceries
    await user.type(input, '{ArrowDown}');

    highlightedItem = screen.getAllByRole('listitem').find(item =>
      item.className.includes('highlighted')
    );
    expect(highlightedItem).toHaveTextContent('Groceries');

    // Press ArrowUp to go back to Uncategorized
    await user.type(input, '{ArrowUp}');

    highlightedItem = screen.getAllByRole('listitem').find(item =>
      item.className.includes('highlighted')
    );
    expect(highlightedItem).toHaveTextContent('Uncategorized');
  });

  it('allows selecting category with Enter key', async () => {
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

    // Click first uncategorized transaction
    const categoryButtons = screen.getAllByRole('button', { name: 'Uncategorized' });
    await user.click(categoryButtons[0]);

    const input = screen.getByRole('textbox');

    // Navigate to Groceries and select it
    await user.type(input, '{ArrowDown}');
    await user.type(input, '{ArrowDown}');
    await user.type(input, '{Enter}');

    // Should have called onCategoryChange with Groceries ID
    expect(onCategoryChange).toHaveBeenCalledWith(1, 1);
  });

  it('allows closing dropdown with Escape key', async () => {
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

    // Click first uncategorized transaction
    const categoryButtons = screen.getAllByRole('button', { name: 'Uncategorized' });
    await user.click(categoryButtons[0]);

    // Input should be visible
    expect(screen.getByRole('textbox')).toBeInTheDocument();

    // Press Escape to close
    const input = screen.getByRole('textbox');
    await user.type(input, '{Escape}');

    // Input should be gone
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();

    // Should not have changed category
    expect(onCategoryChange).not.toHaveBeenCalled();
  });

  it('allows clicking outside dropdown to close', async () => {
    const user = userEvent.setup();
    const onCategoryChange = vi.fn();

    const { container } = render(
      <div>
        <TransactionTable
          transactions={mockTransactions}
          categories={mockCategories}
          onCategoryChange={onCategoryChange}
          sorting={[]}
          onSortingChange={vi.fn()}
        />
        <div data-testid="outside">Outside element</div>
      </div>
    );

    // Click first uncategorized transaction
    const categoryButtons = screen.getAllByRole('button', { name: 'Uncategorized' });
    await user.click(categoryButtons[0]);

    // Input should be visible
    expect(screen.getByRole('textbox')).toBeInTheDocument();

    // Click outside
    const outsideElement = screen.getByTestId('outside');
    await user.click(outsideElement);

    // Input should be gone
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();

    // Should not have changed category
    expect(onCategoryChange).not.toHaveBeenCalled();
  });
});
