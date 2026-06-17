interface TableSearchProps {
  value: string;
  onChange: (value: string) => void;
}

// Free-text search over payee / description / category, sitting in the toolbar alongside
// the work-queue toggles. Payee and description are high-cardinality, so they get a
// search box rather than a facet (the actual matching lives in searchTransactions).
export function TableSearch({ value, onChange }: TableSearchProps) {
  return (
    <div className="table-search">
      <svg
        className="table-search-icon"
        width="14"
        height="14"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
      >
        <circle cx="11" cy="11" r="8" />
        <line x1="21" y1="21" x2="16.65" y2="16.65" />
      </svg>
      <input
        type="search"
        className="table-search-input"
        placeholder="Search payee, description…"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-label="Search transactions"
      />
    </div>
  );
}
