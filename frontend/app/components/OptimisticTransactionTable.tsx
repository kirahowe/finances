import {
  useState,
  useMemo,
  useRef,
  useEffect,
  useLayoutEffect,
  Fragment,
  type KeyboardEvent,
} from 'react';
import {
  useReactTable,
  getCoreRowModel,
  getSortedRowModel,
  flexRender,
  createColumnHelper,
  type SortingState,
  type VisibilityState,
  type ColumnSizingState,
  type OnChangeFn,
} from '@tanstack/react-table';
import { type Transaction, type Category, type Split } from '../lib/api';
import { formatAmount, formatDate } from '../lib/format';
import { sortSplits } from '../lib/splitMath';
import { columnDefSizing } from '../lib/transactionColumns';
import { useAutoColumnSizing } from '../lib/useAutoColumnSizing';
import { useGridNavigation } from '../lib/useGridNavigation';
import {
  buildGridModel,
  navigableColumns,
  cellKey,
  resolveIntent,
  EDITABLE_COLUMN_IDS,
  type RowKey,
  type ColId,
} from '../lib/gridNavigation';
import { CategoryDropdown } from './CategoryDropdown';
import { EditableDescriptionCell } from './EditableDescriptionCell';
import { RowActionsMenu, type RowAction } from './RowActionsMenu';
import { HeaderFilterControl } from './HeaderFilterControl';
import type { FilterState, FilterValue } from '../lib/filterState';
import type { FilterOption } from '../lib/filterOptions';
import '../styles/components/split-rows.css';
import '../styles/components/transfer-modal.css';
import '../styles/components/transactions-table.css';
import '../styles/components/grid-navigation.css';

// Branch/split marker shown on each line of a split transaction.
function SplitIcon({ drift }: { drift?: boolean }) {
  return (
    <svg
      className={`split-icon ${drift ? 'split-icon-drift' : ''}`}
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
      <title>{drift ? 'Split parts no longer add up to the amount' : 'Part of a split'}</title>
      <polyline points="15 10 20 15 15 20" />
      <path d="M4 4v7a4 4 0 0 0 4 4h12" />
    </svg>
  );
}

interface OptimisticTransactionTableProps {
  transactions: Transaction[];
  categories: Category[];
  page?: number;
  pageSize?: number;
  sorting: SortingState;
  onSortingChange: OnChangeFn<SortingState>;
  // Column view state is controlled by the page (so it can live in the URL). When
  // omitted, the table manages it internally with sensible defaults.
  columnVisibility?: VisibilityState;
  onColumnVisibilityChange?: OnChangeFn<VisibilityState>;
  columnSizing?: ColumnSizingState;
  onColumnSizingChange?: OnChangeFn<ColumnSizingState>;
  onSplit?: (transaction: Transaction) => void;
  // Open the transfer modal for a transfer row (matched or unmatched).
  onOpenTransfer?: (transaction: Transaction) => void;
  // Reviewed toggles. The table renders the checkbox straight from the (already
  // overlaid) data and reports clicks up; the owner holds the optimistic projection and
  // debounces persistence (see reviewedOverlay / useWriteBehind).
  onToggleReviewed?: (transactionId: number, reviewed: boolean) => void;
  onToggleSplitReviewed?: (transactionId: number, splitId: number, reviewed: boolean) => void;
  // Edit a transaction's description in place. The table renders the displayed
  // (effective) value and reports committed edits up; the owner holds the optimistic
  // projection and debounces persistence (see descriptionOverlay / useWriteBehind). When
  // omitted, the Description column is read-only text.
  onEditDescription?: (transactionId: number, description: string) => void;
  // Edit one split part's description (its memo) in place, on the part's own row. When
  // omitted, a split part's description cell shows only the branch marker.
  onEditSplitDescription?: (transactionId: number, splitId: number, description: string) => void;
  // Edit an unsplit transaction's category in place. The table renders the (already
  // overlaid) category and reports the chosen id up; the owner holds the optimistic
  // projection and debounces persistence (see categoryOverlay / useWriteBehind).
  onEditCategory?: (transactionId: number, categoryId: number | null) => void;
  // In-header (Excel-style) column filters. When provided, filterable columns
  // (account / institution / category) grow a funnel that opens the shared filter
  // dropdown, bound to the same filter state the rest of the app uses.
  filterState?: FilterState;
  filterOptionsByField?: Record<string, FilterOption[]>;
  onToggleFilterValue?: (field: string, value: FilterValue) => void;
  onClearFilterField?: (field: string) => void;
  // Rows kept visible after an edit moved them out of the active filter (the "linger"
  // set). They render de-emphasized with a moved marker until the next filter reset.
  staleTransactionIds?: Set<number>;
}

// Columns whose header carries a filter funnel, with the label shown in the popover.
const HEADER_FILTER_LABELS: Record<string, string> = {
  account: 'Account',
  institution: 'Institution',
  category: 'Category',
};

export function OptimisticTransactionTable({
  transactions,
  categories,
  page = 0,
  pageSize,
  sorting,
  onSortingChange,
  // Default to empty objects (not undefined) so getSize()/visibility keep TanStack's
  // own defaults; passing `undefined` for a state key would clobber them.
  columnVisibility = {},
  onColumnVisibilityChange,
  columnSizing = {},
  onColumnSizingChange,
  onSplit,
  onOpenTransfer,
  onToggleReviewed,
  onToggleSplitReviewed,
  onEditDescription,
  onEditSplitDescription,
  onEditCategory,
  filterState,
  filterOptionsByField,
  onToggleFilterValue,
  onClearFilterField,
  staleTransactionIds,
}: OptimisticTransactionTableProps) {
  // Spreadsheet keyboard navigation: a single state machine (active cell + nav/
  // edit mode) decides which cell is focused and which editor is open, replacing
  // the old independent editing cursors. The pure core lives in gridNavigation.ts;
  // the model is published below once the displayed rows + visible columns are known.
  const gridNav = useGridNavigation();

  // Measured content-fit widths (stretched to fill), used as each column's default
  // size. A user resize (columnSizing) overrides per-column. Not persisted — it's
  // derived from the data + container width on each load.
  const [autoSizing, setAutoSizing] = useState<Record<string, number>>({});

  // Sort each split's parts once per data change, not on every render (which is
  // re-triggered by unrelated state like editing or fetcher activity).
  const sortedPartsByTx = useMemo(() => {
    const byId = new Map<number, Split[]>();
    for (const tx of transactions) {
      const parts = tx['transaction/splits'];
      if (parts && parts.length > 0) byId.set(tx['db/id'], sortSplits(parts));
    }
    return byId;
  }, [transactions]);

  const columnHelper = createColumnHelper<Transaction>();

  // Keyboard-driven side effects the nav layer routes for non-inline cells:
  // toggling a reviewed checkbox (Space / Enter) and opening the split modal from
  // a split part's category cell (Enter). Both read the current value from the
  // (already overlaid) data and report up the usual way.
  const toggleReviewedAt = (key: RowKey) => {
    if (key.splitId == null) {
      const tx = transactions.find((t) => t['db/id'] === key.txId);
      onToggleReviewed?.(key.txId, !(tx?.['transaction/reviewed'] === true));
    } else {
      const part = sortedPartsByTx.get(key.txId)?.find((s) => s['db/id'] === key.splitId);
      onToggleSplitReviewed?.(key.txId, key.splitId, !(part?.['split/reviewed'] === true));
    }
  };
  const openSplitFor = (txId: number) => {
    const tx = transactions.find((t) => t['db/id'] === txId);
    if (tx) onSplit?.(tx);
  };

  // The category to display: read straight from the (already overlaid) transaction. The
  // optimistic value is applied above the filter in the owner via categoryOverlay, so the
  // cell, the filter predicate, and the counts all agree — no per-cell fetcher state.
  const categoryOf = (transaction: Transaction): { id: number | null; name: string } => {
    const categoryRef = transaction['transaction/category'];
    return {
      id: categoryRef?.['db/id'] ?? null,
      name: categoryRef?.['category/name'] ?? 'Uncategorized',
    };
  };

  // A transfer marker for the category cell, inline beside the category. A matched
  // transfer is a quiet pine ⇄ glyph (settled — the partner account and amount live
  // in the tooltip); an unmatched transfer-typed row is an ochre "Match" to-do button
  // — the one state that wants a click. Either opens the transfer modal (to view or
  // unmatch, or to find a counterpart). The shared ⇄ glyph reads both as one idea.
  const renderTransferStatus = (transaction: Transaction) => {
    const pair = transaction['transaction/transfer-pair'];
    if (pair) {
      const partnerName = pair['transaction/account']?.['account/external-name'] ?? 'another account';
      return (
        <button
          type="button"
          className="transfer-status transfer-status-matched"
          title={`Matched transfer with ${partnerName} (${formatAmount(pair['transaction/amount'])})`}
          aria-label={`Matched transfer with ${partnerName} — view or unmatch`}
          onClick={() => onOpenTransfer?.(transaction)}
        >
          <span className="transfer-status-glyph" aria-hidden="true">⇄</span>
        </button>
      );
    }
    const isTransferType = transaction['transaction/category']?.['category/type'] === 'transfer';
    return isTransferType ? (
      <button
        type="button"
        className="transfer-status transfer-status-unmatched"
        title="Transfer with no matched counterpart — click to match"
        onClick={() => onOpenTransfer?.(transaction)}
      >
        <span className="transfer-status-glyph" aria-hidden="true">⇄</span>
        Match
      </button>
    ) : null;
  };

  // Actions for a row's "⌄" menu. Transfer matching lives on the status pill now,
  // so only split/edit-split remains here.
  const rowActions = (transaction: Transaction): RowAction[] => {
    const actions: RowAction[] = [];
    const isSplit = (transaction['transaction/splits']?.length ?? 0) > 0;
    // A $0 transaction can't be divided into non-zero parts, so don't offer it.
    if (onSplit && transaction['transaction/amount'] !== 0) {
      actions.push({
        label: isSplit ? 'Edit split' : 'Split transaction',
        onSelect: () => onSplit(transaction),
      });
    }
    return actions;
  };

  const handleCategoryChange = (transactionId: number, categoryId: number | null) => {
    // Report the change up; the owner overlays it optimistically and debounces the write
    // (categoryOverlay / useWriteBehind), so the cell updates instantly without a reload.
    // The nav layer (commit/cancel at the call site) handles leaving edit mode.
    onEditCategory?.(transactionId, categoryId);
  };

  // One cell of a split part's (child) row. The description carries the branch marker
  // plus the part's own (editable) description; amount and category are filled too. The
  // rest is blank because the parent row already carries the date/account/payee context.
  const renderSplitChildCell = (columnId: string, split: Split, tx: Transaction, drift: boolean) => {
    const childKey: RowKey = { txId: tx['db/id'], splitId: split['db/id'] };
    switch (columnId) {
      case 'description': {
        if (!onEditSplitDescription) return <SplitIcon drift={drift} />;
        const memo = split['split/memo'] ?? '';
        if (gridNav.cellStatus(childKey, 'description').editing) {
          // Persist only a real change, storing the trimmed value so a spaces-only entry
          // clears the memo rather than sticking as a blank-looking description.
          const commit = (t: string) => {
            const trimmed = t.trim();
            if (trimmed !== memo) onEditSplitDescription(tx['db/id'], split['db/id'], trimmed);
          };
          return (
            <span className="split-description">
              <SplitIcon drift={drift} />
              <EditableDescriptionCell
                initialValue={memo}
                seedChar={gridNav.editSeed}
                onSaveAndNext={(t) => {
                  commit(t);
                  gridNav.commitAndMoveDown();
                }}
                onSave={(t) => {
                  commit(t);
                  gridNav.commitClose();
                }}
                onCancel={gridNav.cancelEdit}
              />
            </span>
          );
        }
        return (
          <span className="split-description">
            <SplitIcon drift={drift} />
            <button
              type="button"
              className="description-button"
              // With a memo, it's the button's accessible name; when empty (the '—'
              // placeholder), label the add action instead of "dash".
              aria-label={memo ? undefined : 'Add description'}
              onClick={() => gridNav.activate(childKey, 'description', { edit: true })}
            >
              {memo || '—'}
            </button>
          </span>
        );
      }
      case 'amount': {
        const amount = split['split/amount'];
        return (
          <span className={`numeric ${amount >= 0 ? 'positive' : 'negative'}`}>
            {formatAmount(amount)}
          </span>
        );
      }
      case 'category':
        return (
          <div className="category-cell-content">
            <button
              type="button"
              className="category-button"
              onClick={() => {
                gridNav.activate(childKey, 'category');
                onSplit?.(tx);
              }}
              title="Edit split"
            >
              {split['split/category']?.['category/name'] ?? 'Uncategorized'}
            </button>
          </div>
        );
      case 'reviewed': {
        const checked = split['split/reviewed'] === true;
        return (
          <input
            type="checkbox"
            className="reviewed-checkbox"
            // Roving focus lives on the <td>; the checkbox stays out of the tab
            // order so Space (handled by the grid) doesn't also toggle it natively.
            tabIndex={-1}
            checked={checked}
            onChange={(e) => onToggleSplitReviewed?.(tx['db/id'], split['db/id'], e.target.checked)}
            aria-label={checked ? 'Mark split as not reviewed' : 'Mark split as reviewed'}
          />
        );
      }
      default:
        return null;
    }
  };

  const columns = [
    columnHelper.accessor('transaction/posted-date', {
      id: 'date',
      header: 'Date',
      ...columnDefSizing('date', autoSizing['date']),
      cell: (info) => <span className="numeric">{formatDate(info.getValue())}</span>,
    }),
    columnHelper.accessor(row => row['transaction/account']?.['account/external-name'], {
      id: 'account',
      header: 'Account',
      ...columnDefSizing('account', autoSizing['account']),
      cell: (info) => info.getValue() || '—',
    }),
    columnHelper.accessor(row => row['transaction/account']?.['account/institution']?.['institution/name'], {
      id: 'institution',
      header: 'Institution',
      ...columnDefSizing('institution', autoSizing['institution']),
      cell: (info) => info.getValue() || '—',
    }),
    columnHelper.accessor('transaction/payee', {
      id: 'payee',
      header: 'Payee',
      ...columnDefSizing('payee', autoSizing['payee']),
      cell: (info) => info.getValue(),
    }),
    columnHelper.accessor(
      (row) => row['transaction/effective-description'] ?? row['transaction/description'] ?? '',
      {
        id: 'description',
        header: 'Description',
        ...columnDefSizing('description', autoSizing['description']),
        cell: (info) => {
          const transaction = info.row.original;
          // `text` is the displayed (effective) value, straight from the accessor — the
          // single source for what the override-or-import resolves to.
          const text = info.getValue() as string;
          if (!onEditDescription) return text || '—';
          const txKey: RowKey = { txId: transaction['db/id'], splitId: null };
          if (gridNav.cellStatus(txKey, 'description').editing) {
            // Persist only a real change. Comparing against `text` (the displayed,
            // effective value) makes opening/closing a cell — or retyping the same value,
            // possibly with surrounding whitespace — a no-op. Comparing against the
            // existing override too keeps clearing a row that has no override a no-op,
            // rather than firing an empty-override PUT the server would just retract.
            const currentOverride = transaction['transaction/user-description'] ?? '';
            const commit = (t: string) => {
              const trimmed = t.trim();
              if (trimmed !== text && trimmed !== currentOverride) {
                onEditDescription(transaction['db/id'], trimmed);
              }
            };
            return (
              <EditableDescriptionCell
                initialValue={text}
                seedChar={gridNav.editSeed}
                onSaveAndNext={(t) => {
                  commit(t);
                  gridNav.commitAndMoveDown();
                }}
                onSave={(t) => {
                  commit(t);
                  gridNav.commitClose();
                }}
                onCancel={gridNav.cancelEdit}
              />
            );
          }
          return (
            <button
              type="button"
              className="description-button"
              // With text, the description itself is the button's accessible name; when
              // empty (the '—' placeholder), label the add action instead of "dash".
              aria-label={text ? undefined : 'Add description'}
              onClick={() => gridNav.activate(txKey, 'description', { edit: true })}
            >
              {text || '—'}
            </button>
          );
        },
      }
    ),
    columnHelper.accessor('transaction/amount', {
      id: 'amount',
      header: 'Amount',
      ...columnDefSizing('amount', autoSizing['amount']),
      cell: (info) => {
        const amount = info.getValue();
        return (
          <span className={`numeric ${amount > 0 ? 'positive' : 'negative'}`}>
            {formatAmount(amount)}
          </span>
        );
      },
    }),
    columnHelper.display({
      id: 'category',
      header: 'Category',
      ...columnDefSizing('category', autoSizing['category']),
      cell: (info) => {
        const transaction = info.row.original;
        const txKey: RowKey = { txId: transaction['db/id'], splitId: null };
        const isEditing = gridNav.cellStatus(txKey, 'category').editing;

        const category = categoryOf(transaction);

        if (isEditing) {
          return (
            <CategoryDropdown
              categories={categories}
              selectedCategoryId={category.id}
              portalMenu
              initialFilter={gridNav.editSeed ?? undefined}
              onSelect={(categoryId) => {
                handleCategoryChange(transaction['db/id'], categoryId);
                gridNav.commitClose();
              }}
              onSelectAndNext={(categoryId) => {
                handleCategoryChange(transaction['db/id'], categoryId);
                gridNav.commitAndMoveDown();
              }}
              onClose={gridNav.cancelEdit}
            />
          );
        }

        // A stale (lingering) row was just moved out of the active filter; mark where it
        // went and keep it editable so the user can keep working before it clears.
        const stale = staleTransactionIds?.has(transaction['db/id']) ?? false;
        return (
          <div className="category-cell-row">
            <button
              className="category-button"
              // Open on click only — opening on focus (the old behavior) meant tabbing
              // or programmatically focusing the cell instantly swapped in the dropdown,
              // so Enter could never open it and could clobber an existing category.
              onClick={() => gridNav.activate(txKey, 'category', { edit: true })}
              title={
                stale
                  ? `Moved to ${category.name} — no longer matches the filter; clears on refresh`
                  : undefined
              }
            >
              {stale && (
                <span className="category-moved-mark" aria-hidden="true">
                  →{' '}
                </span>
              )}
              {category.name}
            </button>
            {renderTransferStatus(transaction)}
          </div>
        );
      },
    }),
    columnHelper.display({
      id: 'reviewed',
      header: 'Reviewed',
      ...columnDefSizing('reviewed'),
      cell: (info) => {
        const transaction = info.row.original;
        const checked = transaction['transaction/reviewed'] === true;
        return (
          <input
            type="checkbox"
            className="reviewed-checkbox"
            // Roving focus lives on the <td>; the checkbox stays out of the tab order
            // so Space (handled by the grid) doesn't also toggle it natively.
            tabIndex={-1}
            checked={checked}
            onChange={(e) => onToggleReviewed?.(transaction['db/id'], e.target.checked)}
            aria-label={checked ? 'Mark as not reviewed' : 'Mark as reviewed'}
          />
        );
      },
    }),
    columnHelper.display({
      id: 'actions',
      header: '',
      ...columnDefSizing('actions'),
      cell: (info) => (
        <RowActionsMenu actions={rowActions(info.row.original)} label="Transaction actions" />
      ),
    }),
  ];

  const cellClassName = (columnId: string): string | undefined => {
    if (columnId === 'amount') return 'amount-cell';
    if (columnId === 'description') return 'description-cell';
    if (columnId === 'category') return 'category-cell';
    if (columnId === 'reviewed') return 'reviewed-cell';
    if (columnId === 'actions') return 'actions-cell';
    return undefined;
  };

  const table = useReactTable({
    data: transactions,
    columns,
    state: {
      sorting,
      columnVisibility,
      columnSizing,
    },
    onSortingChange,
    onColumnVisibilityChange,
    onColumnSizingChange,
    enableColumnResizing: true,
    columnResizeMode: 'onChange',
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
  });

  // A resize drag ends with a click that would otherwise toggle the header sort.
  // Latch a flag while a column is resizing and clear it one tick after the drag
  // ends, so the trailing click (which fires synchronously on mouseup) is swallowed
  // but later header clicks still sort.
  const justResizedRef = useRef(false);
  const isResizing = !!table.getState().columnSizingInfo.isResizingColumn;
  useEffect(() => {
    if (isResizing) {
      justResizedRef.current = true;
      return;
    }
    if (justResizedRef.current) {
      const timer = setTimeout(() => {
        justResizedRef.current = false;
      }, 0);
      return () => clearTimeout(timer);
    }
  }, [isResizing]);

  // Content-fit auto-sizing (and the double-click auto-fit) live in a hook so all DOM
  // measurement stays out of the component. Re-measure whenever the visible rows
  // change — data, columns, sort, or page — so the page on screen always fits (e.g. a
  // large amount on a later page widens the amount column when you reach it) and the
  // protected columns never clip.
  const tableRef = useRef<HTMLTableElement>(null);
  const { recompute, autoFitColumn } = useAutoColumnSizing(table, tableRef, setAutoSizing);
  useLayoutEffect(() => {
    recompute();
  }, [recompute, transactions, columnVisibility, sorting, page, pageSize]);

  // Get sorted rows, then apply pagination if specified
  const sortedRows = table.getRowModel().rows;
  const displayRows = pageSize !== undefined
    ? sortedRows.slice(page * pageSize, (page + 1) * pageSize)
    : sortedRows;

  // Publish the navigable grid for this render: the displayed rows expanded into
  // navigable rows (split parents/children included) and the visible editable
  // columns. The hook reads this through a ref, so the keydown handler and focus
  // effect always reason over exactly what's on screen.
  const navigableCols = navigableColumns(table.getVisibleLeafColumns().map((c) => c.id));
  const gridModel = buildGridModel(
    navigableCols,
    displayRows.map((row) => ({
      txId: row.original['db/id'],
      splitIds: sortedPartsByTx.get(row.original['db/id'])?.map((s) => s['db/id']) ?? null,
    }))
  );
  gridNav.setModel(gridModel);
  const { navState } = gridNav;

  // The table's single keydown handler. It resolves the keystroke to an intent
  // (pure), routes non-inline cells to their side effects (toggle a reviewed
  // checkbox, open the split modal), and otherwise dispatches movement / edit
  // transitions to the state machine. In edit mode it claims only Tab — every
  // other key is left to the open editor.
  const handleTableKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    // Escape with an active cell drops the selection so a following Tab can leave
    // the grid; in edit mode the editor handles Escape itself.
    if (navState.mode === 'navigation' && e.key === 'Escape') {
      if (navState.active) {
        e.preventDefault();
        gridNav.clearActive();
      }
      return;
    }
    const intent = resolveIntent(e, navState.mode);
    if (!intent) return; // not ours — leave it to the editor / browser
    e.preventDefault();

    if (!navState.active) {
      // First keystroke after focusing the table: land on the first cell.
      gridNav.dispatchIntent('grid-start');
      return;
    }

    const row = gridModel.rows[navState.active.row];
    const col = navState.active.col;

    if (intent === 'toggle-reviewed') {
      // Space toggles a reviewed cell; elsewhere it's swallowed (no page scroll).
      if (col === 'reviewed') toggleReviewedAt(row.key);
      return;
    }
    if (intent === 'edit' || intent === 'type-to-edit') {
      if (col === 'reviewed') {
        toggleReviewedAt(row.key);
        return;
      }
      if (col === 'category' && row.kind === 'split-child') {
        openSplitFor(row.key.txId);
        return;
      }
      gridNav.setEditSeed(intent === 'type-to-edit' ? e.key : null);
      gridNav.dispatchIntent(intent);
      return;
    }
    // Movement. In edit mode this is Tab: blur the open editor first so it commits
    // its value through its own onBlur (React doesn't reliably fire onBlur when the
    // editor unmounts), then move on.
    if (navState.mode === 'edit') {
      (document.activeElement as HTMLElement | null)?.blur();
    }
    gridNav.setEditSeed(null);
    gridNav.dispatchIntent(intent);
  };

  // Per-cell roving-tabindex/focus wiring, merged with the cell's own class. A cell
  // is navigable when its column is editable and the row offers it (a split parent
  // offers only its description); other cells keep just their base class.
  const tdNavProps = (colId: string, key: RowKey, navigable: boolean) => {
    const base = cellClassName(colId);
    if (!navigable || !(EDITABLE_COLUMN_IDS as readonly string[]).includes(colId)) {
      return { className: base };
    }
    const col = colId as ColId;
    const status = gridNav.cellStatus(key, col);
    return {
      ref: (el: HTMLTableCellElement | null) => gridNav.registerCell(cellKey(key, col), el),
      tabIndex: status.active ? 0 : -1,
      className: [base, status.active ? 'grid-cell-active' : ''].filter(Boolean).join(' ') || undefined,
    };
  };

  return (
    <div
      className="transactions-table-scroll"
      // A single tab stop: the scroll container catches the initial Tab-in (no
      // active cell yet); once a cell is active, the roving tabindex on that <td>
      // becomes the stop and the container steps out of the tab order.
      tabIndex={navState.active ? -1 : 0}
      onKeyDown={handleTableKeyDown}>
      {/* width:100% (from .table) lets the trailing auto-width spacer column absorb
          any slack so the table fills the card; min-width keeps the real columns at
          their exact pixel widths (no proportional reflow), scrolling when they
          exceed the container. */}
      <table ref={tableRef} className="table table-resizable" style={{ minWidth: table.getTotalSize() }}>
        <colgroup>
          {table.getVisibleLeafColumns().map((column) => (
            <col key={column.id} style={{ width: column.getSize() }} />
          ))}
          <col className="table-spacer-col" />
        </colgroup>
        <thead>
          {table.getHeaderGroups().map((headerGroup) => (
            <tr key={headerGroup.id}>
              {headerGroup.headers.map((header) => {
                const canSort = header.column.getCanSort();
                const filterField =
                  onToggleFilterValue && HEADER_FILTER_LABELS[header.column.id]
                    ? header.column.id
                    : null;
                const thClass = [cellClassName(header.column.id), canSort ? 'th-sortable' : 'th-static']
                  .filter(Boolean)
                  .join(' ');
                return (
                  <th
                    key={header.id}
                    className={thClass}
                    onClick={(e) => {
                      // Don't sort on the click that ends a resize drag.
                      if (justResizedRef.current) return;
                      if (canSort) header.column.getToggleSortingHandler()?.(e);
                    }}
                  >
                    {header.isPlaceholder
                      ? null
                      : flexRender(
                          header.column.columnDef.header,
                          header.getContext()
                        )}
                    {header.column.getIsSorted() === 'asc' && ' ↑'}
                    {header.column.getIsSorted() === 'desc' && ' ↓'}
                    {filterField && (
                      <HeaderFilterControl
                        label={HEADER_FILTER_LABELS[filterField]}
                        options={filterOptionsByField?.[filterField] ?? []}
                        selectedValues={filterState?.[filterField] ?? []}
                        onToggle={(value) => onToggleFilterValue?.(filterField, value)}
                        onClear={() => onClearFilterField?.(filterField)}
                      />
                    )}
                    {header.column.getCanResize() && (
                      <div
                        className={`col-resize-handle ${header.column.getIsResizing() ? 'is-resizing' : ''}`}
                        onMouseDown={header.getResizeHandler()}
                        onTouchStart={header.getResizeHandler()}
                        onClick={(e) => e.stopPropagation()}
                        onDoubleClick={() => autoFitColumn(header.column)}
                        aria-hidden="true"
                      />
                    )}
                  </th>
                );
              })}
              <th className="table-spacer-cell" aria-hidden="true" />
            </tr>
          ))}
        </thead>
        <tbody>
          {displayRows.map((row) => {
            const tx = row.original;
            const parts = sortedPartsByTx.get(tx['db/id']) ?? null;

            // Unsplit transaction: one normal row. A stale row (edited out of the active
            // filter but kept in view) renders de-emphasized until the filter resets.
            if (!parts) {
              const stale = staleTransactionIds?.has(tx['db/id']) ?? false;
              return (
                <tr key={row.id} className={stale ? 'is-stale' : undefined}>
                  {row.getVisibleCells().map((cell) => (
                    <td
                      key={cell.id}
                      {...tdNavProps(cell.column.id, { txId: tx['db/id'], splitId: null }, true)}
                    >
                      {flexRender(cell.column.columnDef.cell, cell.getContext())}
                    </td>
                  ))}
                  <td className="table-spacer-cell" />
                </tr>
              );
            }

            // Split transaction: a context "parent" row (date/account/payee, but no
            // amount to avoid showing the total twice) followed by one muted line per
            // part carrying just the amount + category. Drift is the server's
            // bigdec-exact verdict, not a lossy client re-derivation.
            const drift = tx['transaction/splits-balanced'] === false;
            const lastIdx = parts.length - 1;
            return (
              <Fragment key={row.id}>
                <tr className="is-split-parent">
                  {row.getVisibleCells().map((cell) => {
                    const id = cell.column.id;
                    // The parent carries context only: blank the amount (so the total
                    // isn't shown twice), the category (each part owns its own
                    // category; the parent has none) and the reviewed checkbox (each
                    // part is reviewed on its own row). The row menu stays available.
                    const blank = id === 'amount' || id === 'category' || id === 'reviewed';
                    return (
                      <td
                        key={cell.id}
                        {...tdNavProps(id, { txId: tx['db/id'], splitId: null }, id === 'description')}
                      >
                        {blank ? null : flexRender(cell.column.columnDef.cell, cell.getContext())}
                      </td>
                    );
                  })}
                  <td className="table-spacer-cell" />
                </tr>
                {parts.map((split, i) => (
                  <tr
                    key={`${row.id}-part-${split['db/id'] ?? i}`}
                    className={`split-child-row ${i === lastIdx ? 'is-last' : ''}`}
                  >
                    {row.getVisibleCells().map((cell) => (
                      <td
                        key={cell.id}
                        {...tdNavProps(cell.column.id, { txId: tx['db/id'], splitId: split['db/id'] }, true)}
                      >
                        {renderSplitChildCell(cell.column.id, split, tx, drift)}
                      </td>
                    ))}
                    <td className="table-spacer-cell" />
                  </tr>
                ))}
              </Fragment>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
