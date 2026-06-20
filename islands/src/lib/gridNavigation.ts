// The spreadsheet-style keyboard navigation core for the transactions table.
//
// This module is pure — no React, no DOM. It models the table as a flat list of
// navigable rows (each carrying the editable columns it actually offers) and runs
// a two-mode state machine over an `{ active, mode }` value:
//
//   navigation  — an active cell is highlighted; arrows move it.
//   edit        — the active cell's editor is open and owns its own keys.
//
// The owning hook (useGridNavigation) is a thin imperative shell that feeds the
// current GridModel in, resolves a keystroke to an Intent, dispatches it through
// `navReducer`, and moves real DOM focus to match. Keeping all the movement and
// mode logic here means the whole keymap is unit-testable without rendering a
// single component (mirrors the pure overlay modules + thin useWriteBehind shell).

// The editable columns, in visual left-to-right order. Horizontal navigation hops
// between these (skipping the read-only and hidden columns); this is the single
// source of truth for which columns the keyboard layer knows about.
export const EDITABLE_COLUMN_IDS = ['description', 'category', 'reviewed'] as const;
export type ColId = (typeof EDITABLE_COLUMN_IDS)[number];

export type NavMode = 'navigation' | 'edit';

// Stable identity of a navigable row. For an unsplit transaction and a split
// PARENT row, splitId is null; for a split CHILD row it's the part's db id. This
// (not a row index) keys the focus registry, so focus survives a re-sort.
export interface RowKey {
  txId: number;
  splitId: number | null;
}

export type RowKind = 'normal' | 'split-parent' | 'split-child';

// One <tr> the keyboard can land on. `cols` is the ordered subset of the visible
// editable columns this row actually offers — a split parent offers only its
// description; a split child offers all three. Rows with no editable columns are
// never built (see buildGridModel), so `cols` is always non-empty here.
export interface NavigableRow {
  key: RowKey;
  kind: RowKind;
  cols: ColId[];
}

export interface CellAddress {
  // The active row's stable identity (so the active cell survives a re-sort or an
  // optimistic re-order of the rows) plus its column. Row indices are resolved
  // from this against the live model only when computing neighbours.
  key: RowKey;
  col: ColId;
}

export interface NavState {
  active: CellAddress | null;
  mode: NavMode;
}

// The navigable grid: just the ordered rows. The reducer reasons purely over this
// — every row is guaranteed to have at least one editable column.
export interface GridModel {
  rows: NavigableRow[];
}

export const INITIAL_NAV_STATE: NavState = { active: null, mode: 'navigation' };

// ---------------------------------------------------------------------------
// Building the model
// ---------------------------------------------------------------------------

// The per-transaction shape buildGridModel needs from the displayed page: the
// transaction id and, when split, its parts' ids in display order (null when not
// split). Derived at the call site from the displayed rows + their sorted splits.
export interface RowInput {
  txId: number;
  splitIds: number[] | null;
}

// The visible editable columns, in visual order — the intersection of the
// editable set with whatever columns are currently shown. Pass
// table.getVisibleLeafColumns().map(c => c.id); hidden columns drop out, so the
// keyboard layer never lands on a column the user has hidden.
export function navigableColumns(visibleColumnIds: readonly string[]): ColId[] {
  return EDITABLE_COLUMN_IDS.filter((id) => visibleColumnIds.includes(id));
}

// Build the flat navigable-row list from the displayed transactions and the
// visible editable columns. A split transaction becomes a parent row (description
// only) followed by one child row per part (all three columns). Rows left with no
// editable columns — e.g. a split parent when the Description column is hidden —
// are omitted entirely, so navigation never stalls on an inert row.
export function buildGridModel(cols: ColId[], inputs: RowInput[]): GridModel {
  const childCols = cols; // children offer every visible editable column
  const parentCols = cols.filter((c) => c === 'description');

  const rows: NavigableRow[] = [];
  for (const input of inputs) {
    if (input.splitIds === null) {
      if (cols.length > 0) {
        rows.push({ key: { txId: input.txId, splitId: null }, kind: 'normal', cols });
      }
      continue;
    }
    if (parentCols.length > 0) {
      rows.push({
        key: { txId: input.txId, splitId: null },
        kind: 'split-parent',
        cols: parentCols,
      });
    }
    for (const splitId of input.splitIds) {
      if (childCols.length > 0) {
        rows.push({
          key: { txId: input.txId, splitId },
          kind: 'split-child',
          cols: childCols,
        });
      }
    }
  }
  return { rows };
}

// ---------------------------------------------------------------------------
// Cell identity helpers
// ---------------------------------------------------------------------------

// A stable string key for a cell, used by the hook's focus registry. Built from
// the row's identity (not its index) so it's invariant under sort/filter changes.
export function cellKey(key: RowKey, col: ColId): string {
  return `${key.txId}:${key.splitId ?? 'tx'}:${col}`;
}

// Whether a cell opens an INLINE editor (a text input or the category combobox).
// The reviewed checkbox toggles in place (Space), and a split child's category
// opens the split modal — neither is "inline editable", so Enter/type-to-edit
// must not switch them into edit mode. The hook routes those to side effects.
export function isInlineEditable(row: NavigableRow, col: ColId): boolean {
  if (col === 'reviewed') return false;
  if (col === 'category' && row.kind === 'split-child') return false;
  return true;
}

// ---------------------------------------------------------------------------
// Keymap: keystroke -> intent (data-driven)
// ---------------------------------------------------------------------------

export type Intent =
  | 'up'
  | 'down'
  | 'left'
  | 'right'
  | 'row-start'
  | 'row-end'
  | 'grid-start'
  | 'grid-end'
  | 'edit'
  | 'type-to-edit'
  | 'toggle-reviewed'
  | 'commit-down'
  | 'commit-close'
  | 'cancel';

// The minimal keyboard-event shape the resolver needs — so it can be exercised in
// a plain unit test without a real KeyboardEvent.
export interface KeyChord {
  key: string;
  shiftKey?: boolean;
  ctrlKey?: boolean;
  metaKey?: boolean;
  altKey?: boolean;
}

// A single binding in the keymap table. `mod` true requires Ctrl or Cmd; false
// requires neither; undefined means "don't care". `shift` works the same way.
interface KeyBinding {
  key: string;
  mode: NavMode;
  mod?: boolean;
  shift?: boolean;
  intent: Intent;
}

// The keymap as data, not a switch. Order matters only in that the first match
// wins; bindings are otherwise disjoint. Tab in either mode moves like an arrow —
// in edit mode the move unmounts the editor, whose blur commits the value.
const KEY_BINDINGS: KeyBinding[] = [
  // Navigation mode
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
  // Edit mode — only Tab is the grid's to handle; the editors own Enter/Escape
  // and report back via their callbacks (which dispatch commit-*/cancel).
  { key: 'Tab', mode: 'edit', shift: false, intent: 'right' },
  { key: 'Tab', mode: 'edit', shift: true, intent: 'left' },
];

const matches = (b: KeyBinding, e: KeyChord, mode: NavMode): boolean => {
  if (b.mode !== mode || b.key !== e.key) return false;
  const mod = !!(e.ctrlKey || e.metaKey);
  if (b.mod !== undefined && b.mod !== mod) return false;
  if (b.shift !== undefined && b.shift !== !!e.shiftKey) return false;
  return true;
};

// Resolve a keystroke to an intent for the current mode, or null when the grid
// should leave the key alone. Type-to-edit is handled ahead of the table: in
// navigation mode any lone printable character (no modifier, not Space) starts an
// edit, which would be impractical to enumerate as bindings.
export function resolveIntent(e: KeyChord, mode: NavMode): Intent | null {
  if (
    mode === 'navigation' &&
    e.key.length === 1 &&
    e.key !== ' ' &&
    !e.ctrlKey &&
    !e.metaKey &&
    !e.altKey
  ) {
    return 'type-to-edit';
  }
  for (const b of KEY_BINDINGS) {
    if (matches(b, e, mode)) return b.intent;
  }
  return null;
}

// ---------------------------------------------------------------------------
// The reducer: intent -> next NavState (pure)
// ---------------------------------------------------------------------------

const clamp = (n: number, lo: number, hi: number): number =>
  Math.max(lo, Math.min(hi, n));

const rowIndexOf = (rows: NavigableRow[], key: RowKey): number =>
  rows.findIndex((r) => r.key.txId === key.txId && r.key.splitId === key.splitId);

// The cell at row index `rowIdx`, keeping `preferred` if that row offers it (so
// vertical motion stays in its column), else its first column.
const cellAt = (rows: NavigableRow[], rowIdx: number, preferred: ColId): CellAddress => {
  const row = rows[rowIdx];
  return { key: row.key, col: row.cols.includes(preferred) ? preferred : row.cols[0] };
};

// The active cell's current row index, or 0 when its row has dropped out of the
// grid (a re-sort/filter removed it) — re-anchoring to the top beats moving from
// a stale position.
const activeIndex = (rows: NavigableRow[], active: CellAddress): number => {
  const i = rowIndexOf(rows, active.key);
  return i === -1 ? 0 : i;
};

const moveVertical = (active: CellAddress, rows: NavigableRow[], delta: number): CellAddress =>
  cellAt(rows, clamp(activeIndex(rows, active) + delta, 0, rows.length - 1), active.col);

const moveHorizontal = (active: CellAddress, rows: NavigableRow[], delta: number): CellAddress => {
  const i = activeIndex(rows, active);
  const cols = rows[i].cols;
  const idx = clamp(cols.indexOf(active.col) + delta, 0, cols.length - 1);
  return { key: rows[i].key, col: cols[idx] };
};

// Drive the navigation state machine. `model` is the current navigable grid; the
// reducer never mutates it. Movement clamps at the grid's edges (no page flip).
export function navReducer(state: NavState, intent: Intent, model: GridModel): NavState {
  const { rows } = model;
  if (rows.length === 0) return INITIAL_NAV_STATE;

  // Activate the first cell when something asks to move but nothing is active yet
  // (e.g. the first keystroke after focusing the table).
  const active = state.active ?? { key: rows[0].key, col: rows[0].cols[0] };
  const nav = (cell: CellAddress): NavState => ({ active: cell, mode: 'navigation' });
  const i = activeIndex(rows, active);

  switch (intent) {
    case 'up':
      return nav(moveVertical(active, rows, -1));
    case 'down':
      return nav(moveVertical(active, rows, +1));
    case 'left':
      return nav(moveHorizontal(active, rows, -1));
    case 'right':
      return nav(moveHorizontal(active, rows, +1));
    case 'row-start':
      return nav({ key: rows[i].key, col: rows[i].cols[0] });
    case 'row-end':
      return nav({ key: rows[i].key, col: rows[i].cols[rows[i].cols.length - 1] });
    case 'grid-start':
      return nav(cellAt(rows, 0, active.col));
    case 'grid-end':
      return nav(cellAt(rows, rows.length - 1, active.col));

    case 'edit':
    case 'type-to-edit':
      // Only inline-editable cells enter edit mode; the hook routes reviewed /
      // split-category cells to their side effects instead of dispatching here.
      if (!isInlineEditable(rows[i], active.col)) return { active, mode: 'navigation' };
      return { active, mode: 'edit' };

    case 'commit-down': {
      // Enter inside an editor: move down a row, and keep editing if the cell
      // below is the same column and also inline-editable — this is the rapid
      // "Enter walks the column" categorization flow. If we can't move (last row)
      // or the target isn't editable in this column, fall back to navigation.
      const next = moveVertical(active, rows, +1);
      const nextIdx = rowIndexOf(rows, next.key);
      const moved = nextIdx !== i;
      const stayEditing =
        moved && next.col === active.col && isInlineEditable(rows[nextIdx], next.col);
      return { active: next, mode: stayEditing ? 'edit' : 'navigation' };
    }
    case 'commit-close':
    case 'cancel':
      return { active, mode: 'navigation' };

    case 'toggle-reviewed':
      // A pure no-op here — the toggle itself is a persisted side effect the hook
      // performs. Returning state unchanged keeps the active cell put.
      return state;

    default:
      return state;
  }
}
