import { useState, useEffect } from 'react';
import {
  useReactTable,
  getCoreRowModel,
  getSortedRowModel,
  flexRender,
  createColumnHelper,
  type SortingState,
} from '@tanstack/react-table';
import type { Transaction, Category } from '../lib/api';
import { formatAmount, formatDate } from '../lib/format';

interface OptimisticTransactionTableProps {
  transactions: Transaction[];
  categories: Category[];
  onCategoryChange: (
    transactionId: number,
    categoryId: number | null,
    rollback: () => void
  ) => void;
  page?: number;
  pageSize?: number;
}

export function OptimisticTransactionTable({
  transactions,
  categories,
  onCategoryChange,
  page = 0,
  pageSize,
}: OptimisticTransactionTableProps) {
  const [sorting, setSorting] = useState<SortingState>([]);
  const [editingTransactionId, setEditingTransactionId] = useState<number | null>(null);

  // Local state for optimistic updates
  const [optimisticTransactions, setOptimisticTransactions] = useState<Transaction[]>(transactions);

  // Update local state when props change (e.g., after successful save)
  useEffect(() => {
    setOptimisticTransactions(transactions);
  }, [transactions]);

  const columnHelper = createColumnHelper<Transaction>();

  const handleCategoryChange = (transactionId: number, categoryId: number | null) => {
    // Find the new category
    const newCategory = categoryId
      ? categories.find((cat) => cat['db/id'] === categoryId)
      : null;

    // Store the original transaction for rollback
    const originalTransaction = optimisticTransactions.find(
      (tx) => tx['db/id'] === transactionId
    );

    if (!originalTransaction) return;

    // Optimistic update: Update UI immediately
    setOptimisticTransactions((prev) =>
      prev.map((tx) =>
        tx['db/id'] === transactionId
          ? { ...tx, 'transaction/category': newCategory || null }
          : tx
      )
    );

    // Provide rollback function
    const rollback = () => {
      setOptimisticTransactions((prev) =>
        prev.map((tx) =>
          tx['db/id'] === transactionId
            ? { ...tx, 'transaction/category': originalTransaction['transaction/category'] }
            : tx
        )
      );
    };

    // Call parent with rollback function
    onCategoryChange(transactionId, categoryId, rollback);
    setEditingTransactionId(null);
  };

  const columns = [
    columnHelper.accessor('transaction/posted-date', {
      id: 'date',
      header: 'Date',
      cell: (info) => <span className="numeric">{formatDate(info.getValue())}</span>,
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
        const currentCategory = transaction['transaction/category'];

        if (isEditing) {
          return (
            <select
              className="form-select"
              defaultValue={currentCategory?.['db/id']?.toString() || ''}
              onChange={(e) => {
                const value = e.target.value;
                handleCategoryChange(
                  transaction['db/id'],
                  value ? parseInt(value) : null
                );
              }}
              onBlur={() => setEditingTransactionId(null)}
              autoFocus
            >
              <option value="">Uncategorized</option>
              {categories.map((cat) => (
                <option key={cat['db/id']} value={cat['db/id'].toString()}>
                  {cat['category/name']}
                </option>
              ))}
            </select>
          );
        }

        return (
          <button
            className="category-button"
            onClick={() => setEditingTransactionId(transaction['db/id'])}
          >
            {currentCategory?.['category/name'] || 'Uncategorized'}
          </button>
        );
      },
    }),
  ];

  const table = useReactTable({
    data: optimisticTransactions,
    columns,
    state: {
      sorting,
    },
    onSortingChange: setSorting,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
  });

  // Get sorted rows, then apply pagination if specified
  const sortedRows = table.getRowModel().rows;
  const displayRows = pageSize !== undefined
    ? sortedRows.slice(page * pageSize, (page + 1) * pageSize)
    : sortedRows;

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
