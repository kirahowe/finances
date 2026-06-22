// Column auto-sizing + manual resize for the /v2 transactions table.
//
// A DOM-driven version of col-resize: the column metadata (id / minSize / protected) is read
// from the server-rendered `<th data-col-id data-min data-protected>`, so it isn't tied to a
// hardcoded column set (the old col-resize island is). The sizing math is the shared, pure
// columnAutoSizing module. The table is `table-layout: fixed`, so the <col> widths are
// authoritative; this island auto-fits visible columns to content (capped, growing/shrinking
// to the container), lets the user drag borders to override, and double-click fits a column
// to its content. Hidden columns (the column picker toggles a `hide-<id>` table class) are
// excluded from the fit and their <col> set display:none (else fixed layout shifts the
// remaining columns onto the wrong widths).
//
// Resize model: a manual gesture (drag or double-click) is LOCAL — it changes ONLY the
// touched column. Columns to its LEFT keep their exact width; columns to its RIGHT keep their
// exact width and are pushed right; the table's total width grows/shrinks by the drag delta and
// the scroll container scrolls horizontally when the table exceeds it. There is NEVER any
// redistribution of the other columns during a drag.
//
// To make that hold under fixed layout, the first manual gesture FREEZES every visible column's
// current rendered width into userWidths and then PINS the table to the exact sum of those widths
// (plus the fixed actions column): the table's `width` — not just `min-width` — is set to that
// sum. That removes the only source of give: with an exact table width there is no leftover for
// the browser to redistribute, so a one-column delta grows the table by exactly that delta and
// scrolls, while every other column stays put. The trailing <col.actions-col> is a REAL fixed
// column (the row-actions caret), so its width is folded into the sum rather than collapsed —
// collapsing it (an earlier bug) hid the carets and left the table a column short. While pinned,
// container-resize no longer re-distributes (that would un-localise the resize); the auto-fit
// (which fits the data columns into card − actions so the table fills) returns only via
// "Reset widths" (window.__resetWidths).

import {
  measureIntrinsicWidths,
  distributeColumnWidths,
  fitColumnWidth,
  type ColumnLeaf,
  type SizingPolicy,
} from './lib/columnAutoSizing';

const scroll = document.querySelector<HTMLElement>('.transactions-table-scroll');
const table = scroll?.querySelector<HTMLTableElement>('table');

if (scroll && table) {
  const tbl = table;

  // Column metadata, read from the server-rendered headers (DOM order = colgroup order).
  const LEAVES: ColumnLeaf[] = [...tbl.querySelectorAll<HTMLElement>('thead th[data-col-id]')].map((th) => ({
    id: th.dataset.colId!,
    minSize: Number(th.dataset.min) || 80,
    protected: th.dataset.protected === 'true',
  }));
  const LEAF_IDS = LEAVES.map((l) => l.id);
  const minOf = (id: string) => LEAVES.find((l) => l.id === id)!.minSize;

  const POLICY: SizingPolicy = {
    shrinkOrder: ['account', 'institution', 'payee', 'description'],
    growIds: ['description', 'payee'],
    flexibleCap: 400,
  };
  const MAX_WIDTH = 600;

  const cols = () => [...tbl.querySelectorAll<HTMLElement>('colgroup col')];
  const colFor = (id: string): HTMLElement | undefined => cols()[LEAF_IDS.indexOf(id)];
  const isHidden = (id: string) => tbl.classList.contains(`hide-${id}`);
  const isVisible = (l: ColumnLeaf) => !isHidden(l.id);

  // The trailing <col.actions-col> after the real leaves is the row-actions column — a real,
  // fixed-width column (2.75rem) that holds the caret, NOT a spacer. Its width is folded into the
  // table-width sum so the table is exactly its full content width (no leftover for a drag delta
  // to be absorbed into), and it is never collapsed (that would hide the carets and short the
  // table). Resolved off the root font-size so it tracks the CSS rem rather than a magic 44.
  const ACTIONS_W = 2.75 * (parseFloat(getComputedStyle(document.documentElement).fontSize) || 16);

  // Never re-measure while an editor is open — its wide intrinsic width would balloon the
  // column. The resting layout is stable, so skipping is enough.
  const editing = () =>
    !!tbl.querySelector('.description-cell.editing') ||
    !!document.querySelector('.category-dropdown.is-floating');

  // Columns the user has pinned (via a drag, a double-click fit, or the freeze that the first
  // gesture applies to the whole table). Empty = pure auto-fit state; non-empty = pinned, so
  // container-resize stops re-distributing and only explicit gestures change a width.
  const userWidths: Record<string, number> = {};
  const pinned = () => Object.keys(userWidths).length > 0;

  function applyWidths(sizes: Record<string, number>) {
    const cs = cols();
    LEAF_IDS.forEach((id, i) => {
      const col = cs[i];
      if (!col) return;
      if (isHidden(id)) {
        col.style.display = 'none';
      } else {
        col.style.display = '';
        if (sizes[id] != null) col.style.width = `${sizes[id]}px`;
      }
    });
    updateTableWidth(sizes);
  }

  // Size the table to its full content width = sum of the VISIBLE data columns + the fixed
  // actions column.
  //  - Pinned (manual widths exist): set that width EXACTLY, so changing one column changes the
  //    table width by the same delta — left columns stay put, right columns are pushed over, and
  //    the container scrolls horizontally. Nothing is redistributed and the actions column keeps
  //    its width (the caret stays visible).
  //  - Not pinned (auto-fit): only a min-width floor; recompute() fits the data columns to
  //    (card − actions), so the table fills the card without overflowing.
  function updateTableWidth(sizes: Record<string, number>) {
    const dataTotal = LEAF_IDS.filter((id) => !isHidden(id)).reduce((a, id) => a + (sizes[id] ?? 0), 0);
    const total = dataTotal + ACTIONS_W;
    tbl.style.minWidth = `${total}px`;
    tbl.style.width = pinned() ? `${total}px` : '';
  }

  // Pure auto-fit: distribute the container width across the visible columns by content.
  // Runs only while NOT pinned (no manual width exists yet) — once a gesture pins the layout,
  // every column carries its own width and we never redistribute again (locality).
  function recompute() {
    if (editing() || pinned()) return;
    const intrinsic = measureIntrinsicWidths(tbl, LEAF_IDS);
    const visible = LEAVES.filter(isVisible);
    // Fit the data columns into the card minus the fixed actions column, so data + actions
    // fills the card exactly (no leftover the browser would redistribute into the actions col).
    const target = Math.max(0, (scroll!.clientWidth || 0) - ACTIONS_W);
    const sizes = distributeColumnWidths(intrinsic, visible, target, POLICY);
    applyWidths(sizes);
  }

  // Freeze every visible column at its current rendered width, then pin the table to their exact
  // sum (+ the actions column). Under fixed layout each visible <col> renders at its set width, so
  // the captured rects are the true per-column widths and pinning leaves every column visually put.
  // After this, subsequent local gestures move only their own column and grow/shrink the table by
  // the delta. Idempotent after the first call (already-pinned columns keep their width).
  function freezeWidths() {
    if (pinned()) return;
    for (const l of LEAVES) {
      if (isVisible(l)) userWidths[l.id] = Math.round(colFor(l.id)?.getBoundingClientRect().width ?? l.minSize);
    }
    updateTableWidth(userWidths); // pin the table width up front so there's no snap mid-drag
  }

  // Set one column's width locally: clamp, store, write its <col>, and resize the table to the
  // new visible-column sum. Never touches any other column's <col>; the table grows/shrinks by
  // the delta so right columns are pushed over and the container scrolls.
  function setColumnWidth(id: string, width: number) {
    const w = fitColumnWidth(width, minOf(id), MAX_WIDTH);
    userWidths[id] = w;
    const col = colFor(id);
    if (col) col.style.width = `${w}px`;
    updateTableWidth(userWidths);
  }

  recompute();

  // Re-fit on container resize and on column show/hide — but only while unpinned (recompute
  // self-guards). Observe the table element only, not its subtree, so grid-nav's per-cell
  // class writes don't trigger a re-measure on every keystroke. When pinned, a show/hide still
  // needs to re-apply <col> display + min-width (without redistributing the others).
  let raf = 0;
  const schedule = () => {
    if (raf) cancelAnimationFrame(raf);
    raf = requestAnimationFrame(() => {
      if (pinned()) applyWidths(userWidths);
      else recompute();
    });
  };
  new ResizeObserver(schedule).observe(scroll);
  new MutationObserver(schedule).observe(tbl, { attributes: true, attributeFilter: ['class'] });

  // Reset all manual widths and re-auto-fit (the toolbar "Reset widths" button calls this).
  (window as unknown as { __resetWidths?: () => void }).__resetWidths = () => {
    for (const id of Object.keys(userWidths)) delete userWidths[id];
    recompute();
  };

  // --- Manual drag-resize (local: freezes the layout, then moves only this column) ---
  let drag: { id: string; startX: number; startW: number; handle: HTMLElement } | null = null;

  function endDrag() {
    if (!drag) return;
    drag.handle.classList.remove('is-resizing');
    drag = null;
    // No recompute(): the layout is pinned, so the dragged column keeps its width and every
    // other column stays exactly where it was.
  }

  tbl.addEventListener('pointerdown', (e) => {
    const handle = (e.target as HTMLElement).closest<HTMLElement>('.col-resize-handle');
    if (!handle) return;
    const id = handle.closest<HTMLTableCellElement>('th')?.dataset.colId;
    const col = id ? colFor(id) : undefined;
    if (!id || !col) return;
    e.preventDefault();
    e.stopPropagation();
    freezeWidths(); // pin every column before the first delta, so the resize is local
    drag = { id, startX: e.clientX, startW: col.getBoundingClientRect().width, handle };
    handle.classList.add('is-resizing');
    handle.setPointerCapture(e.pointerId);
  });

  tbl.addEventListener('pointermove', (e) => {
    if (!drag) return;
    setColumnWidth(drag.id, Math.round(drag.startW + (e.clientX - drag.startX)));
  });

  tbl.addEventListener('pointerup', endDrag);
  tbl.addEventListener('pointercancel', endDrag);

  // Double-click a handle → fit THAT column to its content (Excel-style), every time —
  // regardless of whether it was manually resized first. Freezes the rest so they stay put.
  tbl.addEventListener('dblclick', (e) => {
    const id = (e.target as HTMLElement).closest<HTMLElement>('.col-resize-handle')
      ?.closest<HTMLTableCellElement>('th')?.dataset.colId;
    if (!id || editing()) return;
    freezeWidths();
    const intrinsic = measureIntrinsicWidths(tbl, LEAF_IDS)[id];
    if (intrinsic != null) setColumnWidth(id, intrinsic);
  });

  // Swallow the click that ends a resize drag (capture phase) so it doesn't toggle the sort.
  tbl.addEventListener('click', (e) => {
    if ((e.target as HTMLElement).closest('.col-resize-handle')) e.stopPropagation();
  }, true);

  console.log('resize island ready');
}
