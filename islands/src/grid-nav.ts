// Spreadsheet keyboard-navigation island for the transactions table.
//
// The movement/mode logic is the project's pure, framework-free reducer
// (src/lib/gridNavigation.ts), which vitest covers. This island is the imperative
// shell: rebuild the navigable grid from the server-rendered DOM (each editable
// <td> carries data-cell="txId:tx:col" — the middle token is a fixed literal, a
// kept-verbatim relic of the split-row era so the DOM contract never churned),
// translate keystrokes through the reducer, move DOM focus + the roving tabindex,
// and drive the inline editors. Every row is a plain transaction row — a split
// part included.
//
// Editing reuses the existing editors rather than reimplementing them: the island
// OPENS an editor (clicking the description button runs its Datastar open handler; a
// category cell dispatches `open-combobox` to the combobox island), and the editors
// report back through a bubbling `gridedit` DOM event — detail.action `advance`
// (Enter: persist + walk down the column, re-opening the next editor) or `cancel`
// (Escape / combobox close: return focus to the cell). The grid yields the keyboard
// whenever an editor owns focus; it only claims Tab (commit + move) while editing.

import {
  resolveIntent,
  navReducer,
  isInlineEditable,
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
  // their row key (DOM order = visual order) into the {key, cols} rows the
  // reducer reasons over (gotcha §2: no JSON in a <script>).
  const cellEls = new Map<string, HTMLElement>();
  const model: GridModel = { rows: [] };

  const parseKey = (dc: string): { key: RowKey; col: ColId } => {
    // data-cell="txId:tx:col" — the middle token is always the literal "tx".
    const [txStr, , col] = dc.split(':');
    return { key: { txId: Number(txStr) }, col: col as ColId };
  };

  // (Re)build the navigable grid from the current DOM order — called on load and again
  // whenever the table is re-rendered client-side (e.g. the sort island reorders rows).
  function buildModel() {
    cellEls.clear();
    model.rows.length = 0;
    let lastTxId: number | null = null;
    for (const td of table!.querySelectorAll<HTMLElement>('[data-cell]')) {
      // Skip cells in a hidden column (the column picker collapses them to display:none, so
      // offsetParent is null): an interactive column the user has hidden must drop out of
      // keyboard navigation too, never leaving focus stranded on an invisible cell.
      if (td.offsetParent === null) continue;
      const dc = td.dataset.cell!;
      cellEls.set(dc, td);
      const { key, col } = parseKey(dc);
      if (key.txId !== lastTxId) {
        model.rows.push({ key, cols: [] });
        lastTxId = key.txId;
      }
      model.rows[model.rows.length - 1].cols.push(col);
    }
  }
  buildModel();

  let state: NavState = { active: null, mode: 'navigation' };
  (window as unknown as { __gridState?: () => NavState }).__gridState = () => state;

  const elFor = (active: NavState['active']): HTMLElement | null =>
    active ? cellEls.get(cellKey(active.key, active.col)) ?? null : null;
  const rowFor = (key: RowKey): NavigableRow | undefined =>
    model.rows.find((r) => r.key.txId === key.txId);

  // An editor owns the keyboard while open: the floating combobox, or a focused
  // description input.
  const comboboxOpen = (): boolean => !!document.querySelector('.category-dropdown.is-floating');
  const descEditing = (): boolean =>
    document.activeElement instanceof HTMLElement &&
    document.activeElement.classList.contains('description-input');

  // paint() reflects the active cell (highlight + roving tabindex + ARIA); focus is
  // moved explicitly so opening an editor (which takes focus) isn't fought. `painting`
  // guards the morph observer below from reacting to paint()'s own class writes.
  let painting = false;
  function paint() {
    painting = true;
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
    } else {
      scroll!.setAttribute('tabindex', '0');
    }
    queueMicrotask(() => { painting = false; });
  }
  const focusActive = () => elFor(state.active)?.focus();

  // A server-authoritative edit/filter/sort/paginate morphs #tx-tbody, which makes idiomorph
  // reset the cells' attributes (wiping the active highlight). state.active is keyed by the
  // stable RowKey, so on any tbody mutation we rebuild the cell map and repaint — restoring the
  // active cell (or clearing it if its row is gone). Guarded so paint()'s own writes don't loop.
  const morphObserver = new MutationObserver(() => {
    if (painting) return;
    buildModel();
    paint();
    // An inline edit commit (description Enter, category pick) removes/hides the focused editor in
    // the morphed tbody, so focus is lost — dropped to <body>, or stranded on a now-hidden
    // description input idiomorph kept in place. grid-nav's keydown listener only fires within the
    // scroll container, so arrow nav would die. Restore focus to the active cell when it was lost
    // that way and no editor is legitimately open. Guarded so a morph the user drove from another
    // focused control (a filter chip, the search box) doesn't get its focus stolen.
    const ae = document.activeElement as HTMLElement | null;
    const editorOpen = comboboxOpen() || !!table!.querySelector('.description-cell.editing');
    const staleInput =
      !!ae?.classList.contains('description-input') &&
      !ae.closest('.description-cell')?.classList.contains('editing');
    if (state.active && !editorOpen && (ae === document.body || staleInput)) focusActive();
  });
  morphObserver.observe(table, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });

  function toggleReviewed() {
    elFor(state.active)?.querySelector<HTMLInputElement>('.reviewed-checkbox')?.click();
  }

  // Open the active cell's editor. Description: click its button (its Datastar
  // handler snapshots + opens + focuses), optionally seeding a typed character.
  // Category: hand off to the combobox island via a DOM event. Reviewed has no
  // inline editor, so it's left alone.
  function openActiveEditor(seed: string | null) {
    const a = state.active;
    if (!a) return;
    const row = rowFor(a.key);
    if (!row || !isInlineEditable(a.col)) return;
    const td = elFor(a);
    if (!td) return;
    if (a.col === 'description') {
      td.querySelector<HTMLButtonElement>('.description-button')?.click();
      if (seed != null) {
        const input = td.querySelector<HTMLInputElement>('.description-input');
        if (input) {
          input.value = seed;
          input.dispatchEvent(new Event('input', { bubbles: true }));
          input.setSelectionRange(seed.length, seed.length);
        }
      }
    } else if (a.col === 'category') {
      const btn = td.querySelector<HTMLElement>('.category-button.combo-cell');
      if (btn) document.dispatchEvent(new CustomEvent('open-combobox', { detail: { cell: btn, seed } }));
    }
    paint();
  }

  scroll.addEventListener('keydown', (e) => {
    if (comboboxOpen()) return; // the combobox island owns the keyboard while open
    if (model.rows.length === 0) return;

    // While editing a description, the input owns Enter/Escape/typing/arrows; the grid
    // only claims Tab (commit the editor via its blur handler, then move).
    if (descEditing()) {
      if (e.key !== 'Tab') return;
      e.preventDefault();
      (document.activeElement as HTMLElement).blur();
      state = navReducer({ active: state.active, mode: 'navigation' }, e.shiftKey ? 'left' : 'right', model);
      paint();
      focusActive();
      return;
    }

    // Navigation mode.
    if (e.key === 'Escape') {
      if (state.active) { e.preventDefault(); state = { active: null, mode: 'navigation' }; paint(); }
      return;
    }
    const intent = resolveIntent(e, 'navigation');
    if (!intent) return;
    e.preventDefault();

    if (intent === 'toggle-reviewed') {
      if (state.active?.col === 'reviewed') toggleReviewed();
      return;
    }
    if (intent === 'edit') {
      openActiveEditor(null);
      return;
    }
    if (intent === 'type-to-edit') {
      openActiveEditor(e.key);
      return;
    }
    state = navReducer(state, intent, model);
    paint();
    focusActive();
  });

  // Editor callbacks (bubbling DOM events): advance walks down the column re-opening
  // the editor; cancel returns focus to the cell.
  scroll.addEventListener('gridedit', (e) => {
    const action = (e as CustomEvent).detail?.action;
    if (!state.active) return;
    if (action === 'cancel') {
      state = { active: state.active, mode: 'navigation' };
      paint();
      focusActive();
      return;
    }
    if (action === 'advance') {
      const next = navReducer({ active: state.active, mode: 'edit' }, 'commit-down', model);
      state = { active: next.active, mode: 'navigation' };
      if (next.mode === 'edit') {
        openActiveEditor(null); // next row offers the same editable column — keep editing
      } else {
        paint();
        focusActive();
      }
    }
  });

  // A click selects the clicked cell. The focus guard keeps a click that opened an
  // inline editor from yanking focus back to the cell.
  table.addEventListener('click', (e) => {
    const td = (e.target as HTMLElement).closest<HTMLElement>('[data-cell]');
    if (!td) return;
    const { key, col } = parseKey(td.dataset.cell!);
    state = { active: { key, col }, mode: 'navigation' };
    paint();
    if (!descEditing() && !comboboxOpen()) focusActive();
  });

  // Tab into the grid with nothing active yet → land on the first cell.
  scroll.addEventListener('focus', () => {
    if (!state.active && model.rows.length > 0) {
      state = navReducer(state, 'grid-start', model);
      paint();
      focusActive();
    }
  });

  // A client-side re-render of the table (the sort island reordering rows) → rebuild the
  // model from the new DOM order. The active cell is keyed by RowKey, so it survives the
  // reorder (its <td> moved, but cellEls re-points to it); repaint to reflect the order.
  scroll.addEventListener('grid-refresh', () => {
    buildModel();
    paint();
  });

  console.log(`grid-nav island ready: ${model.rows.length} navigable rows`);
}
