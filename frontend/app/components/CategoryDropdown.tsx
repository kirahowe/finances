import { useLayoutEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useCombobox } from 'downshift';
import type { Category } from '../lib/api';
import { buildCategoryDropdownModel, type DropdownOption } from '../lib/categoryHierarchy';

interface CategoryDropdownProps {
  categories: Category[];
  selectedCategoryId: number | null;
  onSelect: (categoryId: number | null) => void;
  onSelectAndNext?: (categoryId: number | null) => void;
  onClose: () => void;
  // When the table lives in a horizontal-scroll container the absolutely-positioned
  // list would be clipped, so render it in a body portal with fixed coordinates.
  portalMenu?: boolean;
  // When opened by type-to-edit, the character typed to open it — seeds the filter
  // so the list opens already narrowed (and the first match is highlighted).
  initialFilter?: string;
}

export function CategoryDropdown({
  categories,
  selectedCategoryId,
  onSelect,
  onSelectAndNext,
  onClose,
  portalMenu = false,
  initialFilter,
}: CategoryDropdownProps) {
  const [filter, setFilter] = useState(initialFilter ?? '');
  const anchorRef = useRef<HTMLDivElement>(null);
  const [menuPos, setMenuPos] = useState<{ top: number; left: number; width: number } | null>(null);

  // The grouped render model: every entry is selectable (parents and children
  // alike); `entries` is the render order and `items` the parallel option list
  // Downshift navigates by index.
  const { entries, firstMatchIndex } = useMemo(
    () => buildCategoryDropdownModel(categories, filter),
    [categories, filter]
  );
  const items = useMemo(() => entries.map((e) => e.option), [entries]);

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
  // position in `items` (-1 when none/uncategorized, so the first ArrowDown lands
  // on "Uncategorized" rather than skipping past it).
  const selectedCategoryName =
    categories.find((c) => c['db/id'] === selectedCategoryId)?.['category/name'] ?? 'Uncategorized';
  // With a seeded filter, highlight its first direct match (so Enter picks it);
  // otherwise highlight the currently-assigned category's row. On mount (the only
  // time downshift reads this) `filter` equals `initialFilter`, so the memoized
  // firstMatchIndex above is exactly the seed's first match.
  const initialHighlightedIndex = initialFilter
    ? firstMatchIndex
    : selectedCategoryId === null
      ? -1
      : items.findIndex((o) => o.id === selectedCategoryId);

  const { getMenuProps, getInputProps, getItemProps, getLabelProps, highlightedIndex } =
    useCombobox<DropdownOption>({
      items,
      initialIsOpen: true,
      initialInputValue: initialFilter ?? '',
      defaultHighlightedIndex: 0,
      initialHighlightedIndex,
      itemToString: () => '',
      // Move the highlight in the reducer (synchronously, same render as the
      // keystroke) so it never flickers through "Uncategorized" first. Highlight
      // the first row that directly matches — which, once parents can appear as
      // non-matching context, isn't always index 1 — so Enter picks the match.
      stateReducer: (_state, { type, changes }) => {
        if (type === useCombobox.stateChangeTypes.InputChange) {
          const { firstMatchIndex } = buildCategoryDropdownModel(categories, changes.inputValue ?? '');
          return { ...changes, highlightedIndex: firstMatchIndex };
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
      className={`category-dropdown-list ${portalMenu ? 'category-dropdown-list-portal' : ''} ${
        portalMenu && !menuPos ? 'category-dropdown-list-portal--measuring' : ''
      }`}
      // Only the runtime rect coordinates stay inline; the hidden-while-measuring
      // state is a CSS class (per the project's no-inline-styles rule).
      style={portalMenu && menuPos ? menuPos : undefined}
    >
      {entries.map((entry, index) => (
        <li
          key={entry.option.id ?? 'uncategorized'}
          className={`category-dropdown-item ${
            entry.depth > 0 ? 'category-dropdown-item--child' : ''
          } ${entry.isParent ? 'category-dropdown-item--parent' : ''} ${
            index === highlightedIndex ? 'highlighted' : ''
          }`}
          {...getItemProps({ item: entry.option, index })}
        >
          {entry.option.name}
        </li>
      ))}
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
