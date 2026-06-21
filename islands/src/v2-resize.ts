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
// Resize model: a manual gesture (drag or double-click) is LOCAL — it changes only the
// touched column and leaves every other column exactly where it was. To make that hold under
// fixed layout (where the auto-distribute would otherwise reflow the flexible columns), the
// first manual gesture FREEZES every visible column's current rendered width into userWidths,
// so the layout is fully pinned and only the dragged/fitted column moves afterwards. While
// pinned, container-resize no longer re-distributes (that would un-localise the resize); the
// auto-fit returns only via "Reset widths" (window.__v2ResetWidths), which clears userWidths.

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
    updateMinWidth(sizes);
  }

  // The table's min-width is the sum of its VISIBLE columns, so it fills the card when they
  // fit and scrolls when they don't.
  function updateMinWidth(sizes: Record<string, number>) {
    const total = LEAF_IDS.filter((id) => !isHidden(id)).reduce((a, id) => a + (sizes[id] ?? 0), 0);
    tbl.style.minWidth = `${total}px`;
  }

  // Pure auto-fit: distribute the container width across the visible columns by content.
  // Runs only while NOT pinned (no manual width exists yet) — once a gesture pins the layout,
  // every column carries its own width and we never redistribute again (locality).
  function recompute() {
    if (editing() || pinned()) return;
    const intrinsic = measureIntrinsicWidths(tbl, LEAF_IDS);
    const visible = LEAVES.filter(isVisible);
    const sizes = distributeColumnWidths(intrinsic, visible, scroll!.clientWidth || 0, POLICY);
    applyWidths(sizes);
  }

  // Freeze every visible column at its current rendered width, so subsequent local gestures
  // (drag / double-click) move only their own column and leave the rest exactly put. Idempotent
  // after the first call (already-pinned columns keep their width).
  function freezeWidths() {
    if (pinned()) return;
    for (const l of LEAVES) {
      if (isVisible(l)) userWidths[l.id] = Math.round(colFor(l.id)?.getBoundingClientRect().width ?? l.minSize);
    }
  }

  // Set one column's width locally: clamp, store, write its <col>, and update the table's
  // min-width. Never touches any other column.
  function setColumnWidth(id: string, width: number) {
    const w = fitColumnWidth(width, minOf(id), MAX_WIDTH);
    userWidths[id] = w;
    const col = colFor(id);
    if (col) col.style.width = `${w}px`;
    updateMinWidth(userWidths);
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
  (window as unknown as { __v2ResetWidths?: () => void }).__v2ResetWidths = () => {
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

  console.log('v2-resize island ready');
}
