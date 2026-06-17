interface CountToggleChipProps {
  label: string;
  /** Optional count shown in a pill (e.g. how many rows the filter applies to). */
  count?: number;
  active: boolean;
  onToggle: () => void;
}

/** A binary show/hide filter rendered as a toggle button with an optional count —
 * the quick "what needs work" controls (uncategorized, hide transfers). */
export function CountToggleChip({ label, count, active, onToggle }: CountToggleChipProps) {
  return (
    <button
      type="button"
      className={`count-chip${active ? " is-active" : ""}`}
      aria-pressed={active}
      onClick={onToggle}
    >
      {label}
      {count !== undefined && <span className="count-chip-num">{count}</span>}
    </button>
  );
}
