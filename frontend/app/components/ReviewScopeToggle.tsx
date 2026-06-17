interface ReviewScopeToggleProps {
  /** "needs-review" keeps the unreviewed filter on; "all" clears it. */
  mode: "needs-review" | "all";
  unreviewedCount: number;
  totalCount: number;
  onSelectNeedsReview: () => void;
  onSelectAll: () => void;
}

/** A segmented switch between the review queue (unreviewed only) and everything.
 * It reads and writes the same `reviewed` filter the rest of the app uses. */
export function ReviewScopeToggle({
  mode,
  unreviewedCount,
  totalCount,
  onSelectNeedsReview,
  onSelectAll,
}: ReviewScopeToggleProps) {
  return (
    <div className="scope-toggle" role="group" aria-label="Review scope">
      <button
        type="button"
        className={`scope-toggle-btn${mode === "needs-review" ? " is-active" : ""}`}
        aria-pressed={mode === "needs-review"}
        onClick={onSelectNeedsReview}
      >
        Needs review
        <span className="scope-toggle-count">{unreviewedCount}</span>
      </button>
      <button
        type="button"
        className={`scope-toggle-btn${mode === "all" ? " is-active" : ""}`}
        aria-pressed={mode === "all"}
        onClick={onSelectAll}
      >
        All
        <span className="scope-toggle-count">{totalCount}</span>
      </button>
    </div>
  );
}
