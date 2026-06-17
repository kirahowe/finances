import type { FilterValue } from '../lib/filterState';

export interface ActiveChip {
  /** Filter field the chip belongs to ("account" / "institution" / "category"), or the
   *  synthetic "search" field for the free-text term. */
  field: string;
  /** The value to remove when the chip is dismissed (the search string for "search"). */
  value: FilterValue;
  /** Short field name shown as the chip's lead-in ("Account"). */
  fieldLabel: string;
  /** The human label for the value ("Chase", "Groceries", the search term). */
  valueLabel: string;
}

interface ActiveFilterChipsProps {
  chips: ActiveChip[];
  onRemove: (field: string, value: FilterValue) => void;
  onClearAll: () => void;
}

// A summary row of every active filter as an individually-removable pill, plus Clear all.
// With the attribute filters now living in the column headers, this is the one place that
// shows — at a glance — everything currently narrowing the table (Baymard: an applied-
// filters overview sharply reduces "why am I seeing this?" errors).
export function ActiveFilterChips({ chips, onRemove, onClearAll }: ActiveFilterChipsProps) {
  if (chips.length === 0) return null;

  return (
    <div className="active-chips" aria-label="Active filters">
      {chips.map((chip) => (
        <span key={`${chip.field}:${chip.value}`} className="active-chip">
          <span className="active-chip-field">{chip.fieldLabel}</span>
          <span className="active-chip-value">{chip.valueLabel}</span>
          <button
            type="button"
            className="active-chip-remove"
            aria-label={`Remove ${chip.fieldLabel} ${chip.valueLabel} filter`}
            onClick={() => onRemove(chip.field, chip.value)}
          >
            ×
          </button>
        </span>
      ))}
      <button type="button" className="active-chips-clear" onClick={onClearAll}>
        Clear all
      </button>
    </div>
  );
}
