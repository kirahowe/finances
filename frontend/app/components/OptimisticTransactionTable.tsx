import { useState, Fragment } from 'react';
import {
  useReactTable,
  getCoreRowModel,
  getSortedRowModel,
  flexRender,
  createColumnHelper,
  type SortingState,
  type OnChangeFn,
} from '@tanstack/react-table';
import { useFetcher } from 'react-router';
import type { Transaction, Category, Split } from '../lib/api';
import { formatAmount, formatDate } from '../lib/format';
import { CategoryDropdown } from './CategoryDropdown';
import '../styles/components/split-rows.css';
import '../styles/components/transfer-modal.css';

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
  onSplit?: (transaction: Transaction) => void;
  onMatch?: (transaction: Transaction) => void;
  onUnmatch?: (transaction: Transaction) => void;
}

export function OptimisticTransactionTable({
  transactions,
  categories,
  page = 0,
  pageSize,
  sorting,
  onSortingChange,
  onSplit,
  onMatch,
  onUnmatch,
}: OptimisticTransactionTableProps) {
  const [editingTransactionId, setEditingTransactionId] = useState<number | null>(null);
  const fetcher = useFetcher();

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

  // Transfer affordance for a row's category cell: a persistent badge + unmatch on
  // a matched transfer, otherwise a quiet "Match" action (and an "unmatched" marker
  // for transfer-categorized rows with no counterpart).
  const renderTransferAffordance = (transaction: Transaction) => {
    const pair = transaction['transaction/transfer-pair'];
    if (pair) {
      const partnerName = pair['transaction/account']?.['account/external-name'] ?? 'another account';
      return (
        <button
          type="button"
          className="transfer-badge"
          title={`Transfer with ${partnerName} (${formatAmount(pair['transaction/amount'])}) — click to unmatch`}
          onClick={() => onUnmatch?.(transaction)}
        >
          <span aria-hidden="true">⇄</span>
          <span className="sr-only">Unmatch transfer</span>
        </button>
      );
    }
    const isTransferType = transaction['transaction/category']?.['category/type'] === 'transfer';
    return (
      <>
        {isTransferType && (
          <span className="transfer-unmatched" title="Transfer with no matched counterpart">
            unmatched
          </span>
        )}
        {onMatch && (
          <button
            type="button"
            className="transfer-action"
            onClick={() => onMatch(transaction)}
            title="Match as a transfer"
          >
            Match
          </button>
        )}
      </>
    );
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
            {onSplit && (
              <button
                type="button"
                className="split-action"
                onClick={() => onSplit(tx)}
                title="Edit split"
              >
                Edit
              </button>
            )}
          </div>
        );
      default:
        return null;
    }
  };

  const columns = [
    columnHelper.accessor('transaction/posted-date', {
      id: 'date',
      header: 'Date',
      cell: (info) => <span className="numeric">{formatDate(info.getValue())}</span>,
    }),
    columnHelper.accessor(row => row['transaction/account']?.['account/external-name'], {
      id: 'account',
      header: 'Account',
      cell: (info) => info.getValue() || '—',
    }),
    columnHelper.accessor(row => row['transaction/account']?.['account/institution']?.['institution/name'], {
      id: 'institution',
      header: 'Institution',
      cell: (info) => info.getValue() || '—',
    }),
    columnHelper.accessor('transaction/payee', {
      id: 'payee',
      header: 'Payee',
      cell: (info) => info.getValue(),
    }),
    columnHelper.accessor('transaction/description', {
      id: 'description',
      header: 'Description',
      cell: (info) => info.getValue() || '—',
    }),
    columnHelper.accessor('transaction/amount', {
      id: 'amount',
      header: 'Amount',
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
      cell: (info) => {
        const transaction = info.row.original;
        const isEditing = editingTransactionId === transaction['db/id'];

        const optimisticCategory = getOptimisticCategory(transaction);

        if (isEditing) {
          return (
            <CategoryDropdown
              categories={categories}
              selectedCategoryId={optimisticCategory.id}
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
          <div className="category-cell-content">
            <button
              className="category-button"
              onClick={() => setEditingTransactionId(transaction['db/id'])}
              onFocus={() => setEditingTransactionId(transaction['db/id'])}
            >
              {optimisticCategory.name}
            </button>
            {renderTransferAffordance(transaction)}
            {onSplit && (
              <button
                type="button"
                className="split-action"
                onClick={() => onSplit(transaction)}
                title="Split this transaction"
              >
                Split
              </button>
            )}
          </div>
        );
      },
    }),
  ];

  const cellClassName = (columnId: string): string | undefined => {
    if (columnId === 'amount') return 'amount-cell';
    if (columnId === 'category') return 'category-cell';
    return undefined;
  };

  const table = useReactTable({
    data: transactions,
    columns,
    state: {
      sorting,
    },
    onSortingChange,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
  });

  // Get sorted rows, then apply pagination if specified
  const sortedRows = table.getRowModel().rows;
  const displayRows = pageSize !== undefined
    ? sortedRows.slice(page * pageSize, (page + 1) * pageSize)
    : sortedRows;

  // Get the actual Transaction objects from displayRows for navigation
  const displayedTransactions = displayRows.map(row => row.original);

  return (
    <table className="table">
      <thead>
        {table.getHeaderGroups().map((headerGroup) => (
          <tr key={headerGroup.id}>
            {headerGroup.headers.map((header) => (
              <th
                key={header.id}
                className={cellClassName(header.column.id)}
                onClick={header.column.getToggleSortingHandler()}
                style={{
                  cursor: header.column.getCanSort() ? 'pointer' : 'default',
                  userSelect: 'none',
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
              </th>
            ))}
          </tr>
        ))}
      </thead>
      <tbody>
        {displayRows.map((row) => {
          const tx = row.original;
          const txSplits = tx['transaction/splits'];
          const parts =
            txSplits && txSplits.length > 0
              ? [...txSplits].sort((a, b) => (a['split/order'] ?? 0) - (b['split/order'] ?? 0))
              : null;

          // Unsplit transaction: one normal row.
          if (!parts) {
            return (
              <tr key={row.id}>
                {row.getVisibleCells().map((cell) => (
                  <td key={cell.id} className={cellClassName(cell.column.id)}>
                    {flexRender(cell.column.columnDef.cell, cell.getContext())}
                  </td>
                ))}
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
                  const blank = id === 'amount' || id === 'category';
                  return (
                    <td key={cell.id} className={cellClassName(id)}>
                      {blank ? null : flexRender(cell.column.columnDef.cell, cell.getContext())}
                    </td>
                  );
                })}
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
                </tr>
              ))}
            </Fragment>
          );
        })}
      </tbody>
    </table>
  );
}
