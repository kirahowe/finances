import { useState } from 'react';
import {
  useReactTable,
  getCoreRowModel,
  getSortedRowModel,
  flexRender,
  createColumnHelper,
  type SortingState,
} from '@tanstack/react-table';
import { useFetcher } from 'react-router';
import type { Transaction, Category } from '../lib/api';
import { formatAmount, formatDate } from '../lib/format';
import { CategoryDropdown } from './CategoryDropdown';

interface OptimisticTransactionTableProps {
  transactions: Transaction[];
  categories: Category[];
  page?: number;
  pageSize?: number;
  sorting: SortingState;
  onSortingChange: (sorting: SortingState) => void;
}

export function OptimisticTransactionTable({
  transactions,
  categories,
  page = 0,
  pageSize,
  sorting,
  onSortingChange,
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
        const isUpdating =
          fetcher.state !== 'idle' &&
          fetcher.formData?.get('transactionId') === transaction['db/id'].toString();

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
          <button
            className="category-button"
            onClick={() => setEditingTransactionId(transaction['db/id'])}
            disabled={isUpdating}
          >
            {optimisticCategory.name}
            {isUpdating && ' (saving...)'}
          </button>
        );
      },
    }),
  ];

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
        {displayRows.map((row) => (
          <tr key={row.id}>
            {row.getVisibleCells().map((cell) => (
              <td key={cell.id}>
                {flexRender(cell.column.columnDef.cell, cell.getContext())}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
