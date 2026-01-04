import { useState, useRef, useEffect, useMemo } from "react";
import type { Route } from "./+types/home";
import { api, type Stats, type Category, type Account, type Transaction } from "../lib/api";
import { useSearchParams, useFetcher, useNavigation } from "react-router";
import { OptimisticTransactionTable } from "../components/OptimisticTransactionTable";
import { CategoryTable } from "../components/CategoryTable";
import { LoadingIndicator } from "../components/LoadingIndicator";
import { ErrorDisplay } from "../components/ErrorDisplay";
import { Pagination } from "../components/Pagination";
import { FilterBar, type FilterConfig } from "../components/FilterBar";
import { generateCategoryIdent } from "../lib/identGenerator";
import type { CategoryDraft } from "../lib/categoryDraft";
import { CATEGORY_TYPE_OPTIONS, type CategoryType } from "../lib/categoryTypes";
import { calculateSortOrderUpdates, optimizeSortOrderUpdates } from "../lib/categoryReorder";
import { debounce } from "../lib/debounce";
import { parseSortingState, serializeSortingState } from "../lib/sortingState";
import type { SortingState } from "@tanstack/react-table";
import type { PageSize } from "../lib/pagination";
import { usePlaidLink } from "../hooks/usePlaidLink";
import { parseFilters, serializeFilters, toggleFilterValue, clearFilterField, clearAllFilters, type FilterState, type FilterValue } from "../lib/filterState";
import { extractFilterOptions, applyFilters } from "../lib/filterOptions";
import "../styles/pages/dashboard.css";
import "../styles/components/pagination.css";
import "../styles/components/category-button.css";
import "../styles/components/filter.css";

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
  } else if (intent === "link-plaid-account") {
    // Handle Plaid account linking with exchange and sync
    const publicToken = formData.get("publicToken") as string;
    if (!publicToken) {
      return { success: false, error: "Missing public token" };
    }

    try {
      // 1. Exchange public token for access token (stores credential)
      const exchangeResult = await api.exchangePlaidToken(publicToken);

      // 2. Sync accounts from Plaid
      const accountsResult = await api.syncPlaidAccounts();

      // 3. Sync transactions (last 6 months)
      const transactionsResult = await api.syncPlaidTransactions({ months: 6 });

      const message = `Linked ${exchangeResult.institution_name || 'account'}! ` +
        `Synced ${accountsResult.success.accounts} account(s) and ` +
        `${transactionsResult.success.transactions} transaction(s).`;

      return { success: true, message };
    } catch (err) {
      const errorMsg = err instanceof Error ? err.message : "Failed to link account";
      return { success: false, error: errorMsg };
    }
  } else if (intent === "create-category") {
    const name = formData.get("name") as string;
    const type = formData.get("type") as CategoryType;
    const ident = formData.get("ident") as string | undefined;
    await api.createCategory({ name, type, ident: ident || undefined });
  } else if (intent === "update-category") {
    const id = parseInt(formData.get("id") as string);
    const name = formData.get("name") as string;
    const type = formData.get("type") as CategoryType;
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
          <a href="/plaid-test" className="button">
            Plaid Test
          </a>
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
              {CATEGORY_TYPE_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
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
  const { openPlaidLink, isReady, isLinking, status, error, clearError } = usePlaidLink({
    onSuccess: () => {
      console.log('Account linked successfully - data will refresh automatically');
    },
  });

  return (
    <div className="card">
      <div className="card-header">
        <h2>Accounts</h2>
        <button
          className="button"
          onClick={openPlaidLink}
          disabled={!isReady || isLinking}
        >
          {isLinking ? "Linking..." : "Manage Accounts"}
        </button>
      </div>

      {status && <div className="status-banner">{status}</div>}
      {error && (
        <div className="error-banner">
          {error}
          <button
            onClick={clearError}
            style={{ marginLeft: '1rem', background: 'none', border: 'none', cursor: 'pointer' }}
          >
            ×
          </button>
        </div>
      )}

      {accounts.length === 0 ? (
        <p style={{ padding: '1rem', color: '#666' }}>
          No accounts linked yet. Click "Manage Accounts" to connect your bank.
        </p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>Institution</th>
              <th>Name</th>
              <th>Type</th>
              <th>Mask</th>
              <th>Currency</th>
            </tr>
          </thead>
          <tbody>
            {accounts.map((account) => (
              <tr key={account["db/id"]}>
                <td>{account["account/institution"]?.["institution/name"] || "—"}</td>
                <td>{account["account/external-name"]}</td>
                <td>
                  {account["account/plaid-type"]
                    ? `${account["account/plaid-type"]}${account["account/plaid-subtype"] ? ` / ${account["account/plaid-subtype"]}` : ''}`
                    : account["account/type"] || "—"}
                </td>
                <td>{account["account/mask"] ? `****${account["account/mask"]}` : "—"}</td>
                <td>{account["account/currency"]}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
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
  const [pageSize, setPageSize] = useState<PageSize>(25);
  const [error, setError] = useState<string | null>(null);
  const [syncStatus, setSyncStatus] = useState<string>("");
  const [syncError, setSyncError] = useState<string | null>(null);

  // Initialize sorting from URL
  const [sorting, setSorting] = useState<SortingState>(() =>
    parseSortingState(searchParams.get('sort'))
  );

  // Initialize filters from URL - single source of truth
  const [filters, setFilters] = useState<FilterState>(() =>
    parseFilters(searchParams.get('filters'))
  );

  // Extract filter options from all transactions
  const filterConfigs = useMemo<FilterConfig[]>(() => {
    const accountOptions = extractFilterOptions(
      transactions,
      tx => tx['transaction/account']?.['db/id'],
      tx => tx['transaction/account']?.['account/external-name'] || 'Unknown'
    );

    const categoryOptions = extractFilterOptions(
      transactions,
      tx => tx['transaction/category']?.['db/id'],
      tx => tx['transaction/category']?.['category/name'] || 'Uncategorized'
    );

    return [
      { field: 'account', label: 'Account', options: accountOptions },
      { field: 'category', label: 'Category', options: categoryOptions },
    ];
  }, [transactions]);

  // Apply filters to transactions
  const filteredTransactions = useMemo(() => {
    return applyFilters(
      transactions,
      filters,
      {
        account: (tx: Transaction) => tx['transaction/account']?.['db/id'],
        category: (tx: Transaction) => tx['transaction/category']?.['db/id'],
      }
    );
  }, [transactions, filters]);

  // Sync URL when sorting or filters change
  useEffect(() => {
    const newParams = new URLSearchParams(searchParams);

    const serializedSort = serializeSortingState(sorting);
    if (serializedSort) {
      newParams.set('sort', serializedSort);
    } else {
      newParams.delete('sort');
    }

    const serializedFilters = serializeFilters(filters);
    if (serializedFilters) {
      newParams.set('filters', serializedFilters);
    } else {
      newParams.delete('filters');
    }

    // Use native history API to avoid triggering React Router navigation
    const newUrl = `${window.location.pathname}?${newParams.toString()}`;
    window.history.replaceState(null, '', newUrl);
  }, [sorting, filters, searchParams]);

  const handleSortingChange = (updaterOrValue: SortingState | ((old: SortingState) => SortingState)) => {
    setSorting(updaterOrValue);
  };

  const handleToggleFilterValue = (field: string, value: FilterValue) => {
    setFilters(prev => toggleFilterValue(prev, field, value));
  };

  const handleClearFilterField = (field: string) => {
    setFilters(prev => clearFilterField(prev, field));
  };

  const handleClearAllFilters = () => {
    setFilters(clearAllFilters());
  };

  const handleSyncTransactions = async () => {
    setSyncStatus("Syncing transactions from Plaid...");
    setSyncError(null);

    try {
      const result = await api.syncPlaidTransactions({ months: 6 });
      setSyncStatus(
        `Successfully synced ${result.success.transactions} transaction(s)!`
      );

      // Reload transactions data after sync
      setTimeout(() => {
        window.location.reload();
      }, 1500);
    } catch (err) {
      const errorMsg = err instanceof Error ? err.message : "Failed to sync transactions";
      setSyncError(errorMsg);
      setSyncStatus("");
    }
  };

  return (
    <div className="card">
      <div className="card-header">
        <h2>Transactions</h2>
        <button
          className="button"
          onClick={handleSyncTransactions}
          disabled={!!syncStatus}
        >
          Sync Transactions
        </button>
      </div>

      {syncStatus && <div className="status-banner">{syncStatus}</div>}
      {syncError && <div className="error-banner">{syncError}</div>}
      <ErrorDisplay error={error} onDismiss={() => setError(null)} />

      <FilterBar
        filters={filterConfigs}
        filterState={filters}
        onToggleValue={handleToggleFilterValue}
        onClearField={handleClearFilterField}
        onClearAll={handleClearAllFilters}
      />

      <OptimisticTransactionTable
        transactions={filteredTransactions}
        categories={categories}
        page={page}
        pageSize={pageSize}
        sorting={sorting}
        onSortingChange={handleSortingChange}
      />

      <Pagination
        currentPage={page}
        pageSize={pageSize}
        totalItems={filteredTransactions.length}
        onPageChange={setPage}
        onPageSizeChange={setPageSize}
      />
    </div>
  );
}
