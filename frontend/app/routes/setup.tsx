import { useState, useRef, useEffect } from "react";
import type { Route } from "./+types/setup";
import { api, type Stats, type Category, type Account } from "../lib/api";
import { useFetcher, useRevalidator } from "react-router";
import { SiteHeader } from "../components/SiteHeader";
import { CategoryTable } from "../components/CategoryTable";
import { BulkCategoryModal } from "../components/BulkCategoryModal";
import { ManualAccountModal } from "../components/ManualAccountModal";
import { LunchflowConnectionModal } from "../components/LunchflowConnectionModal";
import { CsvImportWizard } from "../components/CsvImportWizard";
import { Modal } from "../components/Modal";
import { generateCategoryIdent } from "../lib/identGenerator";
import type { CategoryDraft } from "../lib/categoryDraft";
import { CATEGORY_TYPE_OPTIONS, type CategoryType } from "../lib/categoryTypes";
import { calculateSortOrderUpdates, optimizeSortOrderUpdates } from "../lib/categoryReorder";
import { debounce } from "../lib/debounce";
import { usePlaidLink } from "../hooks/usePlaidLink";
import "../styles/pages/dashboard.css";
import "../styles/components/category-button.css";
import "../styles/components/category-table.css";
import "../styles/components/provider-connection.css";
import "../styles/components/csv-import.css";

const NUMBER_FORMAT = new Intl.NumberFormat("en-US");

export function meta({}: Route.MetaArgs) {
  return [
    { title: "Setup - Finance Aggregator" },
    { name: "description", content: "Manage accounts and categories" },
  ];
}

export async function loader({}: Route.LoaderArgs) {
  const [stats, categories, accounts] = await Promise.all([
    api.getStats(),
    api.getCategories(),
    api.getAccounts(),
  ]);
  return { stats, categories, accounts };
}

export async function action({ request }: Route.ActionArgs) {
  const formData = await request.formData();
  const intent = formData.get("intent");

  if (intent === "refresh-stats") {
    return { success: true };
  } else if (intent === "link-plaid-account") {
    const publicToken = formData.get("publicToken") as string;
    const accountIdsJson = formData.get("accountIds") as string;

    if (!publicToken) {
      return { success: false, error: "Missing public token" };
    }

    let accountIds: string[] | undefined;
    if (accountIdsJson) {
      try {
        accountIds = JSON.parse(accountIdsJson);
      } catch {
        console.warn("Failed to parse accountIds:", accountIdsJson);
      }
    }

    try {
      const exchangeResult = await api.exchangePlaidToken(publicToken, accountIds);
      await api.syncPlaidAccounts();
      return {
        success: true,
        itemId: exchangeResult.item_id,
        institutionName: exchangeResult.institution_name,
      };
    } catch (err) {
      const errorMsg = err instanceof Error ? err.message : "Failed to link account";
      return { success: false, error: errorMsg };
    }
  } else if (intent === "create-category") {
    const name = formData.get("name") as string;
    const type = formData.get("type") as CategoryType;
    const ident = formData.get("ident") as string | undefined;
    const parentId = formData.get("parentId");
    await api.createCategory({
      name,
      type,
      ident: ident || undefined,
      parentId: parentId ? parseInt(parentId as string) : undefined,
    });
  } else if (intent === "update-category") {
    const id = parseInt(formData.get("id") as string);
    const name = formData.get("name") as string;
    const type = formData.get("type") as CategoryType;
    const ident = formData.get("ident") as string | undefined;
    const parentIdRaw = formData.get("parentId") as string;
    const parentId = parentIdRaw === "" ? null : parseInt(parentIdRaw);
    await api.updateCategory(id, { name, type, ident: ident || undefined, parentId });
  } else if (intent === "delete-category") {
    const id = parseInt(formData.get("id") as string);
    await api.deleteCategory(id);
  } else if (intent === "create-manual-account") {
    const name = formData.get("name") as string;
    const institutionName = formData.get("institutionName") as string;
    const currency = formData.get("currency") as string;
    await api.createAccount({ name, institutionName, currency });
  }

  return { success: true };
}

export default function Setup({ loaderData }: Route.ComponentProps) {
  const { stats, categories, accounts } = loaderData;

  return (
    <div className="container">
      <SiteHeader stats={stats} />

      <div className="page-head">
        <span className="eyebrow">Configuration</span>
        <h2 className="page-title">Setup</h2>
        <p className="page-lede">
          Connect institutions, manage accounts, and curate the category system
          your transactions are sorted into.
        </p>
      </div>

      <div className="stats-grid">
        <div className="stat-card">
          <div className="stat-value">{NUMBER_FORMAT.format(stats.institutions)}</div>
          <div className="stat-label">Institutions</div>
        </div>
        <div className="stat-card">
          <div className="stat-value">{NUMBER_FORMAT.format(stats.accounts)}</div>
          <div className="stat-label">Accounts</div>
        </div>
        <div className="stat-card">
          <div className="stat-value">{NUMBER_FORMAT.format(stats.transactions)}</div>
          <div className="stat-label">Transactions</div>
        </div>
      </div>

      <AccountsSection accounts={accounts} />
      <CategoriesSection categories={categories} />
    </div>
  );
}

function AccountsSection({ accounts }: { accounts: Account[] }) {
  const [showManualAccountModal, setShowManualAccountModal] = useState(false);
  const [showLunchflowModal, setShowLunchflowModal] = useState(false);
  const [importAccountId, setImportAccountId] = useState<number | null>(null);
  const [syncingProvider, setSyncingProvider] = useState<string | null>(null);
  const [syncError, setSyncError] = useState<string | null>(null);
  const revalidator = useRevalidator();
  const { openPlaidLink, isReady, isLinking, status, error, clearError } = usePlaidLink({
    onSuccess: () => {
      console.log("Account linked successfully - data will refresh automatically");
    },
  });

  const handleImportSuccess = () => {
    revalidator.revalidate();
  };

  // Secrets-based providers (e.g. Lunchflow): the API key lives server-side, so
  // syncing just triggers the backend pull and refreshes once it completes.
  const handleSyncProvider = async (provider: string) => {
    setSyncError(null);
    setSyncingProvider(provider);
    try {
      await api.syncProvider(provider);
      revalidator.revalidate();
    } catch (err) {
      setSyncError(err instanceof Error ? err.message : `Failed to sync ${provider}`);
    } finally {
      setSyncingProvider(null);
    }
  };

  const importAccount = accounts.find((a) => a["db/id"] === importAccountId);

  return (
    <div className="card">
      <div className="section-head">
        <h2>
          Accounts <span className="section-count">{accounts.length}</span>
        </h2>
        <div className="button-group">
          <button
            className="button button-secondary"
            onClick={() => setShowManualAccountModal(true)}
          >
            Add Manual Account
          </button>
          <button className="button" onClick={openPlaidLink} disabled={!isReady || isLinking}>
            {isLinking ? "Linking..." : "Link Bank Account"}
          </button>
          <button
            className="button button-secondary"
            onClick={() => setShowLunchflowModal(true)}
          >
            Connect Lunchflow
          </button>
          <button
            className="button button-secondary"
            onClick={() => handleSyncProvider("lunchflow")}
            disabled={syncingProvider === "lunchflow"}
          >
            {syncingProvider === "lunchflow" ? "Syncing..." : "Sync Lunchflow"}
          </button>
        </div>
      </div>

      {status && <div className="status-banner">{status}</div>}
      {error && (
        <div className="error-banner">
          {error}
          <button onClick={clearError}>×</button>
        </div>
      )}
      {syncError && (
        <div className="error-banner">
          {syncError}
          <button onClick={() => setSyncError(null)}>×</button>
        </div>
      )}

      {accounts.length === 0 ? (
        <div className="empty-state">
          <div className="empty-state-title">No accounts yet</div>
          <p>
            Link a bank through Plaid, connect Lunchflow, or add a manual account
            to start importing transactions.
          </p>
        </div>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>Source</th>
              <th>Institution</th>
              <th>Name</th>
              <th>Type</th>
              <th>Mask</th>
              <th>Currency</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {accounts.map((account) => {
              const provider = account["account/provider"];
              const providerLabel = provider
                ? provider.charAt(0).toUpperCase() + provider.slice(1)
                : "Unknown";
              return (
                <tr key={account["db/id"]}>
                  <td>
                    <span className={`badge badge-${provider ?? "unknown"}`}>
                      {providerLabel}
                    </span>
                  </td>
                  <td>{account["account/institution"]?.["institution/name"] || "—"}</td>
                  <td>{account["account/external-name"]}</td>
                  <td>
                    {account["account/provider-type"]
                      ? `${account["account/provider-type"]}${account["account/provider-subtype"] ? ` / ${account["account/provider-subtype"]}` : ""}`
                      : account["account/type"] || "—"}
                  </td>
                  <td>
                    <span className="numeric">
                      {account["account/mask"] ? `••••${account["account/mask"]}` : "—"}
                    </span>
                  </td>
                  <td>{account["account/currency"]}</td>
                  <td>
                    {provider === "manual" && (
                      <button
                        className="button button-secondary button-small"
                        onClick={() => setImportAccountId(account["db/id"])}
                      >
                        Import CSV
                      </button>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}

      {showManualAccountModal && (
        <ManualAccountModal onClose={() => setShowManualAccountModal(false)} />
      )}

      {showLunchflowModal && (
        <LunchflowConnectionModal
          existingAccounts={accounts}
          onClose={() => setShowLunchflowModal(false)}
          onConnected={() => {
            setShowLunchflowModal(false);
            revalidator.revalidate();
          }}
        />
      )}

      {importAccountId && importAccount && (
        <CsvImportWizard
          accountId={importAccountId}
          accountName={importAccount["account/external-name"]}
          onClose={() => setImportAccountId(null)}
          onSuccess={handleImportSuccess}
        />
      )}
    </div>
  );
}

function CategoriesSection({ categories }: { categories: Category[] }) {
  const [editingCategory, setEditingCategory] = useState<Category | null>(null);
  const [showBulkModal, setShowBulkModal] = useState(false);
  const [newCategoryIds, setNewCategoryIds] = useState<number[]>([]);
  const [optimisticCategories, setOptimisticCategories] = useState<Category[]>([]);
  const [localCategories, setLocalCategories] = useState<Category[]>([]);
  const nextTempIdRef = useRef(-1);
  const originalCategoriesRef = useRef(categories);
  const fetcher = useFetcher();
  const revalidator = useRevalidator();

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
        prev.filter(
          (opt) => !categories.some((cat) => cat["category/name"] === opt["category/name"])
        )
      );
    }
  }, [categories, optimisticCategories.length]);

  // Track IDs of categories that were optimistically added
  useEffect(() => {
    const optimisticIds = optimisticCategories.map((cat) => cat["db/id"]);
    if (optimisticIds.length > 0) {
      setNewCategoryIds((prev) => {
        const serverNewIds = categories
          .filter((cat) =>
            optimisticCategories.some((opt) => opt["category/name"] === cat["category/name"])
          )
          .map((cat) => cat["db/id"]);

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
    const tempId = nextTempIdRef.current--;
    const optimisticCategory: Category = {
      "db/id": tempId,
      "category/name": draft.name,
      "category/type": draft.type,
      ...(draft.parentId !== null ? { "category/parent": { "db/id": draft.parentId } } : {}),
    };

    setOptimisticCategories((prev) => [...prev, optimisticCategory]);

    const ident = generateCategoryIdent(draft.name);
    const submission: Record<string, string> = {
      intent: "create-category",
      name: draft.name,
      type: draft.type,
      ident,
    };
    if (draft.parentId !== null) {
      submission.parentId = String(draft.parentId);
    }
    fetcher.submit(submission, { method: "post" });
  };

  const handleCloseForm = () => {
    setEditingCategory(null);
  };

  const handleBulkCreated = () => {
    setShowBulkModal(false);
    revalidator.revalidate();
  };

  // Debounced batch update for reordering
  const debouncedBatchUpdate = useRef(
    debounce(async (updates: Array<{ id: number; sortOrder: number }>) => {
      try {
        await api.batchUpdateCategorySortOrders(updates);
      } catch (error) {
        console.error("Failed to update category order:", error);
        setLocalCategories(originalCategoriesRef.current);
      }
    }, 500)
  ).current;

  const handleReorder = (reordered: Category[]) => {
    setLocalCategories(reordered);

    const allUpdates = calculateSortOrderUpdates(reordered);
    const optimizedUpdates = optimizeSortOrderUpdates(allUpdates, originalCategoriesRef.current);

    if (optimizedUpdates.length > 0) {
      debouncedBatchUpdate(optimizedUpdates);
    }
  };

  return (
    <div className="card">
      <div className="section-head">
        <h2>
          Categories <span className="section-count">{allCategories.length}</span>
        </h2>
      </div>

      <CategoryTable
        categories={allCategories}
        newCategoryIds={newCategoryIds}
        onEdit={handleEdit}
        onDelete={handleDelete}
        onCreate={handleCreate}
        onReorder={handleReorder}
        onBulkAdd={() => setShowBulkModal(true)}
      />

      {editingCategory && (
        <CategoryForm
          category={editingCategory}
          categories={allCategories}
          onClose={handleCloseForm}
        />
      )}

      {showBulkModal && (
        <BulkCategoryModal
          existingCategories={allCategories}
          onClose={() => setShowBulkModal(false)}
          onCreated={handleBulkCreated}
        />
      )}
    </div>
  );
}

function CategoryForm({
  category,
  categories,
  onClose,
}: {
  category: Category | null;
  categories: Category[];
  onClose: () => void;
}) {
  const fetcher = useFetcher();
  const isEditing = category !== null;
  const [name, setName] = useState(category?.["category/name"] || "");

  const generatedIdent = generateCategoryIdent(name);

  // A category with its own sub-categories can't also become a child
  // (single-level hierarchy).
  const hasChildren = category
    ? categories.some((c) => c["category/parent"]?.["db/id"] === category["db/id"])
    : false;

  // Only persisted top-level categories (excluding this one) are eligible
  // parents; unsaved categories (negative temp id) have no real id to reference.
  const parentOptions = categories.filter(
    (c) => !c["category/parent"] && c["db/id"] !== category?.["db/id"] && c["db/id"] > 0
  );

  const handleSubmit = () => {
    onClose();
  };

  return (
    <Modal onClose={onClose} label={isEditing ? "Edit category" : "Add category"}>
        <h2>{isEditing ? "Edit Category" : "Add Category"}</h2>
        <fetcher.Form method="post" onSubmit={handleSubmit}>
          <input
            type="hidden"
            name="intent"
            value={isEditing ? "update-category" : "create-category"}
          />
          <input type="hidden" name="ident" value={generatedIdent} />
          {isEditing && <input type="hidden" name="id" value={category["db/id"].toString()} />}

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

          <div className="form-group">
            <label className="form-label" htmlFor="parentId">
              Parent
            </label>
            <select
              className="form-select"
              id="parentId"
              name="parentId"
              defaultValue={category?.["category/parent"]?.["db/id"]?.toString() ?? ""}
              disabled={hasChildren}
            >
              <option value="">No parent</option>
              {parentOptions.map((option) => (
                <option key={option["db/id"]} value={option["db/id"]}>
                  {option["category/name"]}
                </option>
              ))}
            </select>
            {hasChildren && (
              <p className="bulk-modal-hint">
                This category has sub-categories, so it can't become a child.
              </p>
            )}
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
    </Modal>
  );
}
