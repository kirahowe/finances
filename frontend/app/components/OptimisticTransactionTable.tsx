import { useState, useMemo, useRef, useEffect, useLayoutEffect, Fragment } from 'react';
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
import { CategoryDropdown } from './CategoryDropdown';
import { EditableDescriptionCell } from './EditableDescriptionCell';
import { RowActionsMenu, type RowAction } from './RowActionsMenu';
import { HeaderFilterControl } from './HeaderFilterControl';
import type { FilterState, FilterValue } from '../lib/filterState';
import type { FilterOption } from '../lib/filterOptions';
import '../styles/components/split-rows.css';
import '../styles/components/transfer-modal.css';
import '../styles/components/transactions-table.css';

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
  const [editingTransactionId, setEditingTransactionId] = useState<number | null>(null);
  const [editingDescriptionId, setEditingDescriptionId] = useState<number | null>(null);

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

  // Helper to find next transaction ID in the displayed rows
  const findNextTransactionId = (currentTxId: number, displayedTransactions: Transaction[]): number | null => {
    const currentIndex = displayedTransactions.findIndex(tx => tx['db/id'] === currentTxId);
    if (currentIndex === -1 || currentIndex === displayedTransactions.length - 1) {
      return null; // Not found or last transaction
    }
    return displayedTransactions[currentIndex + 1]['db/id'];
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

  // A transfer status pill for the category cell: "Matched" on a matched transfer,
  // or "Unmatched" on a transfer-categorized row with no counterpart. Clicking
  // either opens the transfer modal (to view/unmatch or to match a counterpart).
  const renderTransferStatus = (transaction: Transaction) => {
    const pair = transaction['transaction/transfer-pair'];
    if (pair) {
      const partnerName = pair['transaction/account']?.['account/external-name'] ?? 'another account';
      return (
        <button
          type="button"
          className="transfer-status transfer-status-matched"
          title={`Matched transfer with ${partnerName} (${formatAmount(pair['transaction/amount'])})`}
          onClick={() => onOpenTransfer?.(transaction)}
        >
          Matched
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
        Unmatched
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
    onEditCategory?.(transactionId, categoryId);
    setEditingTransactionId(null);
  };

  // One cell of a split part's (child) row. The description carries the branch marker
  // plus the part's own (editable) description; amount and category are filled too. The
  // rest is blank because the parent row already carries the date/account/payee context.
  const renderSplitChildCell = (columnId: string, split: Split, tx: Transaction, drift: boolean) => {
    switch (columnId) {
      case 'description': {
        if (!onEditSplitDescription) return <SplitIcon drift={drift} />;
        const memo = split['split/memo'] ?? '';
        if (editingDescriptionId === split['db/id']) {
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
                onSaveAndNext={(t) => {
                  commit(t);
                  setEditingDescriptionId(null);
                }}
                onSave={(t) => {
                  commit(t);
                  setEditingDescriptionId(null);
                }}
                onCancel={() => setEditingDescriptionId(null)}
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
              onClick={() => setEditingDescriptionId(split['db/id'])}
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
              onClick={() => onSplit?.(tx)}
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
          if (editingDescriptionId === transaction['db/id']) {
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
                onSaveAndNext={(t) => {
                  commit(t);
                  setEditingDescriptionId(
                    findNextTransactionId(transaction['db/id'], displayedTransactions)
                  );
                }}
                onSave={(t) => {
                  commit(t);
                  setEditingDescriptionId(null);
                }}
                onCancel={() => setEditingDescriptionId(null)}
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
              onClick={() => setEditingDescriptionId(transaction['db/id'])}
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
        const isEditing = editingTransactionId === transaction['db/id'];

        const category = categoryOf(transaction);

        if (isEditing) {
          return (
            <CategoryDropdown
              categories={categories}
              selectedCategoryId={category.id}
              portalMenu
              onSelect={(categoryId) => {
                handleCategoryChange(transaction['db/id'], categoryId);
              }}
              onSelectAndNext={(categoryId) => {
                handleCategoryChange(transaction['db/id'], categoryId);
                const nextTxId = findNextTransactionId(transaction['db/id'], displayedTransactions);
                setEditingTransactionId(nextTxId);
              }}
              onClose={() => setEditingTransactionId(null)}
            />
          );
        }

        // A stale (lingering) row was just moved out of the active filter; mark where it
        // went and keep it editable so the user can keep working before it clears.
        const stale = staleTransactionIds?.has(transaction['db/id']) ?? false;
        return (
          <div className="category-cell-stack">
            <button
              className="category-button"
              onClick={() => setEditingTransactionId(transaction['db/id'])}
              onFocus={() => setEditingTransactionId(transaction['db/id'])}
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

  // Get the actual Transaction objects from displayRows for navigation
  const displayedTransactions = displayRows.map(row => row.original);

  return (
    <div className="transactions-table-scroll">
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
                    <td key={cell.id} className={cellClassName(cell.column.id)}>
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
                      <td key={cell.id} className={cellClassName(id)}>
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
                      <td key={cell.id} className={cellClassName(cell.column.id)}>
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
