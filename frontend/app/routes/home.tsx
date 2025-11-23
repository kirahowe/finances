import { useState, useRef, useEffect } from "react";
import type { Route } from "./+types/home";
import { api, type Stats, type Category, type Account, type Transaction } from "../lib/api";
import { useSearchParams, useFetcher, useNavigation } from "react-router";
import { OptimisticTransactionTable } from "../components/OptimisticTransactionTable";
import { CategoryTable } from "../components/CategoryTable";
import { LoadingIndicator } from "../components/LoadingIndicator";
import { ErrorDisplay } from "../components/ErrorDisplay";
import { generateCategoryIdent } from "../lib/identGenerator";
import type { CategoryDraft } from "../lib/categoryDraft";
import { calculateSortOrderUpdates, optimizeSortOrderUpdates } from "../lib/categoryReorder";
import { debounce } from "../lib/debounce";
import { parseSortingState, serializeSortingState } from "../lib/sortingState";
import type { SortingState } from "@tanstack/react-table";
import "../styles/pages/dashboard.css";
import "../styles/components/pagination.css";
import "../styles/components/category-button.css";

export function meta({}: Route.MetaArgs) {
  return [
    { title: "Finance Aggregator" },
    { name: "description", content: "Personal finance aggregation tool" },
  ];
}

export async function loader({ request }: Route.LoaderArgs) {
  const url = new URL(request.url);
  const view = url.searchParams.get('view');

  const stats = await api.getStats();

  let categories: Category[] = [];
  let accounts: Account[] = [];
  let transactions: Transaction[] = [];

  if (view === 'categories') {
    categories = await api.getCategories();
  } else if (view === 'accounts') {
    accounts = await api.getAccounts();
  } else if (view === 'transactions') {
    [transactions, categories] = await Promise.all([
      api.getTransactions(),
      api.getCategories(),
    ]);
  }

  // Return plain object - React Router v7 handles serialization
  // Note: For HTTP caching headers, consider using Response object when needed
  return { stats, categories, accounts, transactions, view };
}

export async function action({ request }: Route.ActionArgs) {
  const formData = await request.formData();
  const intent = formData.get("intent");

  if (intent === "refresh-stats") {
    return { success: true };
  } else if (intent === "create-category") {
    const name = formData.get("name") as string;
    const type = formData.get("type") as "expense" | "income";
    const ident = formData.get("ident") as string | undefined;
    await api.createCategory({ name, type, ident: ident || undefined });
  } else if (intent === "update-category") {
    const id = parseInt(formData.get("id") as string);
    const name = formData.get("name") as string;
    const type = formData.get("type") as "expense" | "income";
    const ident = formData.get("ident") as string | undefined;
    await api.updateCategory(id, { name, type, ident: ident || undefined });
  } else if (intent === "delete-category") {
    const id = parseInt(formData.get("id") as string);
    await api.deleteCategory(id);
  } else if (intent === "update-transaction-category") {
    const transactionId = parseInt(formData.get("transactionId") as string);
    const categoryId = formData.get("categoryId");
    await api.updateTransactionCategory(
      transactionId,
      categoryId ? parseInt(categoryId as string) : null
    );
  }

  return { success: true };
}

export default function Home({ loaderData }: Route.ComponentProps) {
  const { stats, categories, accounts, transactions, view } = loaderData;
  const [searchParams, setSearchParams] = useSearchParams();
  const [error, setError] = useState<string | null>(null);
  const fetcher = useFetcher();
  const navigation = useNavigation();

  const isLoading = navigation.state === 'loading';

  const handleRefresh = () => {
    fetcher.submit({ intent: "refresh-stats" }, { method: "post" });
  };

  const handleViewChange = (newView: string) => {
    setSearchParams({ view: newView });
  };

  return (
    <div className="container">
      <h1>Finance Aggregator</h1>

      <ErrorDisplay error={error} onDismiss={() => setError(null)} />

      <div className="stats-grid">
        <div className="stat-card">
          <div className="stat-value">{stats.institutions}</div>
          <div className="stat-label">Institutions</div>
        </div>
        <div className="stat-card">
          <div className="stat-value">{stats.accounts}</div>
          <div className="stat-label">Accounts</div>
        </div>
        <div className="stat-card">
          <div className="stat-value">{stats.transactions}</div>
          <div className="stat-label">Transactions</div>
        </div>
      </div>

      <div className="card">
        <div className="card-header">
          <h2>Navigate</h2>
          <button className="button" onClick={handleRefresh}>
            Refresh Stats
          </button>
        </div>
        <div className="nav-links">
          <button onClick={() => handleViewChange('categories')} className="button">
            Categories
          </button>
          <button onClick={() => handleViewChange('accounts')} className="button">
            Accounts
          </button>
          <button onClick={() => handleViewChange('transactions')} className="button">
            Transactions
          </button>
        </div>
      </div>

      <LoadingIndicator isLoading={isLoading} message="Loading data..." />

      {!isLoading && view === 'categories' && <CategoriesSection categories={categories} />}
      {!isLoading && view === 'accounts' && <AccountsSection accounts={accounts} />}
      {!isLoading && view === 'transactions' && <TransactionsSection transactions={transactions} categories={categories} />}
    </div>
  );
}

function CategoriesSection({ categories }: { categories: Category[] }) {
  const [editingCategory, setEditingCategory] = useState<Category | null>(null);
  const [newCategoryIds, setNewCategoryIds] = useState<number[]>([]);
  const [optimisticCategories, setOptimisticCategories] = useState<Category[]>([]);
  const [localCategories, setLocalCategories] = useState<Category[]>([]);
  const nextTempIdRef = useRef(-1);
  const originalCategoriesRef = useRef(categories);
  const fetcher = useFetcher();

  // Update original categories ref when server data changes
  useEffect(() => {
    originalCategoriesRef.current = categories;
    setLocalCategories(categories);
  }, [categories]);

  // Merge server categories with optimistic categories
  const allCategories = [...localCategories, ...optimisticCategories];

  // Clean up optimistic categories when they appear in the server data
  useEffect(() => {
    if (optimisticCategories.length > 0) {
      setOptimisticCategories((prev) =>
        prev.filter((opt) =>
          !categories.some((cat) => cat['category/name'] === opt['category/name'])
        )
      );
    }
  }, [categories, optimisticCategories.length]);

  // Track IDs of categories that were optimistically added
  useEffect(() => {
    const optimisticIds = optimisticCategories.map((cat) => cat['db/id']);
    if (optimisticIds.length > 0) {
      setNewCategoryIds((prev) => {
        const serverNewIds = categories
          .filter((cat) =>
            optimisticCategories.some((opt) => opt['category/name'] === cat['category/name'])
          )
          .map((cat) => cat['db/id']);

        const combined = [...new Set([...prev, ...optimisticIds, ...serverNewIds])];
        return combined;
      });
    }
  }, [categories, optimisticCategories]);

  const handleEdit = (category: Category) => {
    setEditingCategory(category);
  };

  const handleDelete = (category: Category) => {
    if (confirm(`Delete category "${category["category/name"]}"?`)) {
      fetcher.submit(
        { intent: "delete-category", id: category["db/id"].toString() },
        { method: "post" }
      );
    }
  };

  const handleCreate = (draft: CategoryDraft) => {
    // Create optimistic category
    const tempId = nextTempIdRef.current--;
    const optimisticCategory: Category = {
      'db/id': tempId,
      'category/name': draft.name,
      'category/type': draft.type,
    };

    setOptimisticCategories((prev) => [...prev, optimisticCategory]);

    const ident = generateCategoryIdent(draft.name);
    fetcher.submit(
      {
        intent: "create-category",
        name: draft.name,
        type: draft.type,
        ident,
      },
      { method: "post" }
    );
  };

  const handleCloseForm = () => {
    setEditingCategory(null);
  };

  // Debounced batch update for reordering
  const debouncedBatchUpdate = useRef(
    debounce(async (updates: Array<{ id: number; sortOrder: number }>) => {
      try {
        await api.batchUpdateCategorySortOrders(updates);
      } catch (error) {
        console.error('Failed to update category order:', error);
        // Revert to original order on error
        setLocalCategories(originalCategoriesRef.current);
      }
    }, 500)
  ).current;

  const handleReorder = (reordered: Category[]) => {
    // Optimistically update local state
    setLocalCategories(reordered);

    // Calculate and batch the updates
    const allUpdates = calculateSortOrderUpdates(reordered);
    const optimizedUpdates = optimizeSortOrderUpdates(allUpdates, originalCategoriesRef.current);

    if (optimizedUpdates.length > 0) {
      debouncedBatchUpdate(optimizedUpdates);
    }
  };

  return (
    <div className="card">
      <div className="card-header">
        <h2>Categories</h2>
      </div>

      <CategoryTable
        categories={allCategories}
        newCategoryIds={newCategoryIds}
        onEdit={handleEdit}
        onDelete={handleDelete}
        onCreate={handleCreate}
        onReorder={handleReorder}
      />

      {editingCategory && (
        <CategoryForm
          category={editingCategory}
          onClose={handleCloseForm}
        />
      )}
    </div>
  );
}

function CategoryForm({
  category,
  onClose,
}: {
  category: Category | null;
  onClose: () => void;
}) {
  const fetcher = useFetcher();
  const isEditing = category !== null;
  const [name, setName] = useState(category?.["category/name"] || "");

  const generatedIdent = generateCategoryIdent(name);

  const handleSubmit = () => {
    // Close modal after submission (works with fetcher)
    onClose();
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-content" onClick={(e) => e.stopPropagation()}>
        <h2>{isEditing ? "Edit Category" : "Add Category"}</h2>
        <fetcher.Form method="post" onSubmit={handleSubmit}>
          <input
            type="hidden"
            name="intent"
            value={isEditing ? "update-category" : "create-category"}
          />
          <input
            type="hidden"
            name="ident"
            value={generatedIdent}
          />
          {isEditing && (
            <input
              type="hidden"
              name="id"
              value={category["db/id"].toString()}
            />
          )}

          <div className="form-group">
            <label className="form-label" htmlFor="name">
              Name
            </label>
            <input
              className="form-input"
              type="text"
              id="name"
              name="name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
            />
          </div>

          <div className="form-group">
            <label className="form-label" htmlFor="type">
              Type
            </label>
            <select
              className="form-select"
              id="type"
              name="type"
              defaultValue={category?.["category/type"] || "expense"}
              required
            >
              <option value="expense">Expense</option>
              <option value="income">Income</option>
            </select>
          </div>

          <div className="form-actions">
            <button type="button" className="button button-secondary" onClick={onClose}>
              Cancel
            </button>
            <button type="submit" className="button">
              {isEditing ? "Update" : "Create"}
            </button>
          </div>
        </fetcher.Form>
      </div>
    </div>
  );
}

function AccountsSection({ accounts }: { accounts: Account[] }) {
  return (
    <div className="card">
      <h2>Accounts</h2>
      <table className="table">
        <thead>
          <tr>
            <th>Name</th>
            <th>Type</th>
            <th>Currency</th>
            <th>External ID</th>
          </tr>
        </thead>
        <tbody>
          {accounts.map((account) => (
            <tr key={account["db/id"]}>
              <td>{account["account/external-name"]}</td>
              <td>{account["account/type"] || "—"}</td>
              <td>{account["account/currency"]}</td>
              <td>{account["account/external-id"] || "—"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function TransactionsSection({
  transactions,
  categories
}: {
  transactions: Transaction[];
  categories: Category[];
}) {
  const [searchParams, setSearchParams] = useSearchParams();
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [error, setError] = useState<string | null>(null);

  // Initialize sorting from URL, then manage it locally
  const [sorting, setSorting] = useState<SortingState>(() =>
    parseSortingState(searchParams.get('sort'))
  );

  // Sync URL when sorting changes (without triggering navigation/loading)
  useEffect(() => {
    const serialized = serializeSortingState(sorting);
    const newParams = new URLSearchParams(searchParams);

    if (serialized) {
      newParams.set('sort', serialized);
    } else {
      newParams.delete('sort');
    }

    // Use native history API to avoid triggering React Router navigation
    const newUrl = `${window.location.pathname}?${newParams.toString()}`;
    window.history.replaceState(null, '', newUrl);
  }, [sorting, searchParams]);

  // Update local state when sorting changes
  const handleSortingChange = (updaterOrValue: SortingState | ((old: SortingState) => SortingState)) => {
    setSorting(updaterOrValue);
  };

  const totalPages = Math.ceil(transactions.length / pageSize);

  return (
    <div className="card">
      <h2>Transactions</h2>
      <ErrorDisplay error={error} onDismiss={() => setError(null)} />
      <OptimisticTransactionTable
        transactions={transactions}
        categories={categories}
        page={page}
        pageSize={pageSize}
        sorting={sorting}
        onSortingChange={handleSortingChange}
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
  );
}
