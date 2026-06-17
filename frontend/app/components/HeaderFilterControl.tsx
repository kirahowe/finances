import { useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { FilterDropdown } from './FilterDropdown';
import type { FilterOption } from '../lib/filterOptions';
import type { FilterValue } from '../lib/filterState';

interface HeaderFilterControlProps {
  label: string;
  options: FilterOption[];
  selectedValues: FilterValue[];
  onToggle: (value: FilterValue) => void;
  onClear: () => void;
}

// A funnel button mounted in a column header (Excel-style), opening the shared
// FilterDropdown. The table sits in a horizontal/vertical scroll container that would
// clip an absolutely-positioned menu, so the dropdown is portaled to <body> at fixed
// coordinates measured from the button — the same approach as the in-cell CategoryDropdown.
export function HeaderFilterControl({
  label,
  options,
  selectedValues,
  onToggle,
  onClear,
}: HeaderFilterControlProps) {
  const [open, setOpen] = useState(false);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const [pos, setPos] = useState<{ top: number; left: number } | null>(null);
  // Count only selections this funnel actually lists — a value it can neither show as
  // checked nor clear shouldn't light up its badge. This is what keeps the Category
  // funnel reading as unfiltered when only the by-sign Uncategorized sentinels are
  // selected: those are owned by the dedicated toolbar chip and kept out of `options`.
  const optionValues = new Set(options.map((o) => o.value));
  const relevantCount = selectedValues.filter((v) => optionValues.has(v)).length;
  const active = relevantCount > 0;

  // Track the button's viewport position so the portaled popover stays anchored to it,
  // including while the table scroll container scrolls.
  useLayoutEffect(() => {
    if (!open) return;
    const update = () => {
      const rect = buttonRef.current?.getBoundingClientRect();
      if (rect) setPos({ top: rect.bottom + 4, left: rect.left });
    };
    update();
    window.addEventListener('scroll', update, true);
    window.addEventListener('resize', update);
    return () => {
      window.removeEventListener('scroll', update, true);
      window.removeEventListener('resize', update);
    };
  }, [open]);

  return (
    <>
      <button
        ref={buttonRef}
        type="button"
        className={`th-filter-btn ${active ? 'is-active' : ''}`}
        aria-label={
          active ? `Filter ${label}, ${relevantCount} selected` : `Filter ${label}`
        }
        aria-haspopup="dialog"
        aria-expanded={open}
        // The header cell toggles sort on click; keep that from firing when the funnel is used.
        onClick={(e) => {
          e.stopPropagation();
          setOpen((o) => !o);
        }}
      >
        <svg
          className="th-filter-icon"
          width="13"
          height="13"
          viewBox="0 0 24 24"
          fill={active ? 'currentColor' : 'none'}
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3" />
        </svg>
        {active && <span className="th-filter-count">{relevantCount}</span>}
      </button>

      {open &&
        pos &&
        createPortal(
          // Only the runtime rect coordinates are inline (the sanctioned exception to the
          // no-inline-styles rule, as in CategoryDropdown); everything visual is in CSS.
          <div className="header-filter-popover" style={pos}>
            <FilterDropdown
              label={label}
              options={options}
              selectedValues={selectedValues}
              onToggle={onToggle}
              onClear={() => {
                onClear();
                setOpen(false);
              }}
              onClose={() => setOpen(false)}
              // The funnel button is its own toggle; don't let its mousedown count as an
              // outside click, or the popover would close then immediately re-open.
              ignoreRef={buttonRef}
              bare
            />
          </div>,
          document.body
        )}
    </>
  );
}
