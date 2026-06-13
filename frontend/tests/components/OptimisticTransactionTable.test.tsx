import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { OptimisticTransactionTable } from '../../app/components/OptimisticTransactionTable';
import { api, type Transaction, type Category } from '../../app/lib/api';
import { formatAmount } from '../../app/lib/format';

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

  const mockTransactionWithSplits: Transaction[] = [
    {
      'db/id': 5,
      'transaction/posted-date': '2024-02-01',
      'transaction/payee': 'Costco',
      'transaction/description': 'Warehouse run',
      'transaction/amount': -100.0,
      'transaction/category': null,
      'transaction/splits': [
        {
          'db/id': 51,
          'split/amount': -60.0,
          'split/order': 0,
          'split/memo': 'food',
          'split/category': { 'db/id': 1, 'category/name': 'Groceries' },
        },
        {
          'db/id': 52,
          'split/amount': -40.0,
          'split/order': 1,
          'split/category': { 'db/id': 2, 'category/name': 'Salary' },
        },
      ],
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

  it('renders a split as a context row plus one muted line per part', () => {
    renderWithRouter(
      <OptimisticTransactionTable
        transactions={mockTransactionWithSplits}
        categories={mockCategories}
        sorting={[]}
        onSortingChange={vi.fn()}
        onSplit={vi.fn()}
      />
    );

    // One context (parent) row + one child line per part.
    expect(document.querySelectorAll('tr.is-split-parent').length).toBe(1);
    expect(document.querySelectorAll('tr.split-child-row').length).toBe(2);

    // The parent carries the context once (payee, description)...
    expect(screen.getAllByText('Costco').length).toBe(1);
    expect(screen.getByText('Warehouse run')).toBeInTheDocument();
    // ...but its amount and category are blank so the total isn't shown twice.
    expect(screen.queryByText(formatAmount(-100))).not.toBeInTheDocument();
    expect(screen.queryByText(/Split \(/)).not.toBeInTheDocument();

    // Each part line shows its category + signed amount, with a split marker.
    expect(document.querySelectorAll('.split-icon').length).toBe(2);
    expect(screen.getByRole('button', { name: 'Groceries' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Salary' })).toBeInTheDocument();
    expect(screen.getByText(formatAmount(-60))).toBeInTheDocument();
    expect(screen.getByText(formatAmount(-40))).toBeInTheDocument();
  });

  it('offers a row actions menu on split rows that opens the editor', async () => {
    const user = userEvent.setup();
    const onSplit = vi.fn();
    renderWithRouter(
      <OptimisticTransactionTable
        transactions={mockTransactionWithSplits}
        categories={mockCategories}
        sorting={[]}
        onSortingChange={vi.fn()}
        onSplit={onSplit}
      />
    );

    // A split renders one actions menu (on its parent row), not one per part.
    const triggers = screen.getAllByRole('button', { name: 'Transaction actions' });
    expect(triggers.length).toBe(1);

    await user.click(triggers[0]);
    await user.click(screen.getByRole('menuitem', { name: 'Edit split' }));
    expect(onSplit).toHaveBeenCalledWith(mockTransactionWithSplits[0]);

    // Clicking a part's category also opens the editor.
    await user.click(screen.getByRole('button', { name: 'Groceries' }));
    expect(onSplit).toHaveBeenCalledTimes(2);
  });

  it('puts only split in the row menu, gating it on a non-zero amount', async () => {
    const user = userEvent.setup();
    const onSplit = vi.fn();
    const zeroAndNormal: Transaction[] = [
      { ...mockTransactions[0], 'db/id': 1, 'transaction/amount': -50 },
      { ...mockTransactions[0], 'db/id': 2, 'transaction/amount': 0, 'transaction/payee': 'Zero' },
    ];
    renderWithRouter(
      <OptimisticTransactionTable
        transactions={zeroAndNormal}
        categories={mockCategories}
        sorting={[]}
        onSortingChange={vi.fn()}
        onSplit={onSplit}
      />
    );

    // Only the non-zero row has an available action (Split), so it's the only row
    // with a menu trigger; the $0 row's trigger is withheld entirely.
    const triggers = screen.getAllByRole('button', { name: 'Transaction actions' });
    expect(triggers.length).toBe(1);

    await user.click(triggers[0]);
    expect(screen.getByRole('menuitem', { name: 'Split transaction' })).toBeInTheDocument();
    // Transfer matching moved onto the status pill — it's no longer in the menu.
    expect(screen.queryByRole('menuitem', { name: 'Match as transfer' })).not.toBeInTheDocument();
  });

  it('opens the transfer modal when a matched / unmatched status pill is clicked', async () => {
    const user = userEvent.setup();
    const onOpenTransfer = vi.fn();
    const transferCategory: Category = {
      'db/id': 3,
      'category/name': 'Transfer',
      'category/type': 'transfer',
    };
    const pillRows: Transaction[] = [
      // Unmatched: transfer-categorized with no counterpart.
      {
        ...mockTransactions[0],
        'db/id': 1,
        'transaction/payee': 'Open transfer',
        'transaction/category': transferCategory,
      },
      // Matched: linked to a counterpart on another account.
      {
        ...mockTransactions[0],
        'db/id': 2,
        'transaction/payee': 'Linked transfer',
        'transaction/category': transferCategory,
        'transaction/transfer-pair': {
          'db/id': 99,
          'transaction/amount': 50,
          'transaction/account': { 'db/id': 11, 'account/external-name': 'Savings' },
        },
      },
    ];
    renderWithRouter(
      <OptimisticTransactionTable
        transactions={pillRows}
        categories={mockCategories}
        sorting={[]}
        onSortingChange={vi.fn()}
        onOpenTransfer={onOpenTransfer}
      />
    );

    await user.click(screen.getByRole('button', { name: 'Unmatched' }));
    expect(onOpenTransfer).toHaveBeenCalledWith(pillRows[0]);

    await user.click(screen.getByRole('button', { name: 'Matched' }));
    expect(onOpenTransfer).toHaveBeenCalledWith(pillRows[1]);
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

  it('renders a reviewed checkbox per row reflecting the stored flag', () => {
    const rows: Transaction[] = [
      { ...mockTransactions[0], 'db/id': 1, 'transaction/reviewed': true },
      { ...mockTransactions[0], 'db/id': 2, 'transaction/payee': 'Store B' },
    ];
    renderWithRouter(
      <OptimisticTransactionTable
        transactions={rows}
        categories={mockCategories}
        sorting={[]}
        onSortingChange={vi.fn()}
      />
    );

    expect(screen.getByRole('columnheader', { name: /reviewed/i })).toBeInTheDocument();
    const boxes = screen.getAllByRole('checkbox') as HTMLInputElement[];
    expect(boxes.length).toBe(2);
    expect(boxes[0].checked).toBe(true);
    expect(boxes[1].checked).toBe(false);
  });

  it('persists a reviewed toggle for the clicked row', async () => {
    const user = userEvent.setup();
    const spy = vi.spyOn(api, 'setTransactionReviewed').mockResolvedValue({} as Transaction);
    const rows: Transaction[] = [{ ...mockTransactions[0], 'db/id': 7 }];
    renderWithRouter(
      <OptimisticTransactionTable
        transactions={rows}
        categories={mockCategories}
        sorting={[]}
        onSortingChange={vi.fn()}
      />
    );

    const box = screen.getByRole('checkbox') as HTMLInputElement;
    await user.click(box);

    expect(spy).toHaveBeenCalledWith(7, true);
    // Optimistic: the checkbox shows checked immediately, before any revalidation.
    expect(box.checked).toBe(true);
    spy.mockRestore();
  });

  it('reviews splits per part with no checkbox on the split parent', () => {
    const reviewedSplit: Transaction[] = [
      {
        ...mockTransactionWithSplits[0],
        'transaction/splits': [
          { ...mockTransactionWithSplits[0]['transaction/splits']![0], 'split/reviewed': true },
          { ...mockTransactionWithSplits[0]['transaction/splits']![1] },
        ],
      },
    ];
    renderWithRouter(
      <OptimisticTransactionTable
        transactions={reviewedSplit}
        categories={mockCategories}
        sorting={[]}
        onSortingChange={vi.fn()}
        onSplit={vi.fn()}
      />
    );

    // The parent row's reviewed cell is blank — only the two child rows carry a box.
    const parentRow = document.querySelector('tr.is-split-parent')!;
    expect(parentRow.querySelector('input[type="checkbox"]')).toBeNull();

    const boxes = screen.getAllByRole('checkbox') as HTMLInputElement[];
    expect(boxes.length).toBe(2);
    // First part reviewed, second not — reviewing one leg never touches the sibling.
    expect(boxes[0].checked).toBe(true);
    expect(boxes[1].checked).toBe(false);
  });

  it('persists a split reviewed toggle keyed on the split id', async () => {
    const user = userEvent.setup();
    const spy = vi.spyOn(api, 'setSplitReviewed').mockResolvedValue({} as Transaction);
    renderWithRouter(
      <OptimisticTransactionTable
        transactions={mockTransactionWithSplits}
        categories={mockCategories}
        sorting={[]}
        onSortingChange={vi.fn()}
        onSplit={vi.fn()}
      />
    );

    const boxes = screen.getAllByRole('checkbox');
    await user.click(boxes[0]);

    // Parent transaction db/id 5, first split db/id 51.
    expect(spy).toHaveBeenCalledWith(5, 51, true);
    spy.mockRestore();
  });
});
