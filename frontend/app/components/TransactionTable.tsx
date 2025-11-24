import { useState } from 'react';
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
import { serializeSortingState } from '../lib/sortingState';
import { CategoryDropdown } from './CategoryDropdown';

interface TransactionTableProps {
  transactions: Transaction[];
  categories: Category[];
  onCategoryChange: (transactionId: number, categoryId: number | null) => void;
  page?: number;
  pageSize?: number;
  sorting: SortingState;
  onSortingChange: (sorting: SortingState) => void;
}

export function TransactionTable({
  transactions,
  categories,
  onCategoryChange,
  page = 0,
  pageSize,
  sorting,
  onSortingChange,
}: TransactionTableProps) {
  const [editingTransactionId, setEditingTransactionId] = useState<number | null>(null);

  const columnHelper = createColumnHelper<Transaction>();

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
        const categoryRef = transaction['transaction/category'];

        if (isEditing) {
          return (
            <CategoryDropdown
              categories={categories}
              selectedCategoryId={categoryRef?.['db/id'] || null}
              onSelect={(categoryId) => {
                onCategoryChange(transaction['db/id'], categoryId);
                setEditingTransactionId(null);
              }}
              onClose={() => setEditingTransactionId(null)}
            />
          );
        }

        return (
          <button
            className="category-button"
            onClick={() => setEditingTransactionId(transaction['db/id'])}
          >
            {categoryRef?.['category/name'] || 'Uncategorized'}
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
