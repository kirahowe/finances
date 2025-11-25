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
  const inputRef = useRef<HTMLInputElement>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);

  const filteredOptions = filterOptionsByQuery(options, searchQuery);
  const selectedSet = new Set(selectedValues);

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

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') {
      e.preventDefault();
      onClose();
    }
  };

  return (
    <div className="filter-dropdown" ref={dropdownRef}>
      <div className="filter-dropdown-header">
        <input
          ref={inputRef}
          type="text"
          className="filter-dropdown-search"
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={`Search ${label.toLowerCase()}...`}
        />
      </div>

      <ul className="filter-dropdown-list">
        {filteredOptions.map((option) => {
          const isSelected = selectedSet.has(option.value);
          return (
            <li
              key={String(option.value)}
              className="filter-dropdown-item"
              onClick={() => onToggle(option.value)}
            >
              <label className="filter-dropdown-checkbox-label">
                <input
                  type="checkbox"
                  checked={isSelected}
                  onChange={() => {}} // Handled by onClick on li
                  className="filter-dropdown-checkbox"
                />
                <span className="filter-dropdown-label-text">
                  {option.label}
                </span>
                {option.count !== undefined && (
                  <span className="filter-dropdown-count">{option.count}</span>
                )}
              </label>
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
