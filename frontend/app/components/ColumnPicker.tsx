import { useEffect, useRef, useState } from 'react';
import type { VisibilityState } from '@tanstack/react-table';

interface ColumnPickerProps {
  columns: { id: string; label: string }[];
  visibility: VisibilityState;
  onChange: (next: VisibilityState) => void;
  // Clears any user-resized column widths (back to defaults). Shown in the footer.
  onResetWidths?: () => void;
}

// A small "Columns" dropdown for toggling which table columns are shown. Visibility
// is a controlled VisibilityState (an id is hidden when its value is false; absent
// means visible), owned by the page so it can live in the URL alongside sort/filters.
export function ColumnPicker({ columns, visibility, onChange, onResetWidths }: ColumnPickerProps) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onMouseDown = (e: MouseEvent) => {
      if (!containerRef.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onMouseDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onMouseDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const toggle = (id: string, currentlyVisible: boolean) => {
    const next = { ...visibility };
    if (currentlyVisible) next[id] = false; // hide it
    else delete next[id]; // show it (absent = visible)
    onChange(next);
  };

  return (
    <div className="filter-button-container" ref={containerRef}>
      <button
        type="button"
        className="button button-secondary filter-button"
        aria-haspopup="true"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
      >
        Columns
        <span className="filter-button-arrow">{open ? '▲' : '▼'}</span>
      </button>

      {open && (
        <div className="filter-dropdown">
          <ul className="filter-dropdown-list">
            {columns.map((column) => {
              const isVisible = visibility[column.id] !== false;
              return (
                <li
                  key={column.id}
                  className="filter-dropdown-item"
                  onClick={() => toggle(column.id, isVisible)}
                >
                  {/* A <div>, not a <label>: a label would natively toggle the
                      checkbox AND bubble to the li handler, double-toggling. */}
                  <div className="filter-dropdown-checkbox-label">
                    <input
                      type="checkbox"
                      checked={isVisible}
                      onChange={() => {}}
                      className="filter-dropdown-checkbox"
                      tabIndex={-1}
                    />
                    <span className="filter-dropdown-label-text">{column.label}</span>
                  </div>
                </li>
              );
            })}
          </ul>
          {onResetWidths && (
            <div className="filter-dropdown-footer">
              <button
                type="button"
                className="button button-secondary filter-dropdown-clear"
                onClick={onResetWidths}
              >
                Reset widths
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
