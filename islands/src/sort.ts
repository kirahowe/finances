// Client-side column sorting for the transactions table.
//
// Sorting is presentation, not business logic, so it stays on the client — clicking a
// header reorders the rows in the DOM with zero round-trips, which keeps every Datastar
// signal intact (search / review-scope / chip filters and the optimistic reviewed/desc/
// cat edits) — honouring the workspace rule "never drop filters". The server bakes the
// numeric sort keys (date as epoch, amount as a number) on each transaction's leader
// <tr>; string columns are read from the rendered cell text (so arbitrary text can't
// break an attribute). After a reorder the island fires `grid-refresh` so the grid-nav
// island rebuilds its row order.
//
// A "group" is a transaction's rows — its leader (the row carrying data-sort-*) plus any
// following rows without one (split children) — so a split's parts move with it.

export {}; // mark as a module so top-level consts are module-scoped (not globals)

const scroll = document.querySelector<HTMLElement>('.transactions-table-scroll');
const table = scroll?.querySelector<HTMLTableElement>('table');
const tbody = table?.querySelector<HTMLTableSectionElement>('tbody');
const thead = table?.querySelector<HTMLTableSectionElement>('thead');

if (scroll && table && tbody && thead) {
  const NUMERIC = new Set(['date', 'amount']);
  type Dir = 'asc' | 'desc';
  let sortCol: string | null = null;
  let sortDir: Dir = 'asc';

  type Group = HTMLTableRowElement[];

  const groupRows = (): Group[] => {
    const groups: Group[] = [];
    for (const tr of Array.from(tbody.rows)) {
      if (tr.dataset.sortDate !== undefined || groups.length === 0) groups.push([tr]);
      else groups[groups.length - 1].push(tr);
    }
    return groups;
  };
  // Original order, captured once, to restore on the third (clearing) click.
  const original = groupRows();

  const colIndexOf = (col: string): number => {
    const th = thead!.querySelector<HTMLTableCellElement>(`[data-sort-col="${col}"]`);
    return th ? th.cellIndex : -1;
  };

  const valueOf = (leader: HTMLTableRowElement, col: string, colIndex: number): string | number => {
    if (NUMERIC.has(col)) {
      return parseFloat((col === 'date' ? leader.dataset.sortDate : leader.dataset.sortAmount) ?? '0');
    }
    return (leader.cells[colIndex]?.textContent ?? '').trim().toLowerCase();
  };

  function apply() {
    let groups = original.slice();
    if (sortCol) {
      const colIndex = colIndexOf(sortCol);
      const dir = sortDir === 'asc' ? 1 : -1;
      const col = sortCol;
      groups.sort((a, b) => {
        const va = valueOf(a[0], col, colIndex);
        const vb = valueOf(b[0], col, colIndex);
        const cmp =
          typeof va === 'number' && typeof vb === 'number'
            ? va - vb
            : String(va).localeCompare(String(vb));
        return cmp * dir;
      });
    }
    for (const g of groups) for (const tr of g) tbody!.appendChild(tr);

    for (const th of thead!.querySelectorAll<HTMLElement>('[data-sort-col]')) {
      const isCol = th.dataset.sortCol === sortCol;
      th.classList.toggle('is-sorted', isCol);
      th.setAttribute('aria-sort', isCol ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none');
      const indicator = th.querySelector('.th-sort-indicator');
      if (indicator) indicator.textContent = isCol ? (sortDir === 'asc' ? '↑' : '↓') : '';
    }

    // Let grid-nav rebuild its navigable order from the new DOM order.
    scroll!.dispatchEvent(new CustomEvent('grid-refresh'));
    writeSortUrl();
  }

  // Persist the active sort in the URL (preserving the other view-state params, which the
  // url-state island owns) so a reload / shared link / month nav restores the order.
  function writeSortUrl() {
    const u = new URL(location.href);
    if (sortCol) u.searchParams.set('sort', `${sortCol}:${sortDir}`);
    else u.searchParams.delete('sort');
    history.replaceState(null, '', `${u.pathname}?${u.searchParams.toString()}`);
  }

  // Click cycles a column asc → desc → cleared (back to the original order).
  thead.addEventListener('click', (e) => {
    const th = (e.target as HTMLElement).closest<HTMLElement>('[data-sort-col]');
    if (!th) return;
    const col = th.dataset.sortCol!;
    if (sortCol !== col) {
      sortCol = col;
      sortDir = 'asc';
    } else if (sortDir === 'asc') {
      sortDir = 'desc';
    } else {
      sortCol = null;
      sortDir = 'asc';
    }
    apply();
  });

  // Restore a sort from the URL on load (set by a prior session / shared link / month nav).
  const fromUrl = new URL(location.href).searchParams.get('sort');
  if (fromUrl) {
    const [col, dir] = fromUrl.split(':');
    if (thead.querySelector(`[data-sort-col="${col}"]`)) {
      sortCol = col;
      sortDir = dir === 'desc' ? 'desc' : 'asc';
      apply();
    }
  }

  console.log('sort island ready');
}
