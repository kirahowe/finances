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
import { Toolbar } from "../components/Toolbar";
import { ColumnPicker } from "../components/ColumnPicker";
import { MonthNavigator } from "../components/MonthNavigator";
import { CategoryRollupPane } from "../components/CategoryRollupPane";
import { ReviewScopeToggle } from "../components/ReviewScopeToggle";
import { CountToggleChip } from "../components/CountToggleChip";
import { computeMonthCounts } from "../lib/monthMetrics";
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
import {
  parseFilters,
  serializeFilters,
  toggleFilterValue,
  clearFilterField,
  clearAllFilters,
  removeFilterValue,
  type FilterState,
  type FilterValue,
} from "../lib/filterState";
import { extractFilterOptions, applyFilters, type FilterOption } from "../lib/filterOptions";
import {
  categoryFilterValues,
  buildCategoryOptions,
  uncategorizedTokenForType,
  isUncategorizedToken,
} from "../lib/categoryFilter";
import {
  applyReviewedOverlay,
  setTxOverride,
  setSplitOverride,
  EMPTY_REVIEWED_OVERRIDES,
  type ReviewedOverrides,
} from "../lib/reviewedOverlay";
import {
  applyDescriptionOverlay,
  setTxDescriptionOverride,
  setSplitDescriptionOverride,
  EMPTY_DESCRIPTION_OVERRIDES,
  type DescriptionOverrides,
} from "../lib/descriptionOverlay";
import {
  applyCategoryOverlay,
  setTxCategoryOverride,
  EMPTY_CATEGORY_OVERRIDES,
  type CategoryOverrides,
  type CategoryOverrideValue,
} from "../lib/categoryOverlay";
import { withLingeringRows } from "../lib/lingeringRows";
import { searchTransactions } from "../lib/searchTransactions";
import { ActiveFilterChips, type ActiveChip } from "../components/ActiveFilterChips";
import { TableSearch } from "../components/TableSearch";
import { useWriteBehind } from "../lib/useWriteBehind";
import "../styles/pages/dashboard.css";
import "../styles/components/pagination.css";
import "../styles/components/category-button.css";
import "../styles/components/filter.css";
import "../styles/components/month-navigator.css";
import "../styles/components/category-rollup.css";
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

  // Optimistic per-row projections. An edit has to show up at once in every place that
  // reads the transaction list — for a reviewed toggle that's the checkbox, the Reviewed
  // filter predicate, and the filter counts; for a description edit it's the cell — so we
  // overlay pending edits onto the loader snapshot here, above the filter, and derive
  // everything below from `mergedTransactions`. Persistence is debounced separately so a
  // burst of edits isn't chatty (see useWriteBehind).
  const [reviewedOverrides, setReviewedOverrides] =
    useState<ReviewedOverrides>(EMPTY_REVIEWED_OVERRIDES);
  const [descriptionOverrides, setDescriptionOverrides] =
    useState<DescriptionOverrides>(EMPTY_DESCRIPTION_OVERRIDES);
  const [categoryOverrides, setCategoryOverrides] =
    useState<CategoryOverrides>(EMPTY_CATEGORY_OVERRIDES);
  const { enqueue } = useWriteBehind();
  const mergedTransactions = useMemo(
    () =>
      applyCategoryOverlay(
        applyDescriptionOverlay(
          applyReviewedOverlay(transactions, reviewedOverrides),
          descriptionOverrides
        ),
        categoryOverrides
      ),
    [transactions, reviewedOverrides, descriptionOverrides, categoryOverrides]
  );

  // Free-text search (payee/description/category), URL-backed like the other view state.
  const [search, setSearch] = useState(() => searchParams.get("q") ?? "");

  // Rows the user has categorized out of the active category filter but that we keep in
  // view (stale) rather than yanking away mid-task — see lingeringRows / handleEditCategory.
  // Annotating a category is keep-context work, not triage; reviewed stays triage (the
  // row vanishes the moment you check it off). Cleared on any filter/sort/page reset below.
  const [lingeringIds, setLingeringIds] = useState<Set<number>>(() => new Set());

  // Counts for the review-scope toggle and the binary filter chips, from the overlaid
  // (in-view) transactions so they track optimistic edits.
  const counts = useMemo(() => computeMonthCounts(mergedTransactions), [mergedTransactions]);

  // Review scope reads/writes the same `reviewed` filter the rest of the app uses, so
  // the dropdown filter, the URL, and this toggle stay one source of truth.
  const reviewMode: "needs-review" | "all" = (filters.reviewed ?? []).includes(
    "unreviewed"
  )
    ? "needs-review"
    : "all";
  const handleNeedsReview = () => {
    setFilters((prev) => ({ ...prev, reviewed: ["unreviewed"] }));
    setPage(0);
  };
  const handleAllReviewed = () => {
    setFilters((prev) => clearFilterField(prev, "reviewed"));
    setPage(0);
  };

  // Uncategorized is a binary chip toggling both by-sign Uncategorized category buckets.
  const uncatTokens = [
    uncategorizedTokenForType("income"),
    uncategorizedTokenForType("expense"),
  ];
  const uncategorizedActive = (filters.category ?? []).some((v) =>
    uncatTokens.includes(v)
  );
  const handleToggleUncategorized = () => {
    setFilters((prev) =>
      uncategorizedActive
        ? clearFilterField(prev, "category")
        : { ...prev, category: uncatTokens }
    );
    setPage(0);
  };

  // Category metadata derived once from the full list: which ids exist (so a
  // deleted/unknown ref routes to Uncategorized, mirroring the rollup) and names
  // for the filter dropdown labels.
  const { presentCategoryIds, categoryNameById } = useMemo(() => {
    const present = new Set(categories.map((c) => c["db/id"]));
    const names = new Map(categories.map((c) => [c["db/id"], c["category/name"]]));
    return { presentCategoryIds: present, categoryNameById: names };
  }, [categories]);

  // Accessor map for the transaction filters, one per filterable field. `applyFilters`
  // only consults the accessor for a field when that field is present in the filter
  // state, so carrying every field is free for the unfiltered case. Institution is
  // reached through the account ref. The category accessor is split-aware and returns
  // several values: a transaction matches each of its parts' categories, or a by-sign
  // Uncategorized sentinel for parts with none.
  const txFilterAccessors = useMemo(
    () => ({
      account: (tx: Transaction) => tx["transaction/account"]?.["db/id"],
      institution: (tx: Transaction) =>
        tx["transaction/account"]?.["account/institution"]?.["db/id"],
      category: (tx: Transaction) => categoryFilterValues(tx, presentCategoryIds),
      reviewed: (tx: Transaction) =>
        tx["transaction/reviewed"] ? "reviewed" : "unreviewed",
    }),
    [presentCategoryIds]
  );

  const handleToggleReviewed = (transactionId: number, reviewed: boolean) => {
    setReviewedOverrides((prev) => setTxOverride(prev, transactionId, reviewed));
    enqueue(`tx:${transactionId}`, () => api.setTransactionReviewed(transactionId, reviewed));
  };

  const handleToggleSplitReviewed = (
    transactionId: number,
    splitId: number,
    reviewed: boolean
  ) => {
    setReviewedOverrides((prev) => setSplitOverride(prev, splitId, reviewed));
    enqueue(`split:${splitId}`, () => api.setSplitReviewed(transactionId, splitId, reviewed));
  };

  const handleEditDescription = (transactionId: number, description: string) => {
    setDescriptionOverrides((prev) => setTxDescriptionOverride(prev, transactionId, description));
    enqueue(`desc:${transactionId}`, () =>
      api.setTransactionDescription(transactionId, description)
    );
  };

  const handleEditSplitDescription = (
    transactionId: number,
    splitId: number,
    description: string
  ) => {
    setDescriptionOverrides((prev) => setSplitDescriptionOverride(prev, splitId, description));
    enqueue(`desc-split:${splitId}`, () =>
      api.setSplitMemo(transactionId, splitId, description)
    );
  };

  const handleEditCategory = (transactionId: number, categoryId: number | null) => {
    const cat = categoryId == null ? null : categories.find((c) => c["db/id"] === categoryId);
    const ref: CategoryOverrideValue = cat
      ? {
          "db/id": cat["db/id"],
          "category/name": cat["category/name"],
          "category/type": cat["category/type"],
        }
      : null;
    setCategoryOverrides((prev) => setTxCategoryOverride(prev, transactionId, ref));
    enqueue(`category:${transactionId}`, () =>
      api.updateTransactionCategory(transactionId, categoryId)
    );

    // Defer removal: when a category filter is active and the new category no longer
    // matches it, keep the row visible (stale) instead of yanking it out from under the
    // user. If the edit makes it match again, drop it from the linger set.
    const active = filters.category ?? [];
    if (active.length > 0) {
      // Test the edit against the same predicate the filter uses: project the new
      // category onto the row and ask categoryFilterValues what it now matches, rather
      // than re-deriving the token here. (`ref` is the overlay value computed above; the
      // override isn't in mergedTransactions yet, so we project it onto a copy.)
      const tx = mergedTransactions.find((t) => t["db/id"] === transactionId);
      const stillMatches =
        tx != null &&
        categoryFilterValues({ ...tx, "transaction/category": ref }, presentCategoryIds).some(
          (t) => active.includes(t)
        );
      setLingeringIds((prev) => {
        const next = new Set(prev);
        if (stillMatches) next.delete(transactionId);
        else next.add(transactionId);
        return next;
      });
    }
  };

  // Per-field options for the in-header column filters, derived from the overlaid
  // (in-view) transactions so counts track optimistic edits. Account/institution are
  // immutable attributes; category is editable (and so subject to the linger behavior).
  const accountOptions = useMemo(
    () =>
      extractFilterOptions(
        mergedTransactions,
        (tx) => tx["transaction/account"]?.["db/id"],
        (tx) => tx["transaction/account"]?.["account/external-name"] || "Unknown"
      ),
    [mergedTransactions]
  );
  const institutionOptions = useMemo(
    () =>
      extractFilterOptions(
        mergedTransactions,
        (tx) => tx["transaction/account"]?.["account/institution"]?.["db/id"],
        (tx) => tx["transaction/account"]?.["account/institution"]?.["institution/name"] || "Unknown"
      ),
    [mergedTransactions]
  );
  const categoryOptions = useMemo(
    () =>
      // The two by-sign Uncategorized sentinels are owned by the dedicated Uncategorized
      // toolbar chip, so keep them out of the column funnel — one control, no overlap.
      buildCategoryOptions(mergedTransactions, presentCategoryIds, categoryNameById).filter(
        (o) => !isUncategorizedToken(o.value)
      ),
    [mergedTransactions, presentCategoryIds, categoryNameById]
  );
  const filterOptionsByField = useMemo<Record<string, FilterOption[]>>(
    () => ({
      account: accountOptions,
      institution: institutionOptions,
      category: categoryOptions,
    }),
    [accountOptions, institutionOptions, categoryOptions]
  );

  // Apply filters, keep edited-away rows lingering (stale), then search, then hide
  // transfers. `staleIds` flags the lingering rows so the table can de-emphasize them.
  const { rows: visibleRows, staleIds } = useMemo(() => {
    const matched = applyFilters(mergedTransactions, filters, txFilterAccessors);
    return withLingeringRows(mergedTransactions, matched, lingeringIds);
  }, [mergedTransactions, filters, txFilterAccessors, lingeringIds]);

  const filteredTransactions = useMemo(() => {
    const searched = searchTransactions(visibleRows, search);
    return hideTransfers
      ? searched.filter((tx) => !tx["transaction/transfer-hidden"])
      : searched;
  }, [visibleRows, search, hideTransfers]);

  // Stale rows clear on the next natural reset: any filter/sort/page/search change drops
  // the linger set (month navigation remounts the section, so it resets there too). A
  // category edit touches none of these, so a stale row stays put while you keep working.
  useEffect(() => {
    setLingeringIds((prev) => (prev.size ? new Set() : prev));
  }, [filters, sorting, page, pageSize, hideTransfers, search]);

  // The active-filter summary: account/institution values and real category ids (the
  // by-sign Uncategorized tokens are represented by the Uncategorized toggle, and the
  // reviewed filter by the scope toggle, so they're omitted here), plus the search term.
  const activeChips = useMemo<ActiveChip[]>(() => {
    const accountLabel = new Map(accountOptions.map((o) => [o.value, o.label]));
    const institutionLabel = new Map(institutionOptions.map((o) => [o.value, o.label]));
    const chips: ActiveChip[] = [];
    for (const v of filters.account ?? [])
      chips.push({ field: "account", value: v, fieldLabel: "Account", valueLabel: accountLabel.get(v) ?? String(v) });
    for (const v of filters.institution ?? [])
      chips.push({ field: "institution", value: v, fieldLabel: "Institution", valueLabel: institutionLabel.get(v) ?? String(v) });
    for (const v of filters.category ?? []) {
      if (isUncategorizedToken(v)) continue;
      chips.push({ field: "category", value: v, fieldLabel: "Category", valueLabel: categoryNameById.get(v as number) ?? "Unknown" });
    }
    if (search.trim())
      chips.push({ field: "search", value: search, fieldLabel: "Search", valueLabel: search.trim() });
    return chips;
  }, [filters, accountOptions, institutionOptions, categoryNameById, search]);

  const handleRemoveChip = (field: string, value: FilterValue) => {
    if (field === "search") {
      setSearch("");
    } else {
      setFilters((prev) => removeFilterValue(prev, field, value));
    }
    setPage(0);
  };

  const handleClearAllChips = () => {
    setFilters(clearAllFilters());
    setSearch("");
    setPage(0);
  };

  // The rollup pane is a stable map of the whole month — no table filter (account,
  // category, reviewed, hide-transfers) touches it. You can keep clicking around it
  // to filter the table while the overview itself never shifts under you.
  const activeCategoryIds = useMemo(
    () => new Set<FilterValue>(filters.category ?? []),
    [filters.category]
  );

  // Clicking a rollup row replaces the category filter with that row's ids; clicking
  // the already-active row clears it.
  const handleSelectCategory = (ids: FilterValue[]) => {
    setFilters((prev) => {
      const current = prev.category ?? [];
      const allActive =
        ids.length > 0 &&
        ids.length === current.length &&
        ids.every((id) => current.includes(id));
      return allActive ? clearFilterField(prev, "category") : { ...prev, category: ids };
    });
    setPage(0);
  };

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

    const trimmedSearch = search.trim();
    if (trimmedSearch) {
      currentUrl.searchParams.set("q", trimmedSearch);
    } else {
      currentUrl.searchParams.delete("q");
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
  }, [sorting, filters, hideTransfers, search, columnVisibility, columnSizing, page, pageSize]);

  const handleSortingChange = (
    updaterOrValue: SortingState | ((old: SortingState) => SortingState)
  ) => {
    setSorting(updaterOrValue);
  };

  const handleToggleFilterValue = (field: string, value: FilterValue) => {
    setFilters((prev) => toggleFilterValue(prev, field, value));
    setPage(0);
  };

  const handleClearFilterField = (field: string) => {
    setFilters((prev) => clearFilterField(prev, field));
    setPage(0);
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

  return (
    <>
      <div className="page-head">
        <span className="eyebrow">Ledger</span>
        <h2 className="page-title">Transactions</h2>
      </div>

      <div className="transactions-layout">
        <div className="card">
          <MonthNavigator
            currentMonth={currentMonth}
            onMonthChange={handleMonthChange}
          />

          <Toolbar
            leadingControls={
              <>
                <TableSearch
                  value={search}
                  onChange={(v) => {
                    setSearch(v);
                    setPage(0);
                  }}
                />
                <ReviewScopeToggle
                  mode={reviewMode}
                  unreviewedCount={counts.unreviewed}
                  totalCount={counts.total}
                  onSelectNeedsReview={handleNeedsReview}
                  onSelectAll={handleAllReviewed}
                />
                <CountToggleChip
                  label="Uncategorized"
                  count={counts.uncategorized}
                  active={uncategorizedActive}
                  onToggle={handleToggleUncategorized}
                />
                <CountToggleChip
                  label="Hide transfers"
                  count={counts.transfersHidden}
                  active={hideTransfers}
                  onToggle={() => {
                    setHideTransfers((v) => !v);
                    setPage(0);
                  }}
                />
              </>
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
                  className="button button-secondary button-small"
                  onClick={() => setReviewing(true)}
                >
                  Find transfers
                </button>
              </>
            }
          />

          <ActiveFilterChips
            chips={activeChips}
            onRemove={handleRemoveChip}
            onClearAll={handleClearAllChips}
          />

          {filteredTransactions.length === 0 ? (
            <div className="empty-state">
              {mergedTransactions.length === 0 ? (
                // The month itself is empty — nothing imported for this period.
                <>
                  <div className="empty-state-title">No transactions this month</div>
                  <p>
                    Use the month controls to browse another period, or import
                    transactions from the Setup page.
                  </p>
                </>
              ) : (
                // The month has transactions, but the current view hides them all.
                <>
                  <div className="empty-state-title">No matching transactions</div>
                  <p>
                    Nothing this month matches your current filters and search.
                    Adjust or clear them above to widen the view.
                  </p>
                </>
              )}
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
                onToggleReviewed={handleToggleReviewed}
                onToggleSplitReviewed={handleToggleSplitReviewed}
                onEditDescription={handleEditDescription}
                onEditSplitDescription={handleEditSplitDescription}
                onEditCategory={handleEditCategory}
                filterState={filters}
                filterOptionsByField={filterOptionsByField}
                onToggleFilterValue={handleToggleFilterValue}
                onClearFilterField={handleClearFilterField}
                staleTransactionIds={staleIds}
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

        <CategoryRollupPane
          transactions={mergedTransactions}
          categories={categories}
          activeCategoryIds={activeCategoryIds}
          onSelectCategory={handleSelectCategory}
        />
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
