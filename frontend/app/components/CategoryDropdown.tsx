import { useState, useRef, useEffect } from 'react';
import type { Category } from '../lib/api';
import { filterCategories, getSelectedIndex } from '../lib/categoryFiltering';
import { handleKeyboardNavigation } from '../lib/keyboardNavigation';

interface CategoryDropdownProps {
  categories: Category[];
  selectedCategoryId: number | null;
  onSelect: (categoryId: number | null) => void;
  onSelectAndNext?: (categoryId: number | null) => void;
  onClose: () => void;
}

export function CategoryDropdown({
  categories,
  selectedCategoryId,
  onSelect,
  onSelectAndNext,
  onClose,
}: CategoryDropdownProps) {
  const [filter, setFilter] = useState('');
  const [highlightedIndex, setHighlightedIndex] = useState(() => {
    // Initialize highlighted index to the selected category
    // Start at -1 if no category is selected (user must navigate)
    if (selectedCategoryId === null) {
      return -1;
    } else {
      const filteredCategories = filterCategories(categories, '');
      const selectedIdx = getSelectedIndex(filteredCategories, selectedCategoryId);
      return selectedIdx >= 0 ? selectedIdx + 1 : 0; // +1 for "Uncategorized" option
    }
  });
  const inputRef = useRef<HTMLInputElement>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const previousFilterRef = useRef('');

  // Filter categories based on input
  const filteredCategories = filterCategories(categories, filter);

  // Add "Uncategorized" option at the beginning
  const options = [
    { id: null, name: 'Uncategorized' },
    ...filteredCategories.map((cat) => ({
      id: cat['db/id'],
      name: cat['category/name'],
    })),
  ];

  // Reset highlighted index to first visible item when filter changes
  useEffect(() => {
    if (filter !== previousFilterRef.current) {
      previousFilterRef.current = filter;
      // Only reset if we're actually filtering (not on initial render or clear)
      if (filter.length > 0 && options.length > 0) {
        // If there are filtered categories, highlight the first one (index 1)
        // Otherwise, highlight "Uncategorized" (index 0)
        setHighlightedIndex(filteredCategories.length > 0 ? 1 : 0);
      }
    }
  }, [filter, options.length, filteredCategories.length]);

  // Focus input on mount
  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  // Handle click outside
  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (
        dropdownRef.current &&
        !dropdownRef.current.contains(event.target as Node)
      ) {
        onClose();
      }
    }

    document.addEventListener('mousedown', handleClickOutside);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, [onClose]);

  // Scroll highlighted item into view
  useEffect(() => {
    if (highlightedIndex >= 0 && highlightedIndex < options.length) {
      const highlightedElement = dropdownRef.current?.querySelector(
        `li[data-index="${highlightedIndex}"]`
      ) as HTMLElement | null;

      if (highlightedElement && typeof highlightedElement.scrollIntoView === 'function') {
        highlightedElement.scrollIntoView({ block: 'nearest' });
      }
    }
  }, [highlightedIndex, options.length]);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    const result = handleKeyboardNavigation(
      e.key,
      options.length,
      highlightedIndex
    );

    switch (result.action) {
      case 'next':
      case 'previous':
        e.preventDefault();
        setHighlightedIndex(result.highlightedIndex);
        break;

      case 'select':
        e.preventDefault();
        if (result.highlightedIndex >= 0 && result.highlightedIndex < options.length) {
          // Use onSelectAndNext if provided (for Enter key navigation), otherwise use onSelect
          if (onSelectAndNext) {
            onSelectAndNext(options[result.highlightedIndex].id);
          } else {
            onSelect(options[result.highlightedIndex].id);
          }
        }
        break;

      case 'close':
        e.preventDefault();
        onClose();
        break;
    }
  };

  const handleOptionClick = (categoryId: number | null) => {
    onSelect(categoryId);
  };

  // Find the selected category name for the placeholder
  const selectedCategoryName = selectedCategoryId === null
    ? 'Uncategorized'
    : categories.find(cat => cat['db/id'] === selectedCategoryId)?.['category/name'] || 'Uncategorized';

  return (
    <div className="category-dropdown" ref={dropdownRef}>
      <input
        ref={inputRef}
        type="text"
        className="category-dropdown-input"
        value={filter}
        onChange={(e) => setFilter(e.target.value)}
        onKeyDown={handleKeyDown}
        placeholder={selectedCategoryName}
      />
      <ul className="category-dropdown-list">
        {options.map((option, index) => (
          <li
            key={option.id ?? 'uncategorized'}
            data-index={index}
            className={`category-dropdown-item ${
              index === highlightedIndex ? 'highlighted' : ''
            }`}
            onClick={() => handleOptionClick(option.id)}
            onMouseEnter={() => setHighlightedIndex(index)}
          >
            {option.name}
          </li>
        ))}
        {options.length === 0 && (
          <li className="category-dropdown-item empty">No categories found</li>
        )}
      </ul>
    </div>
  );
}
