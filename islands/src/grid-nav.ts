// Spreadsheet keyboard-navigation island for the transactions table.
//
// The movement/mode logic is the project's pure, framework-free reducer
// (src/lib/gridNavigation.ts) — the same module React's useGridNavigation drives and
// that vitest already covers. This island is only the imperative shell: rebuild the
// navigable grid from the server-rendered DOM (every editable <td> carries
// data-cell="txId:splitId|tx:col"), translate keystrokes through the reducer, and move
// real DOM focus + the roving tabindex to match.
//
// SCOPE (Phase 3d-1): navigation + a11y only — arrows/Tab/Home/End move the active
// cell, click selects, Space toggles the reviewed checkbox. The island YIELDS the
// keyboard whenever an inline editor input is focused or the category combobox is open,
// so the Phase-3c mouse-driven editors are untouched. Keyboard-triggered editing
// (Enter-to-edit, type-to-edit, save-and-advance, combobox-open) lands in 3d-2, when
// edit-state ownership moves into this island.

import {
  resolveIntent,
  navReducer,
  cellKey,
  type GridModel,
  type NavState,
  type NavigableRow,
  type RowKey,
  type ColId,
} from './lib/gridNavigation';

const scroll = document.querySelector<HTMLElement>('.transactions-table-scroll');
const table = scroll?.querySelector<HTMLElement>('table');

if (scroll && table) {
  // Rebuild the navigable grid from the DOM: group consecutive [data-cell] cells by
  // their row key (DOM order = visual order) into the {key, kind, cols} rows the
  // reducer reasons over — read from the HTML the server already sent (gotcha §2: no
  // JSON in a <script>).
  const cellEls = new Map<string, HTMLElement>();
  const rows: NavigableRow[] = [];
  let lastRowKey: string | null = null;

  const parseKey = (dc: string): { key: RowKey; col: ColId } => {
    const [txStr, splitStr, col] = dc.split(':');
    return {
      key: { txId: Number(txStr), splitId: splitStr === 'tx' ? null : Number(splitStr) },
      col: col as ColId,
    };
  };

  for (const td of table.querySelectorAll<HTMLElement>('[data-cell]')) {
    const dc = td.dataset.cell!;
    cellEls.set(dc, td);
    const { key, col } = parseKey(dc);
    const rowKeyStr = `${key.txId}:${key.splitId ?? 'tx'}`;
    if (rowKeyStr !== lastRowKey) {
      rows.push({ key, kind: key.splitId !== null ? 'split-child' : 'normal', cols: [] });
      lastRowKey = rowKeyStr;
    }
    rows[rows.length - 1].cols.push(col);
  }
  // A splitId=null row offering only the description is a split parent (its other
  // columns are blank); everything else with a null splitId is a normal row.
  for (const r of rows) {
    if (r.key.splitId === null && r.cols.length === 1 && r.cols[0] === 'description') {
      r.kind = 'split-parent';
    }
  }
  const model: GridModel = { rows };

  let state: NavState = { active: null, mode: 'navigation' };
  // Exposed for the Playwright probe.
  (window as unknown as { __gridState?: () => NavState }).__gridState = () => state;

  const elFor = (active: NavState['active']): HTMLElement | null =>
    active ? cellEls.get(cellKey(active.key, active.col)) ?? null : null;

  // An inline editor (the description input) or the floating combobox owns the
  // keyboard while open — the island must not steal arrows/Tab from it.
  const editorOpen = (): boolean =>
    !!document.querySelector('.category-dropdown.is-floating') ||
    (document.activeElement instanceof HTMLElement &&
      document.activeElement.classList.contains('description-input'));

  function render() {
    for (const td of cellEls.values()) {
      td.classList.remove('grid-cell-active');
      td.setAttribute('tabindex', '-1');
      td.removeAttribute('aria-selected');
    }
    const el = elFor(state.active);
    if (el) {
      el.classList.add('grid-cell-active');
      el.setAttribute('tabindex', '0');
      el.setAttribute('aria-selected', 'true');
      scroll!.setAttribute('tabindex', '-1');
      // Don't pull focus to the cell when an editor just opened (e.g. a click that
      // started an inline edit) — the editor owns focus in that case.
      if (state.mode === 'navigation' && !editorOpen()) el.focus();
    } else {
      // No active cell: the container is the single tab stop back into the grid.
      scroll!.setAttribute('tabindex', '0');
    }
  }

  function toggleReviewed() {
    const box = elFor(state.active)?.querySelector<HTMLInputElement>('.reviewed-checkbox');
    box?.click(); // Datastar owns the optimistic flip + write-behind
  }

  scroll.addEventListener('keydown', (e) => {
    if (editorOpen()) return; // an editor owns the keyboard
    if (model.rows.length === 0) return;

    const intent = resolveIntent(e, 'navigation');
    if (!intent) return;
    // Keyboard-triggered editing is 3d-2; for now leave Enter and printable keys to
    // the browser / the 3c click-to-edit flow.
    if (intent === 'edit' || intent === 'type-to-edit') return;

    e.preventDefault();
    if (intent === 'toggle-reviewed') {
      if (state.active?.col === 'reviewed') toggleReviewed();
      return;
    }
    state = navReducer(state, intent, model);
    render();
  });

  // A click selects the cell (then the keyboard takes over). render()'s focus guard
  // keeps a click that opened an inline editor from yanking focus back to the cell.
  table.addEventListener('click', (e) => {
    const td = (e.target as HTMLElement).closest<HTMLElement>('[data-cell]');
    if (!td) return;
    const { key, col } = parseKey(td.dataset.cell!);
    state = { active: { key, col }, mode: 'navigation' };
    render();
  });

  // Tab into the grid with nothing active yet → land on the first cell.
  scroll.addEventListener('focus', () => {
    if (!state.active && model.rows.length > 0) {
      state = navReducer(state, 'grid-start', model);
      render();
    }
  });

  console.log(`grid-nav island ready: ${model.rows.length} navigable rows`);
}
