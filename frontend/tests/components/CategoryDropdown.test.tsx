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

    const input = screen.getByRole('combobox');
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

    const input = screen.getByRole('combobox');
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

    await user.keyboard('{ArrowDown}');

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

    await user.keyboard('{ArrowUp}');

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

    await user.keyboard('{ArrowDown}');
    await user.keyboard('{Enter}');

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

    await user.keyboard('{Escape}');

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

  it('highlights the first match while filtering and Enter selects it, not Uncategorized', async () => {
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

    const input = screen.getByRole('combobox');
    await user.type(input, 'sal'); // matches only Salary (id 3)

    const salaryOption = screen.getByText('Salary').closest('li');
    expect(salaryOption).toHaveClass('highlighted');

    await user.keyboard('{Enter}');
    expect(onSelect).toHaveBeenCalledWith(3);
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

    const input = screen.getByRole('combobox');
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

    const input = screen.getByRole('combobox');
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

    const input = screen.getByRole('combobox');
    expect(input).toHaveAttribute('placeholder', 'Uncategorized');
  });
});

describe('CategoryDropdown grouping', () => {
  const hierarchical: Category[] = [
    { 'db/id': 1, 'category/name': 'Food', 'category/type': 'expense', 'category/sort-order': 0 },
    {
      'db/id': 2,
      'category/name': 'Groceries',
      'category/type': 'expense',
      'category/parent': { 'db/id': 1 },
      'category/sort-order': 0,
    },
    {
      'db/id': 3,
      'category/name': 'Dining',
      'category/type': 'expense',
      'category/parent': { 'db/id': 1 },
      'category/sort-order': 1,
    },
    { 'db/id': 4, 'category/name': 'Salary', 'category/type': 'income', 'category/sort-order': 1 },
  ];

  it('renders a parent as a selectable, emphasized group row', () => {
    render(
      <CategoryDropdown
        categories={hierarchical}
        selectedCategoryId={null}
        onSelect={vi.fn()}
        onClose={vi.fn()}
      />
    );

    const optionLabels = screen.getAllByRole('option').map((el) => el.textContent);
    // Parents are selectable in their own right.
    expect(optionLabels).toContain('Food');
    expect(optionLabels).toContain('Groceries');
    expect(screen.getByText('Food').closest('li')).toHaveClass('category-dropdown-item--parent');
  });

  it('indents child categories beneath their parent', () => {
    render(
      <CategoryDropdown
        categories={hierarchical}
        selectedCategoryId={null}
        onSelect={vi.fn()}
        onClose={vi.fn()}
      />
    );

    expect(screen.getByText('Groceries').closest('li')).toHaveClass('category-dropdown-item--child');
    // A childless top-level category stays a normal (non-indented) option.
    expect(screen.getByText('Salary').closest('li')).not.toHaveClass('category-dropdown-item--child');
  });

  it('selects a parent category on click', async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();

    render(
      <CategoryDropdown
        categories={hierarchical}
        selectedCategoryId={null}
        onSelect={onSelect}
        onClose={vi.fn()}
      />
    );

    await user.click(screen.getByText('Food'));

    expect(onSelect).toHaveBeenCalledWith(1);
  });

  it('selects a child category on click', async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();

    render(
      <CategoryDropdown
        categories={hierarchical}
        selectedCategoryId={null}
        onSelect={onSelect}
        onClose={vi.fn()}
      />
    );

    await user.click(screen.getByText('Groceries'));

    expect(onSelect).toHaveBeenCalledWith(2);
  });
});
