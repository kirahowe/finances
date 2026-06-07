import { useState } from 'react';
import { useCombobox } from 'downshift';
import type { Category } from '../lib/api';
import { filterCategories, getSelectedIndex, hasMatchingCategory } from '../lib/categoryFiltering';

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

  // One pass over `categories` yields both the placeholder name and the
  // highlight position (offset by 1 for the leading "Uncategorized" option).
  const selectedIndex = getSelectedIndex(categories, selectedCategoryId);
  const selectedCategoryName =
    selectedIndex >= 0 ? categories[selectedIndex]['category/name'] : 'Uncategorized';
  const initialHighlightedIndex = selectedIndex >= 0 ? selectedIndex + 1 : -1;

  const { getMenuProps, getInputProps, getItemProps, getLabelProps, highlightedIndex } =
    useCombobox<CategoryOption>({
      items,
      initialIsOpen: true,
      defaultHighlightedIndex: 0,
      initialHighlightedIndex,
      itemToString: () => '',
      // Move the highlight in the reducer (synchronously, same render as the
      // keystroke) so it never flickers through "Uncategorized" first. While
      // filtering, highlight the first matching category — index 1, after the
      // leading "Uncategorized" — so Enter picks the match, not Uncategorized.
      // Only an existence check here; onInputValueChange builds the full list.
      stateReducer: (_state, { type, changes }) => {
        if (type === useCombobox.stateChangeTypes.InputChange) {
          const hasMatch = hasMatchingCategory(categories, changes.inputValue ?? '');
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

  return (
    <div className="category-dropdown">
      <label {...getLabelProps()} className="sr-only">
        Category
      </label>
      <input
        {...getInputProps()}
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
