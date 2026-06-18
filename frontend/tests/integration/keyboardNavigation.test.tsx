import { useState } from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { OptimisticTransactionTable } from '../../app/components/OptimisticTransactionTable';
import { type Transaction, type Category } from '../../app/lib/api';

// End-to-end-ish exercise of the centralized keyboard navigation: real focus moves
// (jsdom honours tabindex + .focus()), real key events through the container's one
// handler, and the editor handoff. Covers the spreadsheet keymap the table promises.

const categories: Category[] = [
  { 'db/id': 1, 'category/name': 'Groceries', 'category/type': 'expense' },
  { 'db/id': 2, 'category/name': 'Salary', 'category/type': 'income' },
];

const txns: Transaction[] = [
  {
    'db/id': 1,
    'transaction/posted-date': '2024-01-15',
    'transaction/payee': 'Store A',
    'transaction/description': 'First',
    'transaction/amount': -50,
    'transaction/category': null,
    'transaction/reviewed': false,
  },
  {
    'db/id': 2,
    'transaction/posted-date': '2024-01-16',
    'transaction/payee': 'Store B',
    'transaction/description': 'Second',
    'transaction/amount': -75,
    'transaction/category': null,
    'transaction/reviewed': false,
  },
];

function renderTable(overrides: Partial<React.ComponentProps<typeof OptimisticTransactionTable>> = {}) {
  const props: React.ComponentProps<typeof OptimisticTransactionTable> = {
    transactions: txns,
    categories,
    sorting: [],
    onSortingChange: vi.fn(),
    onEditCategory: vi.fn(),
    onEditDescription: vi.fn(),
    onToggleReviewed: vi.fn(),
    ...overrides,
  };
  const router = createMemoryRouter([{ path: '/', element: <OptimisticTransactionTable {...props} /> }], {
    initialEntries: ['/'],
  });
  const utils = render(<RouterProvider router={router} />);
  const scroll = utils.container.querySelector('.transactions-table-scroll') as HTMLElement;
  return { ...utils, scroll, props };
}

// The active cell is the <td> carrying the focus ring; identify it by class.
const activeCell = (): HTMLElement | null => document.querySelector('td.grid-cell-active');

describe('keyboard navigation', () => {
  it('lands on the first editable cell on the first keystroke', async () => {
    const user = userEvent.setup();
    const { scroll } = renderTable();
    scroll.focus();

    await user.keyboard('{ArrowDown}');

    expect(activeCell()).toHaveClass('description-cell');
  });

  it('moves the active cell right across editable columns and down across rows', async () => {
    const user = userEvent.setup();
    const { scroll } = renderTable();
    scroll.focus();

    await user.keyboard('{ArrowDown}'); // row 0, description
    await user.keyboard('{ArrowRight}'); // row 0, category
    expect(activeCell()).toHaveClass('category-cell');

    await user.keyboard('{ArrowRight}'); // row 0, reviewed
    expect(activeCell()).toHaveClass('reviewed-cell');

    await user.keyboard('{ArrowDown}'); // row 1, reviewed (column preserved)
    expect(activeCell()).toHaveClass('reviewed-cell');
  });

  it('opens the category dropdown on Enter — and not merely on focusing the cell', async () => {
    const user = userEvent.setup();
    const { scroll } = renderTable();
    scroll.focus();

    await user.keyboard('{ArrowDown}{ArrowRight}'); // navigate onto the category cell

    // The old onFocus hack opened the dropdown the instant the cell was focused;
    // it must now stay closed until the user asks to edit.
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();

    await user.keyboard('{Enter}');
    expect(screen.getByRole('combobox')).toBeInTheDocument();
  });

  it('Escape cancels an in-progress description edit without reporting it', async () => {
    const user = userEvent.setup();
    const onEditDescription = vi.fn();
    const { scroll } = renderTable({ onEditDescription });
    scroll.focus();

    await user.keyboard('{ArrowDown}{Enter}'); // edit row 0 description
    const input = screen.getByLabelText('Edit description');
    await user.clear(input);
    await user.type(input, 'changed');
    await user.keyboard('{Escape}');

    expect(screen.queryByLabelText('Edit description')).not.toBeInTheDocument();
    expect(onEditDescription).not.toHaveBeenCalled();
  });

  it('Space toggles the reviewed checkbox on the active cell', async () => {
    const user = userEvent.setup();
    const onToggleReviewed = vi.fn();
    const { scroll } = renderTable({ onToggleReviewed });
    scroll.focus();

    await user.keyboard('{ArrowDown}{ArrowRight}{ArrowRight}'); // row 0, reviewed
    await user.keyboard('{ }');

    expect(onToggleReviewed).toHaveBeenCalledWith(1, true);
  });

  it('only Space toggles reviewed — a stray printable key or Enter does not', async () => {
    const user = userEvent.setup();
    const onToggleReviewed = vi.fn();
    const { scroll } = renderTable({ onToggleReviewed });
    scroll.focus();

    await user.keyboard('{ArrowDown}{ArrowRight}{ArrowRight}'); // row 0, reviewed
    await user.keyboard('x'); // a mis-key must not flip the checkbox...
    await user.keyboard('{Enter}'); // ...nor Enter (only Space acts on a checkbox cell)
    expect(onToggleReviewed).not.toHaveBeenCalled();

    await user.keyboard('{ }');
    expect(onToggleReviewed).toHaveBeenCalledWith(1, true);
  });

  it('re-anchors onto a visible row when the active row drops out of the grid', async () => {
    const user = userEvent.setup();

    // A stateful host so we can drop the active row mid-task (what a filter, re-sort, or
    // refetch does) and confirm the grid recovers rather than stranding focus.
    function Harness() {
      const [data, setData] = useState<Transaction[]>(txns);
      return (
        <>
          <button onClick={() => setData((d) => d.filter((t) => t['db/id'] !== 1))}>
            drop-first
          </button>
          <OptimisticTransactionTable
            transactions={data}
            categories={categories}
            sorting={[]}
            onSortingChange={vi.fn()}
            onEditCategory={vi.fn()}
            onEditDescription={vi.fn()}
            onToggleReviewed={vi.fn()}
          />
        </>
      );
    }
    const router = createMemoryRouter([{ path: '/', element: <Harness /> }], {
      initialEntries: ['/'],
    });
    const { container } = render(<RouterProvider router={router} />);
    const scroll = container.querySelector('.transactions-table-scroll') as HTMLElement;

    scroll.focus();
    await user.keyboard('{ArrowDown}'); // active: tx 1 (First), description
    expect(activeCell()).toHaveTextContent('First');

    await user.click(screen.getByRole('button', { name: 'drop-first' }));

    // The active cell is now stale, so the container keeps the tab stop a keyboard user
    // needs to get back in (it would otherwise be tabindex -1 with no active cell).
    expect(scroll).toHaveAttribute('tabindex', '0');

    // Re-entering and moving re-anchors onto the surviving row instead of no-op'ing.
    scroll.focus();
    await user.keyboard('{ArrowDown}');
    expect(activeCell()).toHaveTextContent('Second');
  });

  it('starts editing a description on a printable key, seeded with that character', async () => {
    const user = userEvent.setup();
    const { scroll } = renderTable();
    scroll.focus();

    await user.keyboard('{ArrowDown}'); // row 0, description (active, not editing)
    await user.keyboard('x');

    const input = screen.getByLabelText('Edit description') as HTMLInputElement;
    expect(input).toHaveValue('x');
  });

  it('opens the category combobox filtered when type-to-edit starts on a category cell', async () => {
    const user = userEvent.setup();
    const { scroll } = renderTable();
    scroll.focus();

    await user.keyboard('{ArrowDown}{ArrowRight}'); // row 0, category
    await user.keyboard('g');

    const input = screen.getByRole('combobox') as HTMLInputElement;
    expect(input).toHaveValue('g');
    // Only Groceries survives the filter (plus the always-present Uncategorized).
    expect(screen.getByText('Groceries')).toBeInTheDocument();
    expect(screen.queryByText('Salary')).not.toBeInTheDocument();
  });

  it('commits a category with Enter and advances editing to the next row (rapid categorization)', async () => {
    const user = userEvent.setup();
    const onEditCategory = vi.fn();
    const { scroll } = renderTable({ onEditCategory });
    scroll.focus();

    await user.keyboard('{ArrowDown}{ArrowRight}{Enter}'); // open row 0 category dropdown
    expect(screen.getByRole('combobox')).toBeInTheDocument();

    // Highlight Groceries (index 0 Uncategorized, index 1 Groceries) and confirm.
    await user.keyboard('{ArrowDown}{ArrowDown}{Enter}');

    expect(onEditCategory).toHaveBeenCalledWith(1, 1);
    // The dropdown for the NEXT row should now be open — the editor advanced.
    expect(screen.getByRole('combobox')).toBeInTheDocument();
  });

  it('Tab commits a description edit and moves to the category cell', async () => {
    const user = userEvent.setup();
    const onEditDescription = vi.fn();
    const { scroll } = renderTable({ onEditDescription });
    scroll.focus();

    await user.keyboard('{ArrowDown}{Enter}'); // edit row 0 description
    const input = screen.getByLabelText('Edit description');
    await user.clear(input);
    await user.type(input, 'Tabbed');
    await user.keyboard('{Tab}');

    expect(onEditDescription).toHaveBeenCalledWith(1, 'Tabbed');
    expect(activeCell()).toHaveClass('category-cell');
  });
});
