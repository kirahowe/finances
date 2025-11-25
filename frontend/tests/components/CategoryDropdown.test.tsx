import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { CategoryDropdown } from '../../app/components/CategoryDropdown';
import type { Category } from '../../app/lib/api';

describe('CategoryDropdown', () => {
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

  it('renders input field when opened', () => {
    render(
      <CategoryDropdown
        categories={mockCategories}
        selectedCategoryId={null}
        onSelect={vi.fn()}
        onClose={vi.fn()}
      />
    );

    const input = screen.getByRole('textbox');
    expect(input).toBeInTheDocument();
    expect(input).toHaveFocus();
  });

  it('displays all categories initially', () => {
    render(
      <CategoryDropdown
        categories={mockCategories}
        selectedCategoryId={null}
        onSelect={vi.fn()}
        onClose={vi.fn()}
      />
    );

    expect(screen.getByText('Groceries')).toBeInTheDocument();
    expect(screen.getByText('Gas')).toBeInTheDocument();
    expect(screen.getByText('Salary')).toBeInTheDocument();
  });

  it('displays Uncategorized option', () => {
    render(
      <CategoryDropdown
        categories={mockCategories}
        selectedCategoryId={null}
        onSelect={vi.fn()}
        onClose={vi.fn()}
      />
    );

    expect(screen.getByText('Uncategorized')).toBeInTheDocument();
  });

  it('filters categories when typing', async () => {
    const user = userEvent.setup();

    render(
      <CategoryDropdown
        categories={mockCategories}
        selectedCategoryId={null}
        onSelect={vi.fn()}
        onClose={vi.fn()}
      />
    );

    const input = screen.getByRole('textbox');
    await user.type(input, 'gro');

    expect(screen.getByText('Groceries')).toBeInTheDocument();
    expect(screen.queryByText('Gas')).not.toBeInTheDocument();
    expect(screen.queryByText('Salary')).not.toBeInTheDocument();
  });

  it('calls onSelect when clicking a category', async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();

    render(
      <CategoryDropdown
        categories={mockCategories}
        selectedCategoryId={null}
        onSelect={onSelect}
        onClose={vi.fn()}
      />
    );

    await user.click(screen.getByText('Groceries'));

    expect(onSelect).toHaveBeenCalledWith(1);
  });

  it('calls onSelect with null when clicking Uncategorized', async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();

    render(
      <CategoryDropdown
        categories={mockCategories}
        selectedCategoryId={1}
        onSelect={onSelect}
        onClose={vi.fn()}
      />
    );

    await user.click(screen.getByText('Uncategorized'));

    expect(onSelect).toHaveBeenCalledWith(null);
  });

  it('highlights the selected category initially', () => {
    render(
      <CategoryDropdown
        categories={mockCategories}
        selectedCategoryId={2}
        onSelect={vi.fn()}
        onClose={vi.fn()}
      />
    );

    const gasOption = screen.getByText('Gas').closest('li');
    expect(gasOption).toHaveClass('highlighted');
  });

  it('navigates with arrow down key', async () => {
    const user = userEvent.setup();

    render(
      <CategoryDropdown
        categories={mockCategories}
        selectedCategoryId={null}
        onSelect={vi.fn()}
        onClose={vi.fn()}
      />
    );

    const input = screen.getByRole('textbox');
    await user.type(input, '{ArrowDown}');

    const firstOption = screen.getByText('Uncategorized').closest('li');
    expect(firstOption).toHaveClass('highlighted');
  });

  it('navigates with arrow up key', async () => {
    const user = userEvent.setup();

    render(
      <CategoryDropdown
        categories={mockCategories}
        selectedCategoryId={null}
        onSelect={vi.fn()}
        onClose={vi.fn()}
      />
    );

    const input = screen.getByRole('textbox');
    await user.type(input, '{ArrowUp}');

    const lastOption = screen.getByText('Salary').closest('li');
    expect(lastOption).toHaveClass('highlighted');
  });

  it('selects highlighted category with Enter key', async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();

    render(
      <CategoryDropdown
        categories={mockCategories}
        selectedCategoryId={null}
        onSelect={onSelect}
        onClose={vi.fn()}
      />
    );

    const input = screen.getByRole('textbox');
    await user.type(input, '{ArrowDown}');
    await user.type(input, '{Enter}');

    expect(onSelect).toHaveBeenCalledWith(null); // Uncategorized
  });

  it('closes dropdown with Escape key', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();

    render(
      <CategoryDropdown
        categories={mockCategories}
        selectedCategoryId={null}
        onSelect={vi.fn()}
        onClose={onClose}
      />
    );

    const input = screen.getByRole('textbox');
    await user.type(input, '{Escape}');

    expect(onClose).toHaveBeenCalled();
  });

  it('closes dropdown when clicking outside', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();

    render(
      <div>
        <CategoryDropdown
          categories={mockCategories}
          selectedCategoryId={null}
          onSelect={vi.fn()}
          onClose={onClose}
        />
        <button>Outside</button>
      </div>
    );

    await user.click(screen.getByRole('button', { name: 'Outside' }));

    expect(onClose).toHaveBeenCalled();
  });

  it('maintains highlighted state after filtering', async () => {
    const user = userEvent.setup();

    render(
      <CategoryDropdown
        categories={mockCategories}
        selectedCategoryId={null}
        onSelect={vi.fn()}
        onClose={vi.fn()}
      />
    );

    const input = screen.getByRole('textbox');
    await user.type(input, '{ArrowDown}');
    await user.type(input, 'g');

    const firstFilteredOption = screen.getByText('Groceries').closest('li');
    expect(firstFilteredOption).toHaveClass('highlighted');
  });

  it('shows "Uncategorized" as placeholder when no category is selected', () => {
    render(
      <CategoryDropdown
        categories={mockCategories}
        selectedCategoryId={null}
        onSelect={vi.fn()}
        onClose={vi.fn()}
      />
    );

    const input = screen.getByRole('textbox');
    expect(input).toHaveAttribute('placeholder', 'Uncategorized');
  });

  it('shows selected category name as placeholder', () => {
    render(
      <CategoryDropdown
        categories={mockCategories}
        selectedCategoryId={2}
        onSelect={vi.fn()}
        onClose={vi.fn()}
      />
    );

    const input = screen.getByRole('textbox');
    expect(input).toHaveAttribute('placeholder', 'Gas');
  });

  it('shows "Uncategorized" as placeholder when selected category is not found', () => {
    render(
      <CategoryDropdown
        categories={mockCategories}
        selectedCategoryId={999}
        onSelect={vi.fn()}
        onClose={vi.fn()}
      />
    );

    const input = screen.getByRole('textbox');
    expect(input).toHaveAttribute('placeholder', 'Uncategorized');
  });
});
