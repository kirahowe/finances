// Auto-sizing for the transactions table, split so the fragile part is tiny and the
// tricky part is pure. `measureIntrinsicWidths` is the ONLY code that touches the
// DOM; `distributeColumnWidths` is a pure function of the measurements, so the
// shrink/grow/protect math is unit-tested without a browser. The React glue lives in
// useAutoColumnSizing.

export interface ColumnLeaf {
  id: string;
  minSize: number;
  protected: boolean;
}

export interface SizingPolicy {
  // Order in which flexible columns give up width when the row overflows.
  shrinkOrder: readonly string[];
  // Wide text columns that absorb spare width to fill the container.
  growIds: readonly string[];
  // Cap on a non-protected column's default (content-fit) width.
  flexibleCap: number;
}

// Measure each visible column's intrinsic (content-fit) width. The table is normally
// `table-layout: fixed` with a <colgroup> driving widths; we briefly flip it to auto
// layout at `max-content` so the browser content-sizes every column, read the header
// cells, then restore. All synchronous, so a caller inside a layout effect or event
// handler sees no flash. Returns raw widths (uncapped) keyed by column id, in the
// same order as `leafIds` (which must match the real <col> / <th> order).
export function measureIntrinsicWidths(
  table: HTMLTableElement,
  leafIds: readonly string[],
): Record<string, number> {
  const headerCells = table.tHead?.rows[0]?.cells;
  if (!headerCells) return {};

  const cols = Array.from(table.querySelectorAll('colgroup col')) as HTMLElement[];
  const saved = {
    layout: table.style.tableLayout,
    width: table.style.width,
    minWidth: table.style.minWidth,
    cols: cols.map((c) => c.style.width),
  };

  table.style.tableLayout = 'auto';
  table.style.width = 'max-content';
  table.style.minWidth = '0';
  cols.forEach((c) => {
    c.style.width = 'auto';
  });

  const widths: Record<string, number> = {};
  leafIds.forEach((id, i) => {
    const cell = headerCells[i];
    // +1 guards against sub-pixel rounding clipping the content.
    widths[id] = cell ? Math.ceil(cell.getBoundingClientRect().width) + 1 : 0;
  });

  table.style.tableLayout = saved.layout;
  table.style.width = saved.width;
  table.style.minWidth = saved.minWidth;
  cols.forEach((c, i) => {
    c.style.width = saved.cols[i];
  });

  return widths;
}

// Pure. Turn raw intrinsic widths into the final per-column widths:
//  - protected columns keep their full content width; flexible columns are capped;
//  - if the row is wider than the container, flexible columns shrink (in order) down
//    to their minimums to make room, leaving protected columns at full width (a
//    leftover deficit just means the table scrolls — protected columns never clip);
//  - if there's spare room, the wide text columns grow to fill it.
export function distributeColumnWidths(
  intrinsic: Record<string, number>,
  leaves: readonly ColumnLeaf[],
  containerWidth: number,
  policy: SizingPolicy,
): Record<string, number> {
  const sizes: Record<string, number> = {};
  const minById: Record<string, number> = {};
  for (const leaf of leaves) {
    minById[leaf.id] = leaf.minSize;
    const raw = intrinsic[leaf.id] ?? leaf.minSize;
    const cap = leaf.protected ? Infinity : policy.flexibleCap;
    sizes[leaf.id] = Math.max(leaf.minSize, Math.min(cap, raw));
  }

  const total = Object.values(sizes).reduce((a, b) => a + b, 0);
  if (containerWidth <= 0) return sizes;

  if (total > containerWidth) {
    let deficit = total - containerWidth;
    for (const id of policy.shrinkOrder) {
      if (deficit <= 0) break;
      if (sizes[id] == null) continue;
      const take = Math.min(sizes[id] - minById[id], deficit);
      if (take > 0) {
        sizes[id] -= take;
        deficit -= take;
      }
    }
  } else if (total < containerWidth) {
    const surplus = containerWidth - total;
    const growers = policy.growIds.filter((id) => sizes[id] != null);
    const base = growers.reduce((a, id) => a + sizes[id], 0);
    if (base > 0) {
      for (const id of growers) {
        sizes[id] += Math.floor(surplus * (sizes[id] / base));
      }
    }
  }

  return sizes;
}

// Shallow numeric-record equality, to skip no-op auto-sizing state updates.
export function sameWidths(a: Record<string, number>, b: Record<string, number>): boolean {
  const ids = Object.keys(b);
  return ids.length === Object.keys(a).length && ids.every((id) => a[id] === b[id]);
}
