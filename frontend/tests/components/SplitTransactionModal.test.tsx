import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SplitTransactionModal } from '../../app/components/SplitTransactionModal';
import type { Transaction, Category } from '../../app/lib/api';

const categories: Category[] = [
  { 'db/id': 1, 'category/name': 'Groceries', 'category/type': 'expense' },
  { 'db/id': 2, 'category/name': 'Household', 'category/type': 'expense' },
];

const tx: Transaction = {
  'db/id': 100,
  'transaction/amount': -100,
  'transaction/payee': 'Costco',
  'transaction/posted-date': '2025-01-15',
};

describe('SplitTransactionModal category navigation', () => {
  it('focuses the category input and navigates options with arrow keys', async () => {
    const user = userEvent.setup();
    render(
      <SplitTransactionModal
        transaction={tx}
        categories={categories}
        onClose={vi.fn()}
        onSaved={vi.fn()}
      />
    );

    // Open the first row's category dropdown.
    const categoryButtons = screen.getAllByRole('button', { name: 'Uncategorized' });
    await user.click(categoryButtons[0]);

    const input = screen.getByRole('combobox');
    expect(input).toHaveFocus();

    const listbox = screen.getByRole('listbox');
    await user.keyboard('{ArrowDown}');
    expect(within(listbox).getByText('Uncategorized').closest('li')).toHaveClass('highlighted');

    await user.keyboard('{ArrowDown}');
    expect(within(listbox).getByText('Groceries').closest('li')).toHaveClass('highlighted');
  });
});
