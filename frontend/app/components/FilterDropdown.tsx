import { useState, useRef, useEffect } from 'react';
import { filterOptionsByQuery, type FilterOption } from '../lib/filterOptions';
import type { FilterValue } from '../lib/filterState';

interface FilterDropdownProps {
  label: string;
  options: FilterOption[];
  selectedValues: FilterValue[];
  onToggle: (value: FilterValue) => void;
  onClear: () => void;
  onClose: () => void;
}

export function FilterDropdown({
  label,
  options,
  selectedValues,
  onToggle,
  onClear,
  onClose,
}: FilterDropdownProps) {
  const [searchQuery, setSearchQuery] = useState('');
  const [highlightedIndex, setHighlightedIndex] = useState(-1);
  const inputRef = useRef<HTMLInputElement>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const listRef = useRef<HTMLUListElement>(null);

  const filteredOptions = filterOptionsByQuery(options, searchQuery);
  const selectedSet = new Set(selectedValues);

  // Focus input on mount
  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  // Reset highlighted index when filtered options change
  useEffect(() => {
    setHighlightedIndex(-1);
  }, [searchQuery]);

  // Scroll highlighted item into view
  useEffect(() => {
    if (highlightedIndex >= 0 && highlightedIndex < filteredOptions.length) {
      const highlightedElement = listRef.current?.querySelector(
        `li[data-index="${highlightedIndex}"]`
      ) as HTMLElement | null;

      if (highlightedElement && typeof highlightedElement.scrollIntoView === 'function') {
        highlightedElement.scrollIntoView({ block: 'nearest' });
      }
    }
  }, [highlightedIndex, filteredOptions.length]);

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

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') {
      e.preventDefault();
      onClose();
      return;
    }

    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setHighlightedIndex(prev =>
        prev < filteredOptions.length - 1 ? prev + 1 : prev
      );
      return;
    }

    if (e.key === 'ArrowUp') {
      e.preventDefault();
      setHighlightedIndex(prev => (prev > 0 ? prev - 1 : prev));
      return;
    }

    if (e.key === 'Enter' && highlightedIndex >= 0 && highlightedIndex < filteredOptions.length) {
      e.preventDefault();
      onToggle(filteredOptions[highlightedIndex].value);
      return;
    }
  };

  return (
    <div className="filter-dropdown" ref={dropdownRef} onKeyDown={handleKeyDown}>
      <div className="filter-dropdown-header">
        <input
          ref={inputRef}
          type="text"
          className="filter-dropdown-search"
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          placeholder={`Search ${label.toLowerCase()}...`}
        />
      </div>

      <ul className="filter-dropdown-list" ref={listRef}>
        {filteredOptions.map((option, index) => {
          const isSelected = selectedSet.has(option.value);
          const isHighlighted = index === highlightedIndex;
          return (
            <li
              key={String(option.value)}
              data-index={index}
              className={`filter-dropdown-item ${isHighlighted ? 'highlighted' : ''}`}
              onClick={() => onToggle(option.value)}
              onMouseEnter={() => setHighlightedIndex(index)}
            >
              <div className="filter-dropdown-checkbox-label">
                <input
                  type="checkbox"
                  checked={isSelected}
                  onChange={() => {}} // Handled by onClick on li
                  className="filter-dropdown-checkbox"
                  tabIndex={-1}
                />
                <span className="filter-dropdown-label-text">
                  {option.label}
                </span>
                {option.count !== undefined && (
                  <span className="filter-dropdown-count">{option.count}</span>
                )}
              </div>
            </li>
          );
        })}
        {filteredOptions.length === 0 && (
          <li className="filter-dropdown-item empty">No options found</li>
        )}
      </ul>

      {selectedValues.length > 0 && (
        <div className="filter-dropdown-footer">
          <button
            type="button"
            className="button button-secondary filter-dropdown-clear"
            onClick={onClear}
          >
            Clear all
          </button>
        </div>
      )}
    </div>
  );
}
