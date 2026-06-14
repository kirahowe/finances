import { useMemo } from "react";
import type { Category, Transaction } from "../lib/api";
import {
  buildCategoryRollup,
  type RollupRow,
  type RollupSection,
} from "../lib/categoryRollup";
import type { CategoryType } from "../lib/categoryTypes";
import { formatAmount } from "../lib/format";

interface CategoryRollupPaneProps {
  transactions: Transaction[];
  categories: Category[];
  /** Category ids currently in the table's category filter. */
  activeCategoryIds: Set<number>;
  /** Apply (or toggle off) the category filter for the clicked row's ids. */
  onSelectCategory: (ids: number[]) => void;
}

const SECTION_LABELS: Record<CategoryType, string> = {
  income: "Income",
  expense: "Expenses",
  transfer: "Transfers",
};

/** A row is active when its ids exactly match the current category filter. */
const isRowActive = (row: RollupRow, active: Set<number>): boolean =>
  row.clickable &&
  row.ids.length > 0 &&
  row.ids.length === active.size &&
  row.ids.every((id) => active.has(id));

function RollupRowView({
  row,
  active,
  onSelect,
}: {
  row: RollupRow;
  active: boolean;
  onSelect: (ids: number[]) => void;
}) {
  const className = [
    "rollup-row",
    `rollup-row--depth${row.depth}`,
    row.isGroup ? "rollup-row--group" : "",
    active ? "is-active" : "",
  ]
    .filter(Boolean)
    .join(" ");

  const name = (
    <span className="rollup-row-name">
      {row.depth === 1 && (
        <span className="rollup-branch" aria-hidden="true">
          └
        </span>
      )}
      {row.name}
    </span>
  );
  const amount = <span className="rollup-amount">{formatAmount(row.amount)}</span>;

  if (!row.clickable) {
    return (
      <li className={className}>
        <div className="rollup-row-static">
          {name}
          {amount}
        </div>
      </li>
    );
  }

  return (
    <li className={className}>
      <button
        type="button"
        className="rollup-row-button"
        onClick={() => onSelect(row.ids)}
        aria-pressed={active}
      >
        {name}
        {amount}
      </button>
    </li>
  );
}

function RollupSectionView({
  section,
  activeCategoryIds,
  onSelectCategory,
}: {
  section: RollupSection;
  activeCategoryIds: Set<number>;
  onSelectCategory: (ids: number[]) => void;
}) {
  const label = SECTION_LABELS[section.type];
  return (
    <section className="rollup-section">
      <h3 className="rollup-section-title">{label}</h3>
      <ul className="rollup-rows">
        {section.rows.map((row, i) => (
          <RollupRowView
            key={row.ids.length > 0 ? row.ids.join(",") : `uncat-${i}`}
            row={row}
            active={isRowActive(row, activeCategoryIds)}
            onSelect={onSelectCategory}
          />
        ))}
      </ul>
      <div className="rollup-subtotal">
        <span className="rollup-row-name">{label} total</span>
        <span className="rollup-amount">{formatAmount(section.total)}</span>
      </div>
    </section>
  );
}

export function CategoryRollupPane({
  transactions,
  categories,
  activeCategoryIds,
  onSelectCategory,
}: CategoryRollupPaneProps) {
  const rollup = useMemo(
    () => buildCategoryRollup(transactions, categories),
    [transactions, categories]
  );

  const sections = [rollup.income, rollup.expenses, rollup.transfers].filter(
    (s) => s.rows.length > 0
  );

  return (
    <aside className="rollup-pane" aria-label="Category summary">
      <div className="rollup-scroll">
        {sections.length === 0 ? (
          <p className="rollup-empty">No activity to summarize.</p>
        ) : (
          sections.map((section) => (
            <RollupSectionView
              key={section.type}
              section={section}
              activeCategoryIds={activeCategoryIds}
              onSelectCategory={onSelectCategory}
            />
          ))
        )}

        <div className="rollup-net">
          <span className="rollup-net-label">Net</span>
          <span
            className={`rollup-amount ${rollup.grandTotal >= 0 ? "positive" : "negative"}`}
          >
            {formatAmount(rollup.grandTotal)}
          </span>
        </div>
      </div>
    </aside>
  );
}
