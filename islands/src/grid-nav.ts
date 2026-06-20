// Spreadsheet keyboard-navigation island for the transactions table.
//
// The movement/mode logic is the project's pure, framework-free reducer
// (src/lib/gridNavigation.ts) — the same module React's useGridNavigation drives and
// that vitest already covers. This island is the imperative shell: rebuild the
// navigable grid from the server-rendered DOM (each editable <td> carries
// data-cell="txId:splitId|tx:col"), translate keystrokes through the reducer, move DOM
// focus + the roving tabindex, and drive the inline editors.
//
// Editing reuses the Phase-3c editors rather than reimplementing them: the island
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
  // their row key (DOM order = visual order) into the {key, kind, cols} rows the
  // reducer reasons over (gotcha §2: no JSON in a <script>).
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
  for (const r of rows) {
    if (r.key.splitId === null && r.cols.length === 1 && r.cols[0] === 'description') {
      r.kind = 'split-parent';
    }
  }
  const model: GridModel = { rows };

  let state: NavState = { active: null, mode: 'navigation' };
  (window as unknown as { __gridState?: () => NavState }).__gridState = () => state;

  const elFor = (active: NavState['active']): HTMLElement | null =>
    active ? cellEls.get(cellKey(active.key, active.col)) ?? null : null;
  const rowFor = (key: RowKey): NavigableRow | undefined =>
    rows.find((r) => r.key.txId === key.txId && r.key.splitId === key.splitId);

  // An editor owns the keyboard while open: the floating combobox, or a focused
  // description input.
  const comboboxOpen = (): boolean => !!document.querySelector('.category-dropdown.is-floating');
  const descEditing = (): boolean =>
    document.activeElement instanceof HTMLElement &&
    document.activeElement.classList.contains('description-input');

  // paint() reflects the active cell (highlight + roving tabindex + ARIA); focus is
  // moved explicitly so opening an editor (which takes focus) isn't fought.
  function paint() {
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
  }
  const focusActive = () => elFor(state.active)?.focus();

  function toggleReviewed() {
    elFor(state.active)?.querySelector<HTMLInputElement>('.reviewed-checkbox')?.click();
  }

  // Open the active cell's editor. Description: click its button (the 3c Datastar
  // handler snapshots + opens + focuses), optionally seeding a typed character.
  // Category (normal rows): hand off to the combobox island via a DOM event. Reviewed
  // and a split child's category have no inline editor, so they're left alone.
  function openActiveEditor(seed: string | null) {
    const a = state.active;
    if (!a) return;
    const row = rowFor(a.key);
    if (!row || !isInlineEditable(row, a.col)) return;
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

  console.log(`grid-nav island ready: ${model.rows.length} navigable rows`);
}
