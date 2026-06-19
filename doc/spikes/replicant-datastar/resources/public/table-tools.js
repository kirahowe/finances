// Column resize + auto-fit island (the TanStack column-sizing feature).
//
// Pointer-driven, so it's inherently JS — but small (~50 lines). Drag a header's
// right edge to resize that column; double-click the edge to auto-fit to the
// widest cell. Writes widths onto the <colgroup>'s <col> elements. In the real
// app these widths persist to the URL (per the project's URL-view-state rule) —
// here we just keep them live to prove the interaction works server-rendered.

const grid = document.getElementById('grid');
const colFor = (id) => grid.querySelector(`col[data-col="${id}"]`);

let active = null; // { col, startX, startW }

for (const handle of grid.querySelectorAll('.resize-handle')) {
  const id = handle.dataset.col;

  handle.addEventListener('pointerdown', (e) => {
    e.preventDefault();
    e.stopPropagation();
    const col = colFor(id);
    active = { col, startX: e.clientX, startW: col.getBoundingClientRect().width };
    handle.setPointerCapture(e.pointerId);
    grid.classList.add('resizing');
  });

  handle.addEventListener('pointermove', (e) => {
    if (!active) return;
    const w = Math.max(48, active.startW + (e.clientX - active.startX));
    active.col.style.width = w + 'px';
  });

  handle.addEventListener('pointerup', () => { active = null; grid.classList.remove('resizing'); });

  // Double-click: auto-fit to the widest cell content in this column.
  handle.addEventListener('dblclick', (e) => {
    e.preventDefault(); e.stopPropagation();
    const cells = grid.querySelectorAll(`td.col-${id} .cell-view, td.col-${id}, th.col-${id} .th-label`);
    let max = 0;
    for (const c of cells) max = Math.max(max, c.scrollWidth);
    colFor(id).style.width = (max + 28) + 'px';
  });
}

console.log('table-tools island ready: column resize + auto-fit');
