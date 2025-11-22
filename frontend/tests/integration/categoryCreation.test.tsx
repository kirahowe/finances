import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { CategoryTable } from '../../app/components/CategoryTable';
import type { Category } from '../../app/lib/api';
import { generateCategoryIdent } from '../../app/lib/identGenerator';

describe('Category Creation Integration', () => {
  const mockCategories: Category[] = [
    {
      'db/id': 1,
      'category/name': 'Groceries',
      'category/type': 'expense',
    },
  ];

  it('creates a new category with generated identifier', async () => {
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

    // Click the add button
    const addButton = screen.getByRole('button', { name: /add|new|\+/i });
    await user.click(addButton);

    // Fill in the name
    const nameInput = screen.getByRole('textbox', { name: /name/i });
    await user.type(nameInput, 'Utilities');

    // Select income type
    const typeSelect = screen.getByRole('combobox', { name: /type/i });
    await user.selectOptions(typeSelect, 'income');

    // Click back on the name field and press Enter to submit
    await user.click(nameInput);
    await user.keyboard('{Enter}');

    // Verify onCreate was called with correct data
    expect(onCreate).toHaveBeenCalledWith({
      name: 'Utilities',
      type: 'income',
    });

    // Verify the identifier would be generated correctly
    const expectedIdent = generateCategoryIdent('Utilities');
    expect(expectedIdent).toBe('category/utilities');

    // Verify a new draft row is opened (auto-continuation behavior)
    await waitFor(() => {
      const newNameInput = screen.getByRole('textbox', { name: /name/i });
      expect(newNameInput).toBeInTheDocument();
      expect(newNameInput).toHaveValue('');
    });
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

    // Create first category
    const addButton = screen.getByRole('button', { name: /add|new|\+/i });
    await user.click(addButton);
    let nameInput = screen.getByRole('textbox', { name: /name/i });
    await user.type(nameInput, 'Utilities');
    await user.keyboard('{Enter}');

    // Create second category by clicking "+" again
    await user.click(addButton);
    nameInput = screen.getByRole('textbox', { name: /name/i });
    await user.type(nameInput, 'Entertainment');
    await user.click(addButton); // Submit via button click

    expect(onCreate).toHaveBeenCalledTimes(2);
    expect(onCreate).toHaveBeenNthCalledWith(1, { name: 'Utilities', type: 'expense' });
    expect(onCreate).toHaveBeenNthCalledWith(2, { name: 'Entertainment', type: 'expense' });
  });

  it('does not submit when name is empty or whitespace only', async () => {
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

    // Try submitting with empty name
    const nameInput = screen.getByRole('textbox', { name: /name/i });
    await user.keyboard('{Enter}');
    expect(onCreate).not.toHaveBeenCalled();

    // Try submitting with whitespace only
    await user.type(nameInput, '   ');
    await user.keyboard('{Enter}');
    expect(onCreate).not.toHaveBeenCalled();
  });
});
