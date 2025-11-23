import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { CategoryTable } from '../../app/components/CategoryTable';
import type { Category } from '../../app/lib/api';

describe('CategoryTable', () => {
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

  it('renders existing categories', () => {
    render(
      <CategoryTable
        categories={mockCategories}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onCreate={vi.fn()}
      />
    );

    expect(screen.getByText('Groceries')).toBeInTheDocument();
    expect(screen.getByText('Salary')).toBeInTheDocument();
    expect(screen.getByText('Expense')).toBeInTheDocument();
    expect(screen.getByText('Income')).toBeInTheDocument();
  });

  it('shows a "+" button to add a new category', () => {
    render(
      <CategoryTable
        categories={mockCategories}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onCreate={vi.fn()}
      />
    );

    const addButton = screen.getByRole('button', { name: /add|new|\+/i });
    expect(addButton).toBeInTheDocument();
  });

  it('shows a draft row when "+" button is clicked', async () => {
    const user = userEvent.setup();

    render(
      <CategoryTable
        categories={mockCategories}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onCreate={vi.fn()}
      />
    );

    const addButton = screen.getByRole('button', { name: /add|new|\+/i });
    await user.click(addButton);

    // Draft row should appear with editable fields
    const nameInput = screen.getByRole('textbox', { name: /name/i });
    const typeSelect = screen.getByRole('combobox', { name: /type/i });

    expect(nameInput).toBeInTheDocument();
    expect(typeSelect).toBeInTheDocument();
  });

  it('allows typing in the draft name field', async () => {
    const user = userEvent.setup();

    render(
      <CategoryTable
        categories={mockCategories}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onCreate={vi.fn()}
      />
    );

    const addButton = screen.getByRole('button', { name: /add|new|\+/i });
    await user.click(addButton);

    const nameInput = screen.getByRole('textbox', { name: /name/i });
    await user.type(nameInput, 'Utilities');

    expect(nameInput).toHaveValue('Utilities');
  });

  it('allows selecting type in the draft row', async () => {
    const user = userEvent.setup();

    render(
      <CategoryTable
        categories={mockCategories}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onCreate={vi.fn()}
      />
    );

    const addButton = screen.getByRole('button', { name: /add|new|\+/i });
    await user.click(addButton);

    const typeSelect = screen.getByRole('combobox', { name: /type/i });
    await user.selectOptions(typeSelect, 'income');

    expect(typeSelect).toHaveValue('income');
  });

  it('calls onCreate when Enter is pressed with valid data and opens new draft row', async () => {
    const user = userEvent.setup();
    const onCreate = vi.fn();

    render(
      <CategoryTable
        categories={mockCategories}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onCreate={onCreate}
      />
    );

    const addButton = screen.getByRole('button', { name: /add|new|\+/i });
    await user.click(addButton);

    const nameInput = screen.getByRole('textbox', { name: /name/i });
    await user.type(nameInput, 'Utilities');
    await user.keyboard('{Enter}');

    expect(onCreate).toHaveBeenCalledWith({
      name: 'Utilities',
      type: 'expense',
    });

    // A new draft row should be opened automatically
    const newNameInput = screen.getByRole('textbox', { name: /name/i });
    expect(newNameInput).toBeInTheDocument();
    expect(newNameInput).toHaveValue('');
  });

  it('calls onCreate when "+" is clicked again with valid data', async () => {
    const user = userEvent.setup();
    const onCreate = vi.fn();

    render(
      <CategoryTable
        categories={mockCategories}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onCreate={onCreate}
      />
    );

    const addButton = screen.getByRole('button', { name: /add|new|\+/i });
    await user.click(addButton);

    const nameInput = screen.getByRole('textbox', { name: /name/i });
    await user.type(nameInput, 'Utilities');

    await user.click(addButton);

    expect(onCreate).toHaveBeenCalledWith({
      name: 'Utilities',
      type: 'expense',
    });
  });

  it('does not call onCreate when Enter is pressed with empty name', async () => {
    const user = userEvent.setup();
    const onCreate = vi.fn();

    render(
      <CategoryTable
        categories={mockCategories}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onCreate={onCreate}
      />
    );

    const addButton = screen.getByRole('button', { name: /add|new|\+/i });
    await user.click(addButton);

    const nameInput = screen.getByRole('textbox', { name: /name/i });
    await user.keyboard('{Enter}');

    expect(onCreate).not.toHaveBeenCalled();
  });

  it('closes draft row when Escape is pressed', async () => {
    const user = userEvent.setup();

    render(
      <CategoryTable
        categories={mockCategories}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onCreate={vi.fn()}
      />
    );

    const addButton = screen.getByRole('button', { name: /add|new|\+/i });
    await user.click(addButton);

    const nameInput = screen.getByRole('textbox', { name: /name/i });
    expect(nameInput).toBeInTheDocument();

    await user.keyboard('{Escape}');

    // Draft row should be closed
    expect(screen.queryByRole('textbox', { name: /name/i })).not.toBeInTheDocument();
  });

  it('closes draft row when X button is clicked', async () => {
    const user = userEvent.setup();

    render(
      <CategoryTable
        categories={mockCategories}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onCreate={vi.fn()}
      />
    );

    const addButton = screen.getByRole('button', { name: /add|new|\+/i });
    await user.click(addButton);

    const nameInput = screen.getByRole('textbox', { name: /name/i });
    expect(nameInput).toBeInTheDocument();

    const closeButton = screen.getByRole('button', { name: /close|×/i });
    await user.click(closeButton);

    // Draft row should be closed
    expect(screen.queryByRole('textbox', { name: /name/i })).not.toBeInTheDocument();
  });

  it('allows creating multiple categories in sequence', async () => {
    const user = userEvent.setup();
    const onCreate = vi.fn();

    render(
      <CategoryTable
        categories={mockCategories}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onCreate={onCreate}
      />
    );

    const addButton = screen.getByRole('button', { name: /add|new|\+/i });

    // Create first category
    await user.click(addButton);
    let nameInput = screen.getByRole('textbox', { name: /name/i });
    await user.type(nameInput, 'Utilities');
    await user.keyboard('{Enter}');

    // Create second category
    await user.click(addButton);
    nameInput = screen.getByRole('textbox', { name: /name/i });
    await user.type(nameInput, 'Entertainment');
    await user.keyboard('{Enter}');

    expect(onCreate).toHaveBeenCalledTimes(2);
    expect(onCreate).toHaveBeenNthCalledWith(1, { name: 'Utilities', type: 'expense' });
    expect(onCreate).toHaveBeenNthCalledWith(2, { name: 'Entertainment', type: 'expense' });
  });

  it('renders Edit and Delete buttons for existing categories', () => {
    render(
      <CategoryTable
        categories={mockCategories}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onCreate={vi.fn()}
      />
    );

    const rows = screen.getAllByRole('row').slice(1); // Skip header row

    rows.forEach(row => {
      const cells = within(row).getAllByRole('cell');
      const actionsCell = cells[cells.length - 1];

      if (within(actionsCell).queryByRole('button', { name: /edit/i })) {
        expect(within(actionsCell).getByRole('button', { name: /edit/i })).toBeInTheDocument();
        expect(within(actionsCell).getByRole('button', { name: /delete/i })).toBeInTheDocument();
      }
    });
  });

  it('calls onEdit when Edit button is clicked', async () => {
    const user = userEvent.setup();
    const onEdit = vi.fn();

    render(
      <CategoryTable
        categories={mockCategories}
        onEdit={onEdit}
        onDelete={vi.fn()}
        onCreate={vi.fn()}
      />
    );

    const editButtons = screen.getAllByRole('button', { name: /edit/i });
    await user.click(editButtons[0]);

    expect(onEdit).toHaveBeenCalledWith(mockCategories[0]);
  });

  it('calls onDelete when Delete button is clicked', async () => {
    const user = userEvent.setup();
    const onDelete = vi.fn();

    render(
      <CategoryTable
        categories={mockCategories}
        onEdit={vi.fn()}
        onDelete={onDelete}
        onCreate={vi.fn()}
      />
    );

    const deleteButtons = screen.getAllByRole('button', { name: /delete/i });
    await user.click(deleteButtons[0]);

    expect(onDelete).toHaveBeenCalledWith(mockCategories[0]);
  });

  it('renders new categories at the bottom when newCategoryIds are provided', () => {
    const categoriesWithNew: Category[] = [
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
      {
        'db/id': 3,
        'category/name': 'Entertainment',
        'category/type': 'expense',
      },
    ];

    render(
      <CategoryTable
        categories={categoriesWithNew}
        newCategoryIds={[3]}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onCreate={vi.fn()}
      />
    );

    const rows = screen.getAllByRole('row').slice(1); // Skip header
    const categoryNames = rows.map(row => {
      const cells = within(row).getAllByRole('cell');
      return cells[0].textContent;
    });

    // Entertainment (id 3) should be at the bottom
    expect(categoryNames).toEqual(['Groceries', 'Salary', 'Entertainment']);
    expect(categoryNames[categoryNames.length - 1]).toBe('Entertainment');
  });

  it('keeps multiple new categories at the bottom in creation order', () => {
    const categoriesWithNew: Category[] = [
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
      {
        'db/id': 3,
        'category/name': 'Entertainment',
        'category/type': 'expense',
      },
      {
        'db/id': 4,
        'category/name': 'Utilities',
        'category/type': 'expense',
      },
    ];

    render(
      <CategoryTable
        categories={categoriesWithNew}
        newCategoryIds={[3, 4]}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onCreate={vi.fn()}
      />
    );

    const rows = screen.getAllByRole('row').slice(1);
    const categoryNames = rows.map(row => {
      const cells = within(row).getAllByRole('cell');
      return cells[0].textContent;
    });

    // New categories should be at the bottom in the order they were created
    expect(categoryNames).toEqual(['Groceries', 'Salary', 'Entertainment', 'Utilities']);
    expect(categoryNames.slice(-2)).toEqual(['Entertainment', 'Utilities']);
  });

  it('shows error when trying to create duplicate category', async () => {
    const user = userEvent.setup();
    const onCreate = vi.fn();

    render(
      <CategoryTable
        categories={mockCategories}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onCreate={onCreate}
      />
    );

    const addButton = screen.getByRole('button', { name: /add|new|\+/i });
    await user.click(addButton);

    // Try to create a category with the same name as an existing one
    const nameInput = screen.getByRole('textbox', { name: /name/i });
    await user.type(nameInput, 'Groceries');
    await user.keyboard('{Enter}');

    // Should not call onCreate
    expect(onCreate).not.toHaveBeenCalled();

    // Should show error message
    expect(screen.getByText(/category already exists/i)).toBeInTheDocument();

    // Input should have error styling
    expect(nameInput).toHaveStyle({ outline: '2px solid red' });
  });

  it('removes duplicate error when name is changed to unique value', async () => {
    const user = userEvent.setup();
    const onCreate = vi.fn();

    render(
      <CategoryTable
        categories={mockCategories}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onCreate={onCreate}
      />
    );

    const addButton = screen.getByRole('button', { name: /add|new|\+/i });
    await user.click(addButton);

    const nameInput = screen.getByRole('textbox', { name: /name/i });
    await user.type(nameInput, 'Groceries');

    // Error should appear
    expect(screen.getByText(/category already exists/i)).toBeInTheDocument();

    // Clear and type a unique name
    await user.clear(nameInput);
    await user.type(nameInput, 'Utilities');

    // Error should disappear
    expect(screen.queryByText(/category already exists/i)).not.toBeInTheDocument();
  });

  it('duplicate check is case-insensitive', async () => {
    const user = userEvent.setup();
    const onCreate = vi.fn();

    render(
      <CategoryTable
        categories={mockCategories}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onCreate={onCreate}
      />
    );

    const addButton = screen.getByRole('button', { name: /add|new|\+/i });
    await user.click(addButton);

    const nameInput = screen.getByRole('textbox', { name: /name/i });
    await user.type(nameInput, 'GROCERIES');
    await user.keyboard('{Enter}');

    // Should not call onCreate
    expect(onCreate).not.toHaveBeenCalled();

    // Should show error message
    expect(screen.getByText(/category already exists/i)).toBeInTheDocument();
  });
});
