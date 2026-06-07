import { useEffect, useRef, useState } from 'react';
import { useCombobox } from 'downshift';
import type { Category } from '../lib/api';
import { filterCategories, getSelectedIndex } from '../lib/categoryFiltering';

interface CategoryDropdownProps {
  categories: Category[];
  selectedCategoryId: number | null;
  onSelect: (categoryId: number | null) => void;
  onSelectAndNext?: (categoryId: number | null) => void;
  onClose: () => void;
}

interface CategoryOption {
  id: number | null;
  name: string;
}

const UNCATEGORIZED: CategoryOption = { id: null, name: 'Uncategorized' };

// "Uncategorized" leads the list so the category can always be cleared, even
// while filtering. Matching categories follow.
function getOptions(categories: Category[], filter: string): CategoryOption[] {
  const matched = filterCategories(categories, filter).map((cat) => ({
    id: cat['db/id'],
    name: cat['category/name'],
  }));
  return [UNCATEGORIZED, ...matched];
}

export function CategoryDropdown({
  categories,
  selectedCategoryId,
  onSelect,
  onSelectAndNext,
  onClose,
}: CategoryDropdownProps) {
  const [items, setItems] = useState<CategoryOption[]>(() => getOptions(categories, ''));
  const inputRef = useRef<HTMLInputElement>(null);

  // Offset by 1 for the leading "Uncategorized" option present at open.
  const selectedIndex = getSelectedIndex(categories, selectedCategoryId);
  const initialHighlightedIndex = selectedIndex >= 0 ? selectedIndex + 1 : -1;

  const selectedCategoryName =
    selectedCategoryId === null
      ? 'Uncategorized'
      : categories.find((cat) => cat['db/id'] === selectedCategoryId)?.['category/name'] ??
        'Uncategorized';

  const { getMenuProps, getInputProps, getItemProps, getLabelProps, highlightedIndex } =
    useCombobox<CategoryOption>({
      items,
      initialIsOpen: true,
      defaultHighlightedIndex: 0,
      initialHighlightedIndex,
      itemToString: () => '',
      // While filtering, highlight the first matching category (after the
      // leading "Uncategorized") so Enter picks the match, not Uncategorized.
      stateReducer: (_state, { type, changes }) => {
        if (type === useCombobox.stateChangeTypes.InputChange) {
          const filter = changes.inputValue ?? '';
          const hasMatch = filter.trim().length > 0 && filterCategories(categories, filter).length > 0;
          return { ...changes, highlightedIndex: hasMatch ? 1 : 0 };
        }
        return changes;
      },
      onInputValueChange: ({ inputValue }) => setItems(getOptions(categories, inputValue ?? '')),
      onSelectedItemChange: ({ selectedItem, type }) => {
        if (!selectedItem) return;
        // Enter confirms and advances to the next row; click just selects.
        if (type === useCombobox.stateChangeTypes.InputKeyDownEnter && onSelectAndNext) {
          onSelectAndNext(selectedItem.id);
        } else {
          onSelect(selectedItem.id);
        }
      },
      onIsOpenChange: ({ isOpen, type }) => {
        if (isOpen) return;
        if (
          type === useCombobox.stateChangeTypes.InputKeyDownEscape ||
          type === useCombobox.stateChangeTypes.InputBlur
        ) {
          onClose();
        }
      },
    });

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  return (
    <div className="category-dropdown">
      <label {...getLabelProps()} className="sr-only">
        Category
      </label>
      <input
        {...getInputProps({ ref: inputRef })}
        className="category-dropdown-input"
        placeholder={selectedCategoryName}
      />
      <ul {...getMenuProps()} className="category-dropdown-list">
        {items.map((item, index) => (
          <li
            key={item.id ?? 'uncategorized'}
            className={`category-dropdown-item ${
              index === highlightedIndex ? 'highlighted' : ''
            }`}
            {...getItemProps({ item, index })}
          >
            {item.name}
          </li>
        ))}
      </ul>
    </div>
  );
}
