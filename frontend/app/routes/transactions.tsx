import { useState } from "react";
import type { Route } from "./+types/transactions";
import { api, type Transaction, type Category } from "../lib/api";
import { useFetcher } from "react-router";
import "../styles/components/pagination.css";
import "../styles/components/category-button.css";

export function meta({}: Route.MetaArgs) {
  return [{ title: "Transactions - Finance Aggregator" }];
}

export async function loader(): Promise<{ transactions: Transaction[]; categories: Category[] }> {
  const [transactions, categories] = await Promise.all([
    api.getTransactions(),
    api.getCategories(),
  ]);
  return { transactions, categories };
}

export async function action({ request }: Route.ActionArgs) {
  const formData = await request.formData();
  const transactionId = parseInt(formData.get("transactionId") as string);
  const categoryId = formData.get("categoryId");

  await api.updateTransactionCategory(
    transactionId,
    categoryId ? parseInt(categoryId as string) : null
  );

  return { success: true };
}

function formatAmount(amount: number): string {
  const formatter = new Intl.NumberFormat('en-CA', {
    style: 'currency',
    currency: 'CAD',
  });
  return formatter.format(amount);
}

function formatDate(dateString: string): string {
  const date = new Date(dateString);
  return date.toLocaleDateString();
}

export default function Transactions({ loaderData }: Route.ComponentProps) {
  const { transactions, categories } = loaderData;
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);

  const startIdx = page * pageSize;
  const endIdx = startIdx + pageSize;
  const paginatedTransactions = transactions.slice(startIdx, endIdx);
  const totalPages = Math.ceil(transactions.length / pageSize);

  return (
    <div className="container">
      <h1>Transactions</h1>

      <div className="card">
        <table className="table">
          <thead>
            <tr>
              <th>Date</th>
              <th>Payee</th>
              <th>Description</th>
              <th>Amount</th>
              <th>Category</th>
            </tr>
          </thead>
          <tbody>
            {paginatedTransactions.map((transaction) => (
              <TransactionRow
                key={transaction["db/id"]}
                transaction={transaction}
                categories={categories}
              />
            ))}
          </tbody>
        </table>

        <div className="pagination">
          <button
            className="button button-secondary"
            onClick={() => setPage(0)}
            disabled={page === 0}
          >
            First
          </button>
          <button
            className="button button-secondary"
            onClick={() => setPage(page - 1)}
            disabled={page === 0}
          >
            Previous
          </button>
          <span className="pagination-info">
            Page {page + 1} of {totalPages}
          </span>
          <button
            className="button button-secondary"
            onClick={() => setPage(page + 1)}
            disabled={page >= totalPages - 1}
          >
            Next
          </button>
          <button
            className="button button-secondary"
            onClick={() => setPage(totalPages - 1)}
            disabled={page >= totalPages - 1}
          >
            Last
          </button>

          <select
            className="form-select pagination-size"
            value={pageSize}
            onChange={(e) => {
              setPageSize(parseInt(e.target.value));
              setPage(0);
            }}
          >
            <option value="10">10 per page</option>
            <option value="20">20 per page</option>
            <option value="50">50 per page</option>
            <option value="100">100 per page</option>
          </select>
        </div>
      </div>

      <div className="nav-links">
        <a href="/" className="button button-secondary">
          Back to Home
        </a>
      </div>
    </div>
  );
}

function TransactionRow({
  transaction,
  categories,
}: {
  transaction: Transaction;
  categories: Category[];
}) {
  const [isEditing, setIsEditing] = useState(false);
  const fetcher = useFetcher();

  const currentCategoryId = transaction["transaction/category"]?.["db/id"] || null;
  const amount = transaction["transaction/amount"];
  const isPositive = amount > 0;

  const handleCategoryChange = (categoryId: string) => {
    fetcher.submit(
      {
        transactionId: transaction["db/id"].toString(),
        categoryId: categoryId || "",
      },
      { method: "post" }
    );
    setIsEditing(false);
  };

  return (
    <tr>
      <td>{formatDate(transaction["transaction/posted-date"])}</td>
      <td>{transaction["transaction/payee"]}</td>
      <td>{transaction["transaction/description"] || "—"}</td>
      <td className={isPositive ? "positive" : "negative"}>
        {formatAmount(amount)}
      </td>
      <td>
        {isEditing ? (
          <select
            className="form-select"
            defaultValue={currentCategoryId?.toString() || ""}
            onChange={(e) => handleCategoryChange(e.target.value)}
            onBlur={() => setIsEditing(false)}
            autoFocus
          >
            <option value="">Uncategorized</option>
            {categories.map((cat) => (
              <option key={cat["db/id"]} value={cat["db/id"].toString()}>
                {cat["category/name"]}
              </option>
            ))}
          </select>
        ) : (
          <button
            className="category-button"
            onClick={() => setIsEditing(true)}
          >
            {transaction["transaction/category"]?.["category/name"] || "Uncategorized"}
          </button>
        )}
      </td>
    </tr>
  );
}
