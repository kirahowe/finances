import { useState } from 'react';
import { FilterDropdown } from './FilterDropdown';
import type { FilterOption } from '../lib/filterOptions';
import type { FilterValue } from '../lib/filterState';

interface FilterButtonProps {
  label: string;
  options: FilterOption[];
  selectedValues: FilterValue[];
  onToggle: (value: FilterValue) => void;
  onClear: () => void;
}

export function FilterButton({
  label,
  options,
  selectedValues,
  onToggle,
  onClear,
}: FilterButtonProps) {
  const [isOpen, setIsOpen] = useState(false);

  const selectedCount = selectedValues.length;
  const buttonText = selectedCount === 0
    ? label
    : selectedCount === 1
    ? `${label}: 1 selected`
    : `${label}: ${selectedCount} selected`;

  return (
    <div className="filter-button-container">
      <button
        type="button"
        className={`button button-secondary filter-button ${selectedCount > 0 ? 'filter-button-active' : ''}`}
        onClick={() => setIsOpen(!isOpen)}
      >
        {buttonText}
        <span className="filter-button-arrow">{isOpen ? '▲' : '▼'}</span>
      </button>

      {selectedCount > 0 && (
        <button
          type="button"
          className="button button-secondary filter-button-close"
          onClick={(e) => {
            e.stopPropagation();
            onClear();
          }}
          aria-label={`Clear ${label} filter`}
        >
          ×
        </button>
      )}

      {isOpen && (
        <FilterDropdown
          label={label}
          options={options}
          selectedValues={selectedValues}
          onToggle={onToggle}
          onClear={() => {
            onClear();
            setIsOpen(false);
          }}
          onClose={() => setIsOpen(false)}
        />
      )}
    </div>
  );
}
