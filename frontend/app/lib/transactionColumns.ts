// Single source of truth for the transactions table's columns: their order,
// display label, default/min width, whether they can be hidden or resized, and
// whether they're "protected" (never clipped). Both the table
// (OptimisticTransactionTable), the column picker, and the auto-sizing logic read
// this so they never drift.

export interface TransactionColumnMeta {
  id: string;
  label: string;
  hideable: boolean;
  resizable: boolean;
  // Protected columns always get their full content width when auto-sizing; the
  // flexible columns clip first when the row is too wide for the container.
  protected: boolean;
  size: number;
  minSize: number;
}

// `size` here is only the fallback used for the first render before content-based
// auto-sizing kicks in (see useAutoColumnSizing); the table measures real content
// widths and stretches columns to fill, so these are rough.
export const TRANSACTION_COLUMNS: TransactionColumnMeta[] = [
  { id: 'date', label: 'Date', hideable: true, resizable: true, protected: true, size: 130, minSize: 80 },
  { id: 'account', label: 'Account', hideable: true, resizable: true, protected: false, size: 150, minSize: 90 },
  { id: 'institution', label: 'Institution', hideable: true, resizable: true, protected: false, size: 190, minSize: 90 },
  { id: 'payee', label: 'Payee', hideable: true, resizable: true, protected: false, size: 240, minSize: 100 },
  { id: 'description', label: 'Description', hideable: true, resizable: true, protected: false, size: 360, minSize: 200 },
  { id: 'amount', label: 'Amount', hideable: true, resizable: true, protected: true, size: 120, minSize: 90 },
  { id: 'category', label: 'Category', hideable: true, resizable: true, protected: true, size: 190, minSize: 150 },
  // A fixed-width checkbox column; protected so the checkbox is never clipped.
  { id: 'reviewed', label: 'Reviewed', hideable: true, resizable: false, protected: true, size: 90, minSize: 80 },
  // The row-actions caret is structural, not data — never hidden or resized.
  { id: 'actions', label: '', hideable: false, resizable: false, protected: true, size: 56, minSize: 56 },
];

const META_BY_ID: Record<string, TransactionColumnMeta> = Object.fromEntries(
  TRANSACTION_COLUMNS.map((c) => [c.id, c])
);

// Auto-sizing policy, kept beside the column defs so the table's layout behaviour
// stays in one place:
//  - MAX_COLUMN_WIDTH caps any single column (a double-click auto-fit, or a width
//    restored from the URL) so one long outlier can't make a column absurdly wide.
//  - FLEXIBLE_COLUMN_CAP caps the *default* width of non-protected columns so one
//    long row can't dominate the initial layout (a double-click still fully fits).
//  - SHRINK_ORDER is the order flexible columns give up width when the row overflows
//    the container. Description carries the most useful free text, so it shrinks LAST;
//    account and institution (often redundant with payee) give up width first. Date
//    and amount are protected, so they never shrink or clip.
//  - GROW_IDS are the wide text columns that absorb spare width to fill the table.
export const MAX_COLUMN_WIDTH = 600;
export const FLEXIBLE_COLUMN_CAP = 400;
export const COLUMN_SHRINK_ORDER = ['account', 'institution', 'payee', 'description'] as const;
export const COLUMN_GROW_IDS = ['description', 'payee'] as const;

export const PROTECTED_COLUMN_IDS = new Set(
  TRANSACTION_COLUMNS.filter((c) => c.protected).map((c) => c.id)
);

// Column-def props (size/min + resize/hide gates) for a given column id, spread
// into the TanStack column definition. `autoSize`, when provided, is the measured
// content-fit width and becomes the column's default (a user resize still wins via
// columnSizing state).
export function columnDefSizing(id: string, autoSize?: number) {
  const meta = META_BY_ID[id];
  return {
    size: autoSize ?? meta.size,
    minSize: meta.minSize,
    enableResizing: meta.resizable,
    enableHiding: meta.hideable,
  };
}

// Columns the user is allowed to toggle, for the column picker.
export const HIDEABLE_COLUMNS = TRANSACTION_COLUMNS.filter((c) => c.hideable).map(
  ({ id, label }) => ({ id, label })
);

// Just the ids of the toggleable columns, used to reject unknown/non-hideable ids
// when restoring column visibility from the URL.
export const HIDEABLE_COLUMN_IDS = HIDEABLE_COLUMNS.map((c) => c.id);
