// Spreadsheet keyboard-navigation island for the spike.
//
// THE POINT OF THIS FILE: the app's keyboard layer (gridNavigation.ts) is a pure,
// React-free state machine. Below is a near-verbatim JS port of that reducer —
// resolveIntent + navReducer + the movement helpers — lifted straight out of the
// TypeScript with the types stripped. It runs entirely in the browser with zero
// network round-trips, so arrow/Tab/Home/End motion is as instant here as in the
// React app. Only the thin imperative shell at the bottom (focus + class + Datastar
// interop) is new; the logic that was "hard" is reused as-is.
//
// This is the honest answer to the crux question: server round-trips can't carry
// per-keystroke grid nav, but they don't have to — this island stays client-side
// and Datastar owns everything else. It's ~120 lines of logic vs. a whole SPA.

// ---- pure core (ported from gridNavigation.ts) ----------------------------

const EDITABLE_COLUMN_IDS = ['description', 'category', 'reviewed'];

// Only 'description' has an inline editor wired in this spike (category in the
// real app opens the downshift combobox — discussed in FINDINGS.md as its own
// client-side island). Mirrors isInlineEditable's intent.
const hasEditor = (col) => col === 'description';

const clamp = (n, lo, hi) => Math.max(lo, Math.min(hi, n));
const cellKey = (rowKey, col) => `${rowKey}:${col}`;
const rowIndexOf = (rows, key) => rows.findIndex((r) => r.key === key);

const cellAt = (rows, rowIdx, preferred) => {
  const row = rows[rowIdx];
  return { key: row.key, col: row.cols.includes(preferred) ? preferred : row.cols[0] };
};

const activeIndex = (rows, active) => {
  const i = rowIndexOf(rows, active.key);
  return i === -1 ? 0 : i;
};

const moveVertical = (active, rows, delta) =>
  cellAt(rows, clamp(activeIndex(rows, active) + delta, 0, rows.length - 1), active.col);

const moveHorizontal = (active, rows, delta) => {
  const i = activeIndex(rows, active);
  const cols = rows[i].cols;
  const idx = clamp(cols.indexOf(active.col) + delta, 0, cols.length - 1);
  return { key: rows[i].key, col: cols[idx] };
};

// keymap as data, first match wins (ported)
const KEY_BINDINGS = [
  { key: 'ArrowUp', mode: 'navigation', intent: 'up' },
  { key: 'ArrowDown', mode: 'navigation', intent: 'down' },
  { key: 'ArrowLeft', mode: 'navigation', intent: 'left' },
  { key: 'ArrowRight', mode: 'navigation', intent: 'right' },
  { key: 'Home', mode: 'navigation', mod: false, intent: 'row-start' },
  { key: 'End', mode: 'navigation', mod: false, intent: 'row-end' },
  { key: 'Home', mode: 'navigation', mod: true, intent: 'grid-start' },
  { key: 'End', mode: 'navigation', mod: true, intent: 'grid-end' },
  { key: 'Tab', mode: 'navigation', shift: false, intent: 'right' },
  { key: 'Tab', mode: 'navigation', shift: true, intent: 'left' },
  { key: 'Enter', mode: 'navigation', intent: 'edit' },
  { key: ' ', mode: 'navigation', intent: 'toggle-reviewed' },
  { key: 'Tab', mode: 'edit', shift: false, intent: 'right' },
  { key: 'Tab', mode: 'edit', shift: true, intent: 'left' },
];

const matchesBinding = (b, e, mode) => {
  if (b.mode !== mode || b.key !== e.key) return false;
  const mod = !!(e.ctrlKey || e.metaKey);
  if (b.mod !== undefined && b.mod !== mod) return false;
  if (b.shift !== undefined && b.shift !== !!e.shiftKey) return false;
  return true;
};

function resolveIntent(e, mode) {
  if (mode === 'navigation' && e.key.length === 1 && e.key !== ' ' &&
      !e.ctrlKey && !e.metaKey && !e.altKey) {
    return 'type-to-edit';
  }
  for (const b of KEY_BINDINGS) if (matchesBinding(b, e, mode)) return b.intent;
  return null;
}

function navReducer(state, intent, model) {
  const { rows } = model;
  if (rows.length === 0) return { active: null, mode: 'navigation' };
  const active = state.active ?? { key: rows[0].key, col: rows[0].cols[0] };
  const nav = (cell) => ({ active: cell, mode: 'navigation' });
  const i = activeIndex(rows, active);
  switch (intent) {
    case 'up': return nav(moveVertical(active, rows, -1));
    case 'down': return nav(moveVertical(active, rows, +1));
    case 'left': return nav(moveHorizontal(active, rows, -1));
    case 'right': return nav(moveHorizontal(active, rows, +1));
    case 'row-start': return nav({ key: rows[i].key, col: rows[i].cols[0] });
    case 'row-end': return nav({ key: rows[i].key, col: rows[i].cols[rows[i].cols.length - 1] });
    case 'grid-start': return nav(cellAt(rows, 0, active.col));
    case 'grid-end': return nav(cellAt(rows, rows.length - 1, active.col));
    case 'edit':
    case 'type-to-edit':
      if (!hasEditor(active.col)) return { active, mode: 'navigation' };
      return { active, mode: 'edit' };
    case 'commit-down': {
      const next = moveVertical(active, rows, +1);
      const nextIdx = rowIndexOf(rows, next.key);
      const moved = nextIdx !== i;
      const stayEditing = moved && next.col === active.col && hasEditor(next.col);
      return { active: next, mode: stayEditing ? 'edit' : 'navigation' };
    }
    case 'commit-close':
    case 'cancel': return { active, mode: 'navigation' };
    case 'toggle-reviewed': return state; // side effect performed by the shell
    default: return state;
  }
}

// ---- imperative shell (new: DOM focus + class + Datastar interop) ----------

const grid = document.getElementById('grid');

// Reconstruct the navigable grid from the server-rendered DOM: every editable
// cell carries data-cell="rowKey:col". Group consecutive cells by rowKey (DOM
// order = visual order) to rebuild [{key, cols}] — the same structure
// buildGridModel produces, but read from the HTML the server already sent.
const cellEls = new Map(); // cellKey -> <td>
const model = { rows: [] };
let lastKey = null;
for (const td of grid.querySelectorAll('[data-cell]')) {
  cellEls.set(td.dataset.cell, td);
  const i = td.dataset.cell.lastIndexOf(':');
  const rowKey = td.dataset.cell.slice(0, i);
  const col = td.dataset.cell.slice(i + 1);
  if (rowKey !== lastKey) { model.rows.push({ key: rowKey, cols: [] }); lastKey = rowKey; }
  model.rows[model.rows.length - 1].cols.push(col);
}

let state = { active: null, mode: 'navigation' };
window.__gridState = () => state; // exposed for the Playwright probe

function elFor(active) {
  return active ? cellEls.get(cellKey(active.key, active.col)) : null;
}

function render() {
  for (const td of cellEls.values()) td.classList.remove('cell-active');
  const el = elFor(state.active);
  if (!el) return;
  el.classList.add('cell-active');
  if (state.mode === 'navigation') el.focus();
}

function enterEdit(seed) {
  const td = elFor(state.active);
  if (!td) return;
  const input = td.querySelector('input.cell-input');
  if (!input) { state.mode = 'navigation'; return; }
  td.classList.add('editing');
  if (seed != null) {
    input.value = seed;
    input.dispatchEvent(new Event('input', { bubbles: true })); // sync Datastar signal
  }
  input.focus();
  if (seed == null) input.select();
}

function toggleReviewed() {
  const td = elFor(state.active);
  const btn = td && td.querySelector('button.check');
  if (btn) btn.click(); // Datastar handles the optimistic flip + write-behind
}

grid.addEventListener('keydown', (e) => {
  // If the category combobox island is open, it owns the keyboard.
  if (document.querySelector('.combo-dropdown')) return;

  const editing = document.activeElement &&
    document.activeElement.classList.contains('cell-input');
  // In edit mode the input (Datastar) owns Enter/Escape; the grid only steals Tab.
  if (editing && e.key !== 'Tab') return;

  const intent = resolveIntent(e, editing ? 'edit' : state.mode);
  if (!intent) return;
  e.preventDefault();

  // Editing a category cell means opening the combobox island, not an inline
  // input — hand off via a DOM event (island↔island through the DOM).
  if ((intent === 'edit' || intent === 'type-to-edit') &&
      state.active && state.active.col === 'category') {
    const td = elFor(state.active);
    if (td && td.classList.contains('combo-cell')) {
      document.dispatchEvent(new CustomEvent('open-combo', { detail: { td } }));
      return;
    }
  }

  if (editing && e.key === 'Tab') {
    // commit the open editor, then move like an arrow
    const td = document.activeElement.closest('td');
    document.activeElement.blur();
    td.classList.remove('editing');
    state.mode = 'navigation';
  }

  if (intent === 'toggle-reviewed') { toggleReviewed(); return; }

  const seed = intent === 'type-to-edit' ? e.key : null;
  state = navReducer(state, intent, model);
  render();
  if (state.mode === 'edit') enterEdit(seed);
});

// Click selects a cell (then the keyboard takes over) — matches the app's
// click-then-navigate flow and keeps the grid model the single source of truth.
grid.addEventListener('click', (e) => {
  const td = e.target.closest('[data-cell]');
  if (!td) return;
  const i = td.dataset.cell.lastIndexOf(':');
  state = { active: { key: td.dataset.cell.slice(0, i), col: td.dataset.cell.slice(i + 1) },
            mode: 'navigation' };
  render();
});

// Activate the first cell when the grid is focused with nothing active yet.
grid.addEventListener('focus', () => {
  if (!state.active) { state = navReducer(state, 'down', model); render(); }
});

console.log(`grid-nav island ready: ${model.rows.length} navigable rows`);
