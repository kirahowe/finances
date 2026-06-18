import { describe, it, expect } from 'vitest';
import {
  navigableColumns,
  buildGridModel,
  cellKey,
  isInlineEditable,
  resolveIntent,
  navReducer,
  INITIAL_NAV_STATE,
  type GridModel,
  type NavState,
  type ColId,
} from './gridNavigation';

const ALL_COLS: ColId[] = ['description', 'category', 'reviewed'];

// A normal (unsplit) transaction row.
const tx = (txId: number) => ({ txId, splitIds: null });
// A split transaction with the given part ids.
const split = (txId: number, splitIds: number[]) => ({ txId, splitIds });

describe('navigableColumns', () => {
  it('keeps only editable columns, in visual order', () => {
    expect(navigableColumns(['date', 'description', 'amount', 'category', 'reviewed'])).toEqual([
      'description',
      'category',
      'reviewed',
    ]);
  });

  it('drops hidden editable columns', () => {
    expect(navigableColumns(['description', 'reviewed'])).toEqual(['description', 'reviewed']);
  });

  it('returns empty when no editable column is visible', () => {
    expect(navigableColumns(['date', 'amount', 'payee'])).toEqual([]);
  });
});

describe('buildGridModel', () => {
  it('builds one row per unsplit transaction with all visible editable columns', () => {
    const { rows } = buildGridModel(ALL_COLS, [tx(1), tx(2)]);
    expect(rows).toHaveLength(2);
    expect(rows[0]).toEqual({ key: { txId: 1, splitId: null }, kind: 'normal', cols: ALL_COLS });
  });

  it('expands a split into a description-only parent plus full child rows', () => {
    const { rows } = buildGridModel(ALL_COLS, [split(5, [51, 52])]);
    expect(rows.map((r) => [r.kind, r.key.splitId, r.cols])).toEqual([
      ['split-parent', null, ['description']],
      ['split-child', 51, ALL_COLS],
      ['split-child', 52, ALL_COLS],
    ]);
  });

  it('omits the split parent when Description is hidden (it would have no cells)', () => {
    const { rows } = buildGridModel(['category', 'reviewed'], [split(5, [51])]);
    expect(rows.map((r) => r.kind)).toEqual(['split-child']);
    expect(rows[0].cols).toEqual(['category', 'reviewed']);
  });

  it('produces no rows when every editable column is hidden', () => {
    expect(buildGridModel([], [tx(1), split(5, [51])]).rows).toEqual([]);
  });
});

describe('cell identity helpers', () => {
  it('keys cells by row identity and column, not index', () => {
    expect(cellKey({ txId: 7, splitId: null }, 'category')).toBe('7:tx:category');
    expect(cellKey({ txId: 7, splitId: 51 }, 'reviewed')).toBe('7:51:reviewed');
  });

  it('marks reviewed and split-child category as not inline-editable', () => {
    const normal = { key: { txId: 1, splitId: null }, kind: 'normal' as const, cols: ALL_COLS };
    const child = { key: { txId: 1, splitId: 9 }, kind: 'split-child' as const, cols: ALL_COLS };
    expect(isInlineEditable(normal, 'description')).toBe(true);
    expect(isInlineEditable(normal, 'category')).toBe(true);
    expect(isInlineEditable(normal, 'reviewed')).toBe(false);
    expect(isInlineEditable(child, 'category')).toBe(false);
    expect(isInlineEditable(child, 'description')).toBe(true);
  });
});

describe('resolveIntent', () => {
  it('maps arrows and Tab to movement in navigation mode', () => {
    expect(resolveIntent({ key: 'ArrowDown' }, 'navigation')).toBe('down');
    expect(resolveIntent({ key: 'ArrowUp' }, 'navigation')).toBe('up');
    expect(resolveIntent({ key: 'ArrowLeft' }, 'navigation')).toBe('left');
    expect(resolveIntent({ key: 'ArrowRight' }, 'navigation')).toBe('right');
    expect(resolveIntent({ key: 'Tab' }, 'navigation')).toBe('right');
    expect(resolveIntent({ key: 'Tab', shiftKey: true }, 'navigation')).toBe('left');
  });

  it('distinguishes Home/End from their Ctrl/Cmd variants', () => {
    expect(resolveIntent({ key: 'Home' }, 'navigation')).toBe('row-start');
    expect(resolveIntent({ key: 'End' }, 'navigation')).toBe('row-end');
    expect(resolveIntent({ key: 'Home', metaKey: true }, 'navigation')).toBe('grid-start');
    expect(resolveIntent({ key: 'End', ctrlKey: true }, 'navigation')).toBe('grid-end');
  });

  it('maps Enter to edit and Space to toggle in navigation mode', () => {
    expect(resolveIntent({ key: 'Enter' }, 'navigation')).toBe('edit');
    expect(resolveIntent({ key: ' ' }, 'navigation')).toBe('toggle-reviewed');
  });

  it('treats a lone printable character as type-to-edit', () => {
    expect(resolveIntent({ key: 'g' }, 'navigation')).toBe('type-to-edit');
    expect(resolveIntent({ key: '5' }, 'navigation')).toBe('type-to-edit');
    // ...but not with a modifier (that's a shortcut, leave it alone).
    expect(resolveIntent({ key: 'g', metaKey: true }, 'navigation')).toBeNull();
  });

  it('only claims Tab in edit mode, leaving Enter/Escape to the editor', () => {
    expect(resolveIntent({ key: 'Tab' }, 'edit')).toBe('right');
    expect(resolveIntent({ key: 'Tab', shiftKey: true }, 'edit')).toBe('left');
    expect(resolveIntent({ key: 'Enter' }, 'edit')).toBeNull();
    expect(resolveIntent({ key: 'Escape' }, 'edit')).toBeNull();
    expect(resolveIntent({ key: 'g' }, 'edit')).toBeNull();
  });
});

describe('navReducer', () => {
  const model: GridModel = buildGridModel(ALL_COLS, [tx(1), tx(2), tx(3)]);
  // The reducer addresses cells by stable row key; these helpers express an
  // expected/seed state by row INDEX in a given model, resolved to that key.
  const cellOf = (m: GridModel, row: number, col: ColId): NavState => ({
    active: { key: m.rows[row].key, col },
    mode: 'navigation',
  });
  const at = (row: number, col: ColId): NavState => cellOf(model, row, col);
  const editAt = (row: number, col: ColId): NavState => ({ ...at(row, col), mode: 'edit' });

  it('activates the first cell on the first move with no active cell', () => {
    expect(navReducer(INITIAL_NAV_STATE, 'down', model)).toEqual(at(1, 'description'));
  });

  it('moves down and up, clamping at the edges', () => {
    expect(navReducer(at(0, 'category'), 'down', model)).toEqual(at(1, 'category'));
    expect(navReducer(at(2, 'category'), 'down', model)).toEqual(at(2, 'category'));
    expect(navReducer(at(0, 'category'), 'up', model)).toEqual(at(0, 'category'));
  });

  it('moves left and right within a row, clamping at the ends', () => {
    expect(navReducer(at(0, 'description'), 'right', model)).toEqual(at(0, 'category'));
    expect(navReducer(at(0, 'category'), 'right', model)).toEqual(at(0, 'reviewed'));
    expect(navReducer(at(0, 'reviewed'), 'right', model)).toEqual(at(0, 'reviewed'));
    expect(navReducer(at(0, 'description'), 'left', model)).toEqual(at(0, 'description'));
  });

  it('jumps to row and grid edges', () => {
    expect(navReducer(at(1, 'category'), 'row-start', model)).toEqual(at(1, 'description'));
    expect(navReducer(at(1, 'category'), 'row-end', model)).toEqual(at(1, 'reviewed'));
    expect(navReducer(at(1, 'category'), 'grid-start', model)).toEqual(at(0, 'category'));
    expect(navReducer(at(1, 'category'), 'grid-end', model)).toEqual(at(2, 'category'));
  });

  it('enters edit mode only on inline-editable cells', () => {
    expect(navReducer(at(0, 'description'), 'edit', model).mode).toBe('edit');
    expect(navReducer(at(0, 'category'), 'edit', model).mode).toBe('edit');
    // Reviewed has no inline editor — Enter is a no-op, stays in navigation.
    expect(navReducer(at(0, 'reviewed'), 'edit', model).mode).toBe('navigation');
    // type-to-edit behaves the same as edit.
    expect(navReducer(at(0, 'description'), 'type-to-edit', model).mode).toBe('edit');
  });

  it('cancel and commit-close return to navigation on the same cell', () => {
    expect(navReducer(editAt(1, 'category'), 'cancel', model)).toEqual(at(1, 'category'));
    expect(navReducer(editAt(1, 'category'), 'commit-close', model)).toEqual(at(1, 'category'));
  });

  it('commit-down walks the column staying in edit mode', () => {
    expect(navReducer(editAt(0, 'category'), 'commit-down', model)).toEqual(editAt(1, 'category'));
  });

  it('commit-down on the last row commits and drops to navigation', () => {
    expect(navReducer(editAt(2, 'category'), 'commit-down', model)).toEqual(at(2, 'category'));
  });

  it('toggle-reviewed leaves the state untouched (the toggle is a side effect)', () => {
    const s = at(0, 'reviewed');
    expect(navReducer(s, 'toggle-reviewed', model)).toBe(s);
  });

  it('re-anchors to the top when the active row has dropped out of the grid', () => {
    // A row that no longer exists (e.g. re-sorted / filtered away) must not move
    // from a stale index; the reducer re-anchors to the first row.
    const ghost: NavState = {
      active: { key: { txId: 999, splitId: null }, col: 'category' },
      mode: 'navigation',
    };
    expect(navReducer(ghost, 'up', model)).toEqual(at(0, 'category'));
  });

  describe('split traversal', () => {
    const splitModel = buildGridModel(ALL_COLS, [tx(1), split(5, [51, 52]), tx(9)]);
    // rows: [0] tx1 normal, [1] split-parent(desc), [2] child51, [3] child52, [4] tx9
    const atS = (row: number, col: ColId): NavState => cellOf(splitModel, row, col);
    const editAtS = (row: number, col: ColId): NavState => ({ ...atS(row, col), mode: 'edit' });

    it('keeps the column when present, falls back to the first when absent', () => {
      // Down from tx1's category onto the split parent (description only) -> description.
      expect(navReducer(atS(0, 'category'), 'down', splitModel)).toEqual(atS(1, 'description'));
      // Down again from the parent into the first child keeps description.
      expect(navReducer(atS(1, 'description'), 'down', splitModel)).toEqual(atS(2, 'description'));
      // A child keeps category on the way down to the next child.
      expect(navReducer(atS(2, 'category'), 'down', splitModel)).toEqual(atS(3, 'category'));
      // Down from the last child into the next normal tx keeps category.
      expect(navReducer(atS(3, 'category'), 'down', splitModel)).toEqual(atS(4, 'category'));
    });

    it('commit-down out of a parent (description-only) drops to navigation in the child', () => {
      // Parent's description -> child's description is inline-editable, so it stays editing.
      expect(navReducer(editAtS(1, 'description'), 'commit-down', splitModel)).toEqual(
        editAtS(2, 'description')
      );
    });
  });
});
