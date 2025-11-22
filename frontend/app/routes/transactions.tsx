import { useState } from "react";
import type { Route } from "./+types/transactions";
import { api, type Transaction, type Category } from "../lib/api";
import { OptimisticTransactionTable } from "../components/OptimisticTransactionTable";
import { LoadingIndicator } from "../components/LoadingIndicator";
import { ErrorDisplay } from "../components/ErrorDisplay";
import { useNavigation, useRevalidator } from "react-router";
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

export default function Transactions({ loaderData }: Route.ComponentProps) {
  const { transactions, categories } = loaderData;
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [error, setError] = useState<string | null>(null);
  const navigation = useNavigation();
  const revalidator = useRevalidator();

  const isLoading = navigation.state === 'loading';

  const totalPages = Math.ceil(transactions.length / pageSize);

  const handleCategoryChange = async (
    transactionId: number,
    categoryId: number | null,
    rollback: () => void
  ) => {
    try {
      await api.updateTransactionCategory(transactionId, categoryId);
      // Success - no rollback needed, clear any previous errors
      setError(null);
    } catch (err) {
      // Failure - rollback the optimistic update
      rollback();
      const errorMessage = err instanceof Error ? err.message : 'Failed to update transaction category';
      setError(errorMessage);
    }
  };

  return (
    <div className="container">
      <h1>Transactions</h1>

      <ErrorDisplay error={error} onDismiss={() => setError(null)} />
      <LoadingIndicator isLoading={isLoading} message="Loading transactions..." />

      {!isLoading && (
        <div className="card">
          <div className="card-header">
            <h2>All Transactions</h2>
            <button className="button" onClick={() => revalidator.revalidate()}>
              Refresh
            </button>
          </div>

          <OptimisticTransactionTable
            transactions={transactions}
            categories={categories}
            onCategoryChange={handleCategoryChange}
            page={page}
            pageSize={pageSize}
          />

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
      )}

      <div className="nav-links">
        <a href="/" className="button button-secondary">
          Back to Home
        </a>
      </div>
    </div>
  );
}
