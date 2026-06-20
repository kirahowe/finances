// Column auto-sizing + manual resize for the /v2 transactions table.
//
// A DOM-driven version of col-resize: the column metadata (id / minSize / protected) is read
// from the server-rendered `<th data-col-id data-min data-protected>`, so it isn't tied to a
// hardcoded column set (the old col-resize island is). The sizing math is the shared, pure
// columnAutoSizing module. The table is `table-layout: fixed`, so the <col> widths are
// authoritative; this island auto-fits visible columns to content (capped, growing/shrinking
// to the container), lets the user drag borders to override, and double-click hands a column
// back to auto-sizing. Hidden columns (the column picker toggles a `hide-<id>` table class)
// are excluded from the fit and their <col> set display:none (else fixed layout shifts the
// remaining columns onto the wrong widths).

import {
  measureIntrinsicWidths,
  distributeColumnWidths,
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

  // Never re-measure while an editor is open — its wide intrinsic width would balloon the
  // column. The resting layout is stable, so skipping is enough.
  const editing = () =>
    !!tbl.querySelector('.description-cell.editing') ||
    !!document.querySelector('.category-dropdown.is-floating');

  const userWidths: Record<string, number> = {};

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

  // Re-fit on container resize and on column show/hide (the picker toggles the TABLE's class —
  // observe the table element only, not its subtree, so grid-nav's per-cell class writes don't
  // trigger a re-measure on every keystroke).
  let raf = 0;
  const schedule = () => {
    if (raf) cancelAnimationFrame(raf);
    raf = requestAnimationFrame(recompute);
  };
  new ResizeObserver(schedule).observe(scroll);
  new MutationObserver(schedule).observe(tbl, { attributes: true, attributeFilter: ['class'] });

  // --- Manual drag-resize ---
  let drag: { id: string; startX: number; startW: number; col: HTMLElement; handle: HTMLElement } | null = null;

  function endDrag() {
    if (!drag) return;
    drag.handle.classList.remove('is-resizing');
    drag = null;
    recompute();
  }

  tbl.addEventListener('pointerdown', (e) => {
    const handle = (e.target as HTMLElement).closest<HTMLElement>('.col-resize-handle');
    if (!handle) return;
    const id = handle.closest<HTMLTableCellElement>('th')?.dataset.colId;
    const col = id ? colFor(id) : undefined;
    if (!id || !col) return;
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

  // Swallow the click that ends a resize drag (capture phase) so it doesn't toggle the sort.
  tbl.addEventListener('click', (e) => {
    if ((e.target as HTMLElement).closest('.col-resize-handle')) e.stopPropagation();
  }, true);

  console.log('v2-resize island ready');
}
