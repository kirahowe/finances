import { useLayoutEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useCombobox } from 'downshift';
import type { Category } from '../lib/api';
import { hasMatchingCategory } from '../lib/categoryFiltering';
import {
  buildCategoryDropdownRows,
  headerCategoryIds,
  type DropdownOption,
} from '../lib/categoryHierarchy';

interface CategoryDropdownProps {
  categories: Category[];
  selectedCategoryId: number | null;
  onSelect: (categoryId: number | null) => void;
  onSelectAndNext?: (categoryId: number | null) => void;
  onClose: () => void;
  // When the table lives in a horizontal-scroll container the absolutely-positioned
  // list would be clipped, so render it in a body portal with fixed coordinates.
  portalMenu?: boolean;
}

export function CategoryDropdown({
  categories,
  selectedCategoryId,
  onSelect,
  onSelectAndNext,
  onClose,
  portalMenu = false,
}: CategoryDropdownProps) {
  const [filter, setFilter] = useState('');
  const anchorRef = useRef<HTMLDivElement>(null);
  const [menuPos, setMenuPos] = useState<{ top: number; left: number; width: number } | null>(null);

  // The grouped render model: `items` drives Downshift's keyboard navigation
  // (selectable only, so headers are skipped); `rows` interleaves the headers.
  const { items, rows } = useMemo(
    () => buildCategoryDropdownRows(categories, filter),
    [categories, filter]
  );
  // Header categories aren't selectable, so highlight/"has match" reason only
  // over the selectable categories.
  const selectableCategories = useMemo(() => {
    const headers = headerCategoryIds(categories);
    return categories.filter((c) => !headers.has(c['db/id']));
  }, [categories]);

  // Track the input's viewport position so the portaled list stays anchored to it
  // (including when the table scroll container scrolls).
  useLayoutEffect(() => {
    if (!portalMenu) return;
    const update = () => {
      const rect = anchorRef.current?.getBoundingClientRect();
      if (rect) setMenuPos({ top: rect.bottom + 4, left: rect.left, width: rect.width });
    };
    update();
    window.addEventListener('scroll', update, true);
    window.addEventListener('resize', update);
    return () => {
      window.removeEventListener('scroll', update, true);
      window.removeEventListener('resize', update);
    };
  }, [portalMenu]);

  // Placeholder shows the assigned category; the initial highlight points at its
  // position in the selectable `items` list (-1 when none/uncategorized, so the
  // first ArrowDown lands on "Uncategorized" rather than skipping past it).
  const selectedCategoryName =
    categories.find((c) => c['db/id'] === selectedCategoryId)?.['category/name'] ?? 'Uncategorized';
  const initialHighlightedIndex =
    selectedCategoryId === null ? -1 : items.findIndex((o) => o.id === selectedCategoryId);

  const { getMenuProps, getInputProps, getItemProps, getLabelProps, highlightedIndex } =
    useCombobox<DropdownOption>({
      items,
      initialIsOpen: true,
      defaultHighlightedIndex: 0,
      initialHighlightedIndex,
      itemToString: () => '',
      // Move the highlight in the reducer (synchronously, same render as the
      // keystroke) so it never flickers through "Uncategorized" first. While
      // filtering, highlight the first matching category — index 1, after the
      // leading "Uncategorized" — so Enter picks the match, not Uncategorized.
      // Only an existence check here; onInputValueChange rebuilds the full list.
      stateReducer: (_state, { type, changes }) => {
        if (type === useCombobox.stateChangeTypes.InputChange) {
          const hasMatch = hasMatchingCategory(selectableCategories, changes.inputValue ?? '');
          return { ...changes, highlightedIndex: hasMatch ? 1 : 0 };
        }
        return changes;
      },
      onInputValueChange: ({ inputValue }) => setFilter(inputValue ?? ''),
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

  // getMenuProps must be called every render and its ref attached, so the list is
  // always rendered; when portaling it's hidden until its position is measured.
  const menu = (
    <ul
      {...getMenuProps()}
      className={`category-dropdown-list ${portalMenu ? 'category-dropdown-list-portal' : ''}`}
      style={portalMenu ? (menuPos ? { ...menuPos } : { visibility: 'hidden' }) : undefined}
    >
      {rows.map((row) =>
        row.kind === 'header' ? (
          <li key={row.key} className="category-dropdown-group-header" role="presentation">
            {row.name}
          </li>
        ) : (
          <li
            key={row.option.id ?? 'uncategorized'}
            className={`category-dropdown-item ${
              row.depth > 0 ? 'category-dropdown-item--child' : ''
            } ${row.itemIndex === highlightedIndex ? 'highlighted' : ''}`}
            {...getItemProps({ item: row.option, index: row.itemIndex })}
          >
            {row.option.name}
          </li>
        )
      )}
    </ul>
  );

  return (
    <div className="category-dropdown" ref={anchorRef}>
      <label {...getLabelProps()} className="sr-only">
        Category
      </label>
      <input
        {...getInputProps()}
        className="category-dropdown-input"
        placeholder={selectedCategoryName}
      />
      {portalMenu ? createPortal(menu, document.body) : menu}
    </div>
  );
}
