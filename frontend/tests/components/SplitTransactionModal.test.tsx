import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SplitTransactionModal } from '../../app/components/SplitTransactionModal';
import { api, type Transaction, type Category } from '../../app/lib/api';

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

describe('SplitTransactionModal behavior', () => {
  it('closes on Escape', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(
      <SplitTransactionModal
        transaction={tx}
        categories={categories}
        onClose={onClose}
        onSaved={vi.fn()}
      />
    );
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalled();
  });

  it('disables removing parts at the 2-row minimum, and enables it past it', async () => {
    const user = userEvent.setup();
    render(
      <SplitTransactionModal
        transaction={tx}
        categories={categories}
        onClose={vi.fn()}
        onSaved={vi.fn()}
      />
    );
    let removeButtons = screen.getAllByRole('button', { name: 'Remove part' });
    expect(removeButtons).toHaveLength(2);
    removeButtons.forEach((b) => expect(b).toBeDisabled());

    await user.click(screen.getByRole('button', { name: '+ Add part' }));
    removeButtons = screen.getAllByRole('button', { name: 'Remove part' });
    expect(removeButtons).toHaveLength(3);
    removeButtons.forEach((b) => expect(b).toBeEnabled());
  });

  it('round-trips a seeded mixed-sign split without flipping the offsetting part', async () => {
    const user = userEvent.setup();
    const spy = vi
      .spyOn(api, 'setTransactionSplits')
      .mockResolvedValue({} as Transaction);
    // A -100 purchase split as a -120 charge with a +20 rebate line (sums to -100).
    const mixedTx: Transaction = {
      ...tx,
      'transaction/splits': [
        {
          'db/id': 11,
          'split/amount': -120,
          'split/order': 0,
          'split/category': { 'db/id': 1, 'category/name': 'Groceries' },
        },
        {
          'db/id': 12,
          'split/amount': 20,
          'split/order': 1,
          'split/category': { 'db/id': 2, 'category/name': 'Household' },
        },
      ],
    };
    render(
      <SplitTransactionModal
        transaction={mixedTx}
        categories={categories}
        onClose={vi.fn()}
        onSaved={vi.fn()}
      />
    );

    // The seeded set already reconciles, so Save is enabled without edits...
    const save = screen.getByRole('button', { name: 'Save split' });
    expect(save).toBeEnabled();

    // ...and saving preserves each part's stored sign (the +20 is not normalized).
    await user.click(save);
    expect(spy).toHaveBeenCalledWith(100, [
      { amount: '-120.00', categoryId: 1, memo: undefined },
      { amount: '20.00', categoryId: 2, memo: undefined },
    ]);
    spy.mockRestore();
  });
});
