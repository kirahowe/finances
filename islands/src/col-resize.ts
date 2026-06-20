// Column auto-sizing + manual resize for the transactions table.
//
// The sizing math is the project's pure, framework-free module (src/lib/columnAutoSizing.ts) —
// the same `measureIntrinsicWidths` / `distributeColumnWidths` React's useAutoColumnSizing
// drives and that vitest already covers. This island is the imperative shell: own the
// <colgroup> widths, auto-fit visible columns to content (capped, shrinking/growing to the
// container), let the user drag column borders to override, and double-click a handle to
// hand a column back to auto-sizing.
//
// The table is `table-layout: fixed`, so the <col> widths are authoritative. Hidden columns
// (the column picker toggles a `hide-<id>` class on the table) are excluded from the fit —
// their cells are display:none, which collapses the column and keeps the rest aligned.

import {
  measureIntrinsicWidths,
  distributeColumnWidths,
  type ColumnLeaf,
  type SizingPolicy,
} from './lib/columnAutoSizing';

// Column metadata, ported from React's transactionColumns.ts. Order MUST match the
// server-rendered <col>/<th> order (the trailing spacer <col> is not represented here).
const LEAVES: ColumnLeaf[] = [
  { id: 'date', minSize: 80, protected: true },
  { id: 'account', minSize: 90, protected: false },
  { id: 'institution', minSize: 90, protected: false },
  { id: 'payee', minSize: 100, protected: false },
  { id: 'description', minSize: 200, protected: false },
  { id: 'amount', minSize: 90, protected: true },
  { id: 'category', minSize: 200, protected: true },
  { id: 'reviewed', minSize: 80, protected: true },
  { id: 'actions', minSize: 56, protected: true },
];
const LEAF_IDS = LEAVES.map((l) => l.id);
const minOf = (id: string) => LEAVES.find((l) => l.id === id)!.minSize;

const POLICY: SizingPolicy = {
  shrinkOrder: ['account', 'institution', 'payee', 'description'],
  growIds: ['description', 'payee'],
  flexibleCap: 400,
};
const MAX_WIDTH = 600; // cap a manual drag (matches React's MAX_COLUMN_WIDTH)

const scroll = document.querySelector<HTMLElement>('.transactions-table-scroll');
const table = scroll?.querySelector<HTMLTableElement>('table');

if (scroll && table) {
  const tbl = table;
  const cols = () => [...tbl.querySelectorAll<HTMLElement>('colgroup col')];
  const colFor = (id: string): HTMLElement | undefined => cols()[LEAF_IDS.indexOf(id)];
  const isHidden = (id: string) => tbl.classList.contains(`hide-${id}`);

  // An open editor (description input / floating combobox) has a wide intrinsic width that
  // would balloon its column, so never re-measure while one is open — the resting layout is
  // stable, so simply skipping is enough (we don't shrink mid-edit and need no re-fit after).
  const editing = () =>
    !!tbl.querySelector('.description-cell.editing') ||
    !!document.querySelector('.category-dropdown.is-floating');

  // Columns the user has dragged keep their explicit width; the rest auto-fit the leftover.
  const userWidths: Record<string, number> = {};

  function applyWidths(sizes: Record<string, number>) {
    const cs = cols();
    LEAF_IDS.forEach((id, i) => {
      const col = cs[i];
      if (!col) return;
      // A hidden column's <col> must ALSO be display:none, not just its (display:none)
      // cells — in fixed layout a present-but-cell-hidden <col> still occupies a column
      // slot, shifting every later column onto the wrong width. Hiding the <col> too keeps
      // the remaining columns mapped 1:1 to their widths (the same effect as React only
      // rendering visible <col>s).
      if (isHidden(id)) {
        col.style.display = 'none';
      } else {
        col.style.display = '';
        if (sizes[id] != null) col.style.width = `${sizes[id]}px`;
      }
    });
    // The table's min-width is the sum of its visible columns, so it fills the card
    // (width:100%) when they fit and scrolls when they don't; the spacer <col> absorbs slack.
    const total = LEAF_IDS.filter((id) => !isHidden(id)).reduce((a, id) => a + (sizes[id] ?? 0), 0);
    tbl.style.minWidth = `${total}px`;
  }

  function recompute() {
    if (editing()) return;
    const intrinsic = measureIntrinsicWidths(tbl, LEAF_IDS);
    const visible = LEAVES.filter((l) => !isHidden(l.id));
    const auto = visible.filter((l) => userWidths[l.id] == null);
    const reserved = visible
      .filter((l) => userWidths[l.id] != null)
      .reduce((a, l) => a + userWidths[l.id], 0);
    const sizes = distributeColumnWidths(intrinsic, auto, (scroll!.clientWidth || 0) - reserved, POLICY);
    for (const l of visible) if (userWidths[l.id] != null) sizes[l.id] = userWidths[l.id];
    applyWidths(sizes);
  }

  recompute();

  // Re-fit on container resize and on column show/hide (the picker toggles a table class).
  let raf = 0;
  const schedule = () => {
    if (raf) cancelAnimationFrame(raf);
    raf = requestAnimationFrame(recompute);
  };
  new ResizeObserver(schedule).observe(scroll);
  new MutationObserver(schedule).observe(tbl, { attributes: true, attributeFilter: ['class'] });

  // --- Manual drag-resize -----------------------------------------------------------------
  let drag: { id: string; startX: number; startW: number; col: HTMLElement; handle: HTMLElement } | null = null;

  // End the drag deterministically from the stored handle — pointer capture means a
  // pointerup/pointercancel may fire on the handle, not the cell under the cursor, so we
  // don't rely on the event target. pointercancel (touch interrupted, device sleep) must be
  // handled too, or `drag` stays set and every later move keeps resizing the column.
  function endDrag() {
    if (!drag) return;
    drag.handle.classList.remove('is-resizing');
    drag = null;
    recompute(); // re-fill the auto columns around the committed manual width
  }

  tbl.addEventListener('pointerdown', (e) => {
    const handle = (e.target as HTMLElement).closest<HTMLElement>('.col-resize-handle');
    if (!handle) return;
    const id = handle.closest<HTMLTableCellElement>('th')?.dataset.colId;
    const col = id ? colFor(id) : undefined;
    if (!id || !col) return;
    // Don't let the drag start a header sort or a grid-cell selection.
    e.preventDefault();
    e.stopPropagation();
    drag = { id, startX: e.clientX, startW: col.getBoundingClientRect().width, col, handle };
    handle.classList.add('is-resizing');
    handle.setPointerCapture(e.pointerId);
  });

  tbl.addEventListener('pointermove', (e) => {
    if (!drag) return;
    const w = Math.max(minOf(drag.id), Math.min(MAX_WIDTH, Math.round(drag.startW + (e.clientX - drag.startX))));
    userWidths[drag.id] = w;
    drag.col.style.width = `${w}px`;
  });

  tbl.addEventListener('pointerup', endDrag);
  tbl.addEventListener('pointercancel', endDrag);

  // Double-click a handle → drop the manual width and re-auto-fit that column.
  tbl.addEventListener('dblclick', (e) => {
    const id = (e.target as HTMLElement).closest<HTMLElement>('.col-resize-handle')
      ?.closest<HTMLTableCellElement>('th')?.dataset.colId;
    if (id && userWidths[id] != null) {
      delete userWidths[id];
      recompute();
    }
  });

  // Swallow the click that ends a resize drag (capture phase, before it reaches the sort
  // island's <thead> listener) so the trailing click doesn't toggle the column sort.
  tbl.addEventListener('click', (e) => {
    if ((e.target as HTMLElement).closest('.col-resize-handle')) e.stopPropagation();
  }, true);

  console.log('col-resize island ready');
}
