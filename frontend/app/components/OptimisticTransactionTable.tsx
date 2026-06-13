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
import { useFetcher, useRevalidator } from 'react-router';
import { api, type Transaction, type Category, type Split } from '../lib/api';
import { formatAmount, formatDate } from '../lib/format';
import { sortSplits } from '../lib/splitMath';
import { columnDefSizing } from '../lib/transactionColumns';
import { useAutoColumnSizing } from '../lib/useAutoColumnSizing';
import { CategoryDropdown } from './CategoryDropdown';
import { RowActionsMenu, type RowAction } from './RowActionsMenu';
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
}

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
}: OptimisticTransactionTableProps) {
  const [editingTransactionId, setEditingTransactionId] = useState<number | null>(null);
  const fetcher = useFetcher();
  const revalidator = useRevalidator();

  // Optimistic reviewed overrides, keyed `tx:<id>` / `split:<id>`. Each toggle fires
  // its own independent request (so rapidly checking many rows never makes the
  // requests abort or clobber each other — a single shared fetcher would), shows the
  // new value immediately, then revalidates. Fresh loader data clears the overrides
  // since the server value is then authoritative.
  const [optimisticReviewed, setOptimisticReviewed] = useState<Record<string, boolean>>({});
  useEffect(() => {
    setOptimisticReviewed({});
  }, [transactions]);

  const reviewedChecked = (key: string, serverValue: boolean): boolean =>
    key in optimisticReviewed ? optimisticReviewed[key] : serverValue;

  const toggleReviewed = (key: string, next: boolean, persist: () => Promise<unknown>) => {
    setOptimisticReviewed((prev) => ({ ...prev, [key]: next }));
    persist().finally(() => revalidator.revalidate());
  };
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

  // Helper function to get optimistic category for a transaction
  const getOptimisticCategory = (transaction: Transaction): { id: number | null; name: string } => {
    // Check if this transaction is being updated via fetcher
    const isUpdating =
      fetcher.state !== 'idle' &&
      fetcher.formData?.get('transactionId') === transaction['db/id'].toString();

    if (isUpdating) {
      // Show optimistic value from formData
      const categoryId = fetcher.formData?.get('categoryId');
      if (categoryId) {
        const cat = categories.find(c => c['db/id'] === parseInt(categoryId as string));
        return { id: parseInt(categoryId as string), name: cat?.['category/name'] || 'Uncategorized' };
      }
      return { id: null, name: 'Uncategorized' };
    }

    // Use values directly from transaction
    const categoryRef = transaction['transaction/category'];
    return {
      id: categoryRef?.['db/id'] || null,
      name: categoryRef?.['category/name'] || 'Uncategorized'
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
    // Use fetcher to submit the change (Remix-native optimistic UI)
    const formData = new FormData();
    formData.set('intent', 'update-transaction-category');
    formData.set('transactionId', transactionId.toString());
    if (categoryId !== null) {
      formData.set('categoryId', categoryId.toString());
    }

    fetcher.submit(formData, { method: 'post' });
    setEditingTransactionId(null);
  };

  // One cell of a split part's (child) row. Only the amount and category are
  // filled (with a branch marker + an Edit action); the rest is blank because the
  // parent row already carries the date/account/payee context.
  const renderSplitChildCell = (columnId: string, split: Split, tx: Transaction, drift: boolean) => {
    switch (columnId) {
      case 'description':
        return <SplitIcon drift={drift} />;
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
        const key = `split:${split['db/id']}`;
        const checked = reviewedChecked(key, split['split/reviewed'] === true);
        return (
          <input
            type="checkbox"
            className="reviewed-checkbox"
            checked={checked}
            onChange={(e) => {
              const next = e.target.checked;
              toggleReviewed(key, next, () => api.setSplitReviewed(tx['db/id'], split['db/id'], next));
            }}
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
    columnHelper.accessor('transaction/description', {
      id: 'description',
      header: 'Description',
      ...columnDefSizing('description', autoSizing['description']),
      cell: (info) => info.getValue() || '—',
    }),
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

        const optimisticCategory = getOptimisticCategory(transaction);

        if (isEditing) {
          return (
            <CategoryDropdown
              categories={categories}
              selectedCategoryId={optimisticCategory.id}
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

        return (
          <div className="category-cell-stack">
            <button
              className="category-button"
              onClick={() => setEditingTransactionId(transaction['db/id'])}
              onFocus={() => setEditingTransactionId(transaction['db/id'])}
            >
              {optimisticCategory.name}
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
        const key = `tx:${transaction['db/id']}`;
        const checked = reviewedChecked(key, transaction['transaction/reviewed'] === true);
        return (
          <input
            type="checkbox"
            className="reviewed-checkbox"
            checked={checked}
            onChange={(e) => {
              const next = e.target.checked;
              toggleReviewed(key, next, () => api.setTransactionReviewed(transaction['db/id'], next));
            }}
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

            // Unsplit transaction: one normal row.
            if (!parts) {
              return (
                <tr key={row.id}>
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
