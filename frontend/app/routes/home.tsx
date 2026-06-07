import { useState, useEffect, useMemo } from "react";
import type { Route } from "./+types/home";
import { api, type Category, type Transaction } from "../lib/api";
import { useSearchParams, useRevalidator } from "react-router";
import { SiteHeader } from "../components/SiteHeader";
import { OptimisticTransactionTable } from "../components/OptimisticTransactionTable";
import { SplitTransactionModal } from "../components/SplitTransactionModal";
import { TransferReviewModal } from "../components/TransferReviewModal";
import { MatchTransferModal } from "../components/MatchTransferModal";
import { ErrorDisplay } from "../components/ErrorDisplay";
import { Pagination } from "../components/Pagination";
import { FilterBar, type FilterConfig } from "../components/FilterBar";
import { MonthNavigator } from "../components/MonthNavigator";
import { parseSortingState, serializeSortingState } from "../lib/sortingState";
import { parseMonthParam, serializeMonth, type MonthState } from "../lib/monthState";
import type { SortingState } from "@tanstack/react-table";
import type { PageSize } from "../lib/pagination";
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
import { shouldHideTransfer } from "../lib/transferMatch";
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
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState<PageSize>(25);
  const [error, setError] = useState<string | null>(null);
  const [splitTx, setSplitTx] = useState<Transaction | null>(null);
  const [matchTx, setMatchTx] = useState<Transaction | null>(null);
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

    return [
      { field: "account", label: "Account", options: accountOptions },
      { field: "category", label: "Category", options: categoryOptions },
    ];
  }, [transactions]);

  // Apply filters to transactions, then optionally hide matched transfers.
  const filteredTransactions = useMemo(() => {
    const filtered = applyFilters(transactions, filters, {
      account: (tx: Transaction) => tx["transaction/account"]?.["db/id"],
      category: (tx: Transaction) => tx["transaction/category"]?.["db/id"],
    });
    return hideTransfers ? filtered.filter((tx) => !shouldHideTransfer(tx)) : filtered;
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

    window.history.replaceState(null, "", currentUrl.toString());
  }, [sorting, filters, hideTransfers]);

  const handleUnmatchTransfer = async (transaction: Transaction) => {
    try {
      await api.unmatchTransfer(transaction["db/id"]);
      revalidator.revalidate();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to unmatch transfer");
    }
  };

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
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      next.set("month", serializeMonth(newMonth));
      return next;
    });
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
        <ErrorDisplay error={error} onDismiss={() => setError(null)} />

        <FilterBar
          filters={filterConfigs}
          filterState={filters}
          onToggleValue={handleToggleFilterValue}
          onClearField={handleClearFilterField}
          onClearAll={handleClearAllFilters}
        />

        <div className="transfer-controls">
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
          <div className="transfer-controls-spacer" />
          <button
            type="button"
            className="button button-secondary"
            onClick={() => setReviewing(true)}
          >
            Find transfers
          </button>
        </div>

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
              onSplit={setSplitTx}
              onMatch={setMatchTx}
              onUnmatch={handleUnmatchTransfer}
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
          onApplied={() => {
            setReviewing(false);
            revalidator.revalidate();
          }}
        />
      )}

      {matchTx && (
        <MatchTransferModal
          transaction={matchTx}
          onClose={() => setMatchTx(null)}
          onSaved={() => {
            setMatchTx(null);
            revalidator.revalidate();
          }}
        />
      )}
    </>
  );
}
