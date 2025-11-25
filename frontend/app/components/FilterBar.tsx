import { FilterButton } from './FilterButton';
import type { FilterOption } from '../lib/filterOptions';
import type { FilterValue, FilterState } from '../lib/filterState';
import { hasActiveFilters } from '../lib/filterState';

export interface FilterConfig {
  field: string;
  label: string;
  options: FilterOption[];
}

interface FilterBarProps {
  filters: FilterConfig[];
  filterState: FilterState;
  onToggleValue: (field: string, value: FilterValue) => void;
  onClearField: (field: string) => void;
  onClearAll: () => void;
}

export function FilterBar({
  filters,
  filterState,
  onToggleValue,
  onClearField,
  onClearAll,
}: FilterBarProps) {
  const hasFilters = hasActiveFilters(filterState);

  return (
    <div className="filter-bar">
      <div className="filter-bar-filters">
        {filters.map((filter) => (
          <FilterButton
            key={filter.field}
            label={filter.label}
            options={filter.options}
            selectedValues={filterState[filter.field] || []}
            onToggle={(value) => onToggleValue(filter.field, value)}
            onClear={() => onClearField(filter.field)}
          />
        ))}
      </div>

      {hasFilters && (
        <button
          type="button"
          className="button button-secondary filter-bar-clear-all"
          onClick={onClearAll}
        >
          Clear all filters
        </button>
      )}
    </div>
  );
}
