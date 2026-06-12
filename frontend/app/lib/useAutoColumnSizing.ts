import { useCallback, useEffect } from 'react';
import type { Dispatch, RefObject, SetStateAction } from 'react';
import type { Column, Table } from '@tanstack/react-table';
import {
  measureIntrinsicWidths,
  distributeColumnWidths,
  sameWidths,
  type ColumnLeaf,
  type SizingPolicy,
} from './columnAutoSizing';
import {
  MAX_COLUMN_WIDTH,
  FLEXIBLE_COLUMN_CAP,
  COLUMN_SHRINK_ORDER,
  COLUMN_GROW_IDS,
  PROTECTED_COLUMN_IDS,
} from './transactionColumns';

const POLICY: SizingPolicy = {
  shrinkOrder: COLUMN_SHRINK_ORDER,
  growIds: COLUMN_GROW_IDS,
  flexibleCap: FLEXIBLE_COLUMN_CAP,
};

function leafSpecs<T>(table: Table<T>): ColumnLeaf[] {
  return table.getVisibleLeafColumns().map((c) => ({
    id: c.id,
    minSize: c.columnDef.minSize ?? 60,
    protected: PROTECTED_COLUMN_IDS.has(c.id),
  }));
}

// Owns the table's content-fit auto-sizing. `recompute()` measures the rendered
// columns and pushes new default widths into the caller's `autoSizing` state; call it
// from a layout effect keyed on whatever changes the visible rows (data, sort, page).
// `autoFitColumn` is the Excel-style double-click handler. All DOM access is funnelled
// through measureIntrinsicWidths, so the component stays declarative.
export function useAutoColumnSizing<T>(
  table: Table<T>,
  tableRef: RefObject<HTMLTableElement | null>,
  setAutoSizing: Dispatch<SetStateAction<Record<string, number>>>,
) {
  const recompute = useCallback(() => {
    const dom = tableRef.current;
    if (!dom) return;
    const leaves = leafSpecs(table);
    const intrinsic = measureIntrinsicWidths(dom, leaves.map((l) => l.id));
    const containerWidth = dom.parentElement?.clientWidth ?? 0;
    const sizes = distributeColumnWidths(intrinsic, leaves, containerWidth, POLICY);
    setAutoSizing((prev) => (sameWidths(prev, sizes) ? prev : sizes));
  }, [table, tableRef, setAutoSizing]);

  useEffect(() => {
    const onResize = () => recompute();
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, [recompute]);

  const autoFitColumn = useCallback(
    (column: Column<T>) => {
      const dom = tableRef.current;
      if (!dom) return;
      const ids = table.getVisibleLeafColumns().map((c) => c.id);
      const width = measureIntrinsicWidths(dom, ids)[column.id];
      if (width == null) return;
      const min = column.columnDef.minSize ?? 0;
      const fitted = Math.max(min, Math.min(MAX_COLUMN_WIDTH, width));
      table.setColumnSizing((old) => ({ ...old, [column.id]: fitted }));
    },
    [table, tableRef],
  );

  return { recompute, autoFitColumn };
}
