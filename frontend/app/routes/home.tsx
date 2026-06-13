import { useState, useEffect, useMemo } from "react";
import type { Route } from "./+types/home";
import { api, type Category, type Transaction } from "../lib/api";
import { useSearchParams, useRevalidator } from "react-router";
import { SiteHeader } from "../components/SiteHeader";
import { OptimisticTransactionTable } from "../components/OptimisticTransactionTable";
import { SplitTransactionModal } from "../components/SplitTransactionModal";
import { TransferReviewModal } from "../components/TransferReviewModal";
import { MatchTransferModal } from "../components/MatchTransferModal";
import { Pagination } from "../components/Pagination";
import { FilterBar, type FilterConfig } from "../components/FilterBar";
import { ColumnPicker } from "../components/ColumnPicker";
import { MonthNavigator } from "../components/MonthNavigator";
import { parseSortingState, serializeSortingState } from "../lib/sortingState";
import {
  parseColumnVisibility,
  serializeColumnVisibility,
  parseColumnSizing,
  serializeColumnSizing,
} from "../lib/columnState";
import { HIDEABLE_COLUMNS, HIDEABLE_COLUMN_IDS } from "../lib/transactionColumns";
import { parseMonthParam, serializeMonth, type MonthState } from "../lib/monthState";
import type { SortingState, VisibilityState, ColumnSizingState } from "@tanstack/react-table";
import { PAGE_SIZE_OPTIONS, calculateTotalPages, type PageSize } from "../lib/pagination";
import { formatAmount } from "../lib/format";
import {
  parseFilters,
  serializeFilters,
  toggleFilterValue,
  clearFilterField,
  clearAllFilters,
  type FilterState,
  type FilterValue,
} from "../lib/filterState";
import { extractFilterOptions, applyFilters } from "../lib/filterOptions";
import "../styles/pages/dashboard.css";
import "../styles/components/pagination.css";
import "../styles/components/category-button.css";
import "../styles/components/filter.css";
import "../styles/components/month-navigator.css";
import "../styles/components/transfer-modal.css";

export function meta({}: Route.MetaArgs) {
  return [
    { title: "Finance Aggregator" },
    { name: "description", content: "Personal finance aggregation tool" },
  ];
}

export async function loader({ request }: Route.LoaderArgs) {
  const url = new URL(request.url);
  const month = url.searchParams.get("month");

  const monthState = parseMonthParam(month);
  const monthParam = serializeMonth(monthState);

  const [stats, transactions, categories] = await Promise.all([
    api.getStats(),
    api.getTransactions({ month: monthParam }),
    api.getCategories(),
  ]);

  return { stats, transactions, categories, month: monthParam };
}

export async function action({ request }: Route.ActionArgs) {
  const formData = await request.formData();
  const intent = formData.get("intent");

  if (intent === "update-transaction-category") {
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
  const { stats, transactions, categories, month } = loaderData;

  return (
    <div className="container">
      <SiteHeader stats={stats} />
      <TransactionsSection
        key={month}
        transactions={transactions}
        categories={categories}
        month={month}
      />
    </div>
  );
}

function TransactionsSection({
  transactions,
  categories,
  month,
}: {
  transactions: Transaction[];
  categories: Category[];
  month: string;
}) {
  const [searchParams, setSearchParams] = useSearchParams();
  const revalidator = useRevalidator();
  // page/pageSize live in the URL too, so a refresh restores the exact same view
  // (same slice of rows) with no jitter. The URL is 1-indexed for readability.
  const [page, setPage] = useState(() => {
    const raw = Number(searchParams.get("page"));
    return Number.isInteger(raw) && raw > 1 ? raw - 1 : 0;
  });
  const [pageSize, setPageSize] = useState<PageSize>(() => {
    const raw = Number(searchParams.get("pageSize"));
    return (PAGE_SIZE_OPTIONS as readonly number[]).includes(raw) ? (raw as PageSize) : 25;
  });
  const [splitTx, setSplitTx] = useState<Transaction | null>(null);
  const [transferTx, setTransferTx] = useState<Transaction | null>(null);
  const [reviewing, setReviewing] = useState(false);
  const [hideTransfers, setHideTransfers] = useState(
    () => searchParams.get("hideTransfers") === "1"
  );
  const [isSyncing, setIsSyncing] = useState(false);
  const [syncStatus, setSyncStatus] = useState<string>("");
  const [syncError, setSyncError] = useState<string | null>(null);

  // Month comes from loader data (server is source of truth)
  const currentMonth = parseMonthParam(month);

  // Initialize sorting from URL
  const [sorting, setSorting] = useState<SortingState>(() =>
    parseSortingState(searchParams.get("sort"))
  );

  // Initialize filters from URL - single source of truth
  const [filters, setFilters] = useState<FilterState>(() =>
    parseFilters(searchParams.get("filters"))
  );

  // Column view state (which columns are shown, and any resized widths) also
  // lives in the URL, consistent with sort/filters.
  const [columnVisibility, setColumnVisibility] = useState<VisibilityState>(() =>
    parseColumnVisibility(searchParams.get("cols"), HIDEABLE_COLUMN_IDS)
  );
  const [columnSizing, setColumnSizing] = useState<ColumnSizingState>(() =>
    parseColumnSizing(searchParams.get("colw"))
  );

  // Extract filter options from all transactions
  const filterConfigs = useMemo<FilterConfig[]>(() => {
    const accountOptions = extractFilterOptions(
      transactions,
      (tx) => tx["transaction/account"]?.["db/id"],
      (tx) => tx["transaction/account"]?.["account/external-name"] || "Unknown"
    );

    const categoryOptions = extractFilterOptions(
      transactions,
      (tx) => tx["transaction/category"]?.["db/id"],
      (tx) => tx["transaction/category"]?.["category/name"] || "Uncategorized"
    );

    // Reviewed is a two-value filter; selecting neither (the default) shows all.
    const reviewedOptions = extractFilterOptions(
      transactions,
      (tx) => (tx["transaction/reviewed"] ? "reviewed" : "unreviewed"),
      (tx) => (tx["transaction/reviewed"] ? "Reviewed" : "Unreviewed")
    );

    return [
      { field: "account", label: "Account", options: accountOptions },
      { field: "category", label: "Category", options: categoryOptions },
      { field: "reviewed", label: "Reviewed", options: reviewedOptions },
    ];
  }, [transactions]);

  // Apply filters to transactions, then optionally hide matched transfers.
  const filteredTransactions = useMemo(() => {
    const filtered = applyFilters(transactions, filters, {
      account: (tx: Transaction) => tx["transaction/account"]?.["db/id"],
      category: (tx: Transaction) => tx["transaction/category"]?.["db/id"],
      reviewed: (tx: Transaction) => (tx["transaction/reviewed"] ? "reviewed" : "unreviewed"),
    });
    return hideTransfers ? filtered.filter((tx) => !tx['transaction/transfer-hidden']) : filtered;
  }, [transactions, filters, hideTransfers]);

  // Month figures derived from the currently-visible (filtered) set.
  const summary = useMemo(() => {
    let inflow = 0;
    let outflow = 0;
    for (const tx of filteredTransactions) {
      const amount = tx["transaction/amount"] ?? 0;
      if (amount >= 0) inflow += amount;
      else outflow += amount;
    }
    return { count: filteredTransactions.length, inflow, outflow, net: inflow + outflow };
  }, [filteredTransactions]);

  // Keep `page` in range if the visible set shrinks (filters, hide-transfers) or the
  // URL carried a stale page beyond the available pages — otherwise the table would
  // render an empty slice with no row to act on.
  useEffect(() => {
    const lastPage = Math.max(0, calculateTotalPages(filteredTransactions.length, pageSize) - 1);
    if (page > lastPage) setPage(lastPage);
  }, [filteredTransactions.length, pageSize, page]);

  // Sync URL when sorting or filters change (not month - that uses React Router navigation)
  useEffect(() => {
    const currentUrl = new URL(window.location.href);

    const serializedSort = serializeSortingState(sorting);
    if (serializedSort) {
      currentUrl.searchParams.set("sort", serializedSort);
    } else {
      currentUrl.searchParams.delete("sort");
    }

    const serializedFilters = serializeFilters(filters);
    if (serializedFilters) {
      currentUrl.searchParams.set("filters", serializedFilters);
    } else {
      currentUrl.searchParams.delete("filters");
    }

    if (hideTransfers) {
      currentUrl.searchParams.set("hideTransfers", "1");
    } else {
      currentUrl.searchParams.delete("hideTransfers");
    }

    const serializedCols = serializeColumnVisibility(columnVisibility);
    if (serializedCols) {
      currentUrl.searchParams.set("cols", serializedCols);
    } else {
      currentUrl.searchParams.delete("cols");
    }

    const serializedColw = serializeColumnSizing(columnSizing);
    if (serializedColw) {
      currentUrl.searchParams.set("colw", serializedColw);
    } else {
      currentUrl.searchParams.delete("colw");
    }

    if (pageSize !== 25) {
      currentUrl.searchParams.set("pageSize", String(pageSize));
    } else {
      currentUrl.searchParams.delete("pageSize");
    }

    if (page > 0) {
      currentUrl.searchParams.set("page", String(page + 1));
    } else {
      currentUrl.searchParams.delete("page");
    }

    window.history.replaceState(null, "", currentUrl.toString());
  }, [sorting, filters, hideTransfers, columnVisibility, columnSizing, page, pageSize]);

  const handleSortingChange = (
    updaterOrValue: SortingState | ((old: SortingState) => SortingState)
  ) => {
    setSorting(updaterOrValue);
  };

  const handleToggleFilterValue = (field: string, value: FilterValue) => {
    setFilters((prev) => toggleFilterValue(prev, field, value));
  };

  const handleClearFilterField = (field: string) => {
    setFilters((prev) => clearFilterField(prev, field));
  };

  const handleClearAllFilters = () => {
    setFilters(clearAllFilters());
  };

  const handleMonthChange = (newMonth: MonthState) => {
    // Build from the live URL, not React Router's params: sort/filters/columns/page
    // size are written straight to the address bar via history.replaceState (the sync
    // effect above), so React Router's own searchParams are stale and would drop them
    // on navigation.
    const next = new URLSearchParams(window.location.search);
    next.set("month", serializeMonth(newMonth));
    // New month starts at the first page (the section remounts and re-reads `page`
    // from the URL, so clear it rather than relying on setPage alone).
    next.delete("page");
    setSearchParams(next);
    setPage(0);
  };

  const handleSyncMonth = async () => {
    setIsSyncing(true);
    setSyncStatus("Syncing transactions from Plaid...");
    setSyncError(null);

    try {
      const result = await api.syncPlaidMonthTransactions(month);
      setSyncStatus(`Successfully synced ${result.success.transactions} transaction(s)!`);

      setTimeout(() => {
        revalidator.revalidate();
        setSyncStatus("");
      }, 1500);
    } catch (err) {
      const errorMsg = err instanceof Error ? err.message : "Failed to sync transactions";
      setSyncError(errorMsg);
      setSyncStatus("");
    } finally {
      setIsSyncing(false);
    }
  };

  return (
    <>
      <div className="page-head">
        <span className="eyebrow">Ledger</span>
        <h2 className="page-title">Transactions</h2>
      </div>

      <div className="summary-bar">
        <div className="summary-item">
          <span className="eyebrow">Net</span>
          <span className={`summary-value ${summary.net >= 0 ? "positive" : "negative"}`}>
            {formatAmount(summary.net)}
          </span>
        </div>
        <div className="summary-item">
          <span className="eyebrow">Inflows</span>
          <span className="summary-value positive">{formatAmount(summary.inflow)}</span>
        </div>
        <div className="summary-item">
          <span className="eyebrow">Outflows</span>
          <span className="summary-value negative">{formatAmount(summary.outflow)}</span>
        </div>
        <div className="summary-divider" />
        <div className="summary-item">
          <span className="eyebrow">Transactions</span>
          <span className="summary-value">{summary.count}</span>
        </div>
      </div>

      <div className="card">
        <MonthNavigator
          currentMonth={currentMonth}
          onMonthChange={handleMonthChange}
          onSync={handleSyncMonth}
          isSyncing={isSyncing}
        />

        {syncStatus && <div className="status-banner">{syncStatus}</div>}
        {syncError && <div className="error-banner">{syncError}</div>}

        <FilterBar
          filters={filterConfigs}
          filterState={filters}
          onToggleValue={handleToggleFilterValue}
          onClearField={handleClearFilterField}
          onClearAll={handleClearAllFilters}
          inlineControls={
            <label className="transfer-toggle">
              <input
                type="checkbox"
                checked={hideTransfers}
                onChange={(e) => {
                  setHideTransfers(e.target.checked);
                  setPage(0);
                }}
              />
              <span>Hide transfers</span>
            </label>
          }
          trailingControls={
            <>
              <ColumnPicker
                columns={HIDEABLE_COLUMNS}
                visibility={columnVisibility}
                onChange={setColumnVisibility}
                onResetWidths={() => setColumnSizing({})}
              />
              <button
                type="button"
                className="button button-secondary"
                onClick={() => setReviewing(true)}
              >
                Find transfers
              </button>
            </>
          }
        />

        {filteredTransactions.length === 0 ? (
          <div className="empty-state">
            <div className="empty-state-title">No transactions this month</div>
            <p>
              Use the month controls to browse another period, or sync this month
              to pull the latest activity from your connected accounts.
            </p>
          </div>
        ) : (
          <>
            <OptimisticTransactionTable
              transactions={filteredTransactions}
              categories={categories}
              page={page}
              pageSize={pageSize}
              sorting={sorting}
              onSortingChange={handleSortingChange}
              columnVisibility={columnVisibility}
              onColumnVisibilityChange={setColumnVisibility}
              columnSizing={columnSizing}
              onColumnSizingChange={setColumnSizing}
              onSplit={setSplitTx}
              onOpenTransfer={setTransferTx}
            />

            <Pagination
              currentPage={page}
              pageSize={pageSize}
              totalItems={filteredTransactions.length}
              onPageChange={setPage}
              onPageSizeChange={setPageSize}
            />
          </>
        )}
      </div>

      {splitTx && (
        <SplitTransactionModal
          key={splitTx['db/id']}
          transaction={splitTx}
          categories={categories}
          onClose={() => setSplitTx(null)}
          onSaved={() => {
            setSplitTx(null);
            revalidator.revalidate();
          }}
        />
      )}

      {reviewing && (
        <TransferReviewModal
          onClose={() => setReviewing(false)}
          onApplied={() => revalidator.revalidate()}
        />
      )}

      {transferTx && (
        <MatchTransferModal
          transaction={transferTx}
          onClose={() => setTransferTx(null)}
          onSaved={() => {
            setTransferTx(null);
            revalidator.revalidate();
          }}
        />
      )}
    </>
  );
}
