import { useReducer, useRef, useState, useEffect, useCallback } from 'react';
import {
  navReducer,
  INITIAL_NAV_STATE,
  cellKey,
  type GridModel,
  type NavState,
  type CellAddress,
  type RowKey,
  type ColId,
  type Intent,
} from './gridNavigation';

// The thin imperative shell around the pure navigation core. It owns the
// `{ active, mode }` reducer, the current GridModel (held in a ref so the reducer
// reads the live grid without it being reducer state), a registry of cell DOM
// elements for roving focus, and the effect that moves real focus to match the
// active cell. All movement/mode logic lives in gridNavigation.ts; this file is
// the wiring (mirrors how useWriteBehind is a thin shell over a pure queue).

// Set/reset are component-driven (a click, or clearing the grid); plain Intents
// flow through the pure reducer.
type Action = Intent | 'reset' | { set: CellAddress; edit: boolean };

export interface CellStatus {
  active: boolean;
  editing: boolean;
}

export interface GridNavigation {
  navState: NavState;
  // The character that opened the current edit via type-to-edit (null otherwise),
  // so the editor can seed itself with it.
  editSeed: string | null;
  // Ref callback attached to each navigable cell's <td>, keyed by cellKey.
  registerCell: (key: string, el: HTMLElement | null) => void;
  // Whether a given cell is the active one, and whether it's being edited.
  cellStatus: (key: RowKey, col: ColId) => CellStatus;
  // Make a cell active (a mouse click); `edit` opens its editor immediately.
  activate: (key: RowKey, col: ColId, opts?: { edit?: boolean }) => void;
  // Drop the active cell (Escape in navigation) so Tab can leave the grid.
  clearActive: () => void;
  // Dispatch a resolved keyboard intent through the pure reducer.
  dispatchIntent: (intent: Intent) => void;
  setEditSeed: (seed: string | null) => void;
  // Editor commit/cancel callbacks — clear the seed and advance the state machine.
  commitAndMoveDown: () => void;
  commitClose: () => void;
  cancelEdit: () => void;
  // Publish the latest navigable grid for the reducer/effect to read. Call once
  // per render, after the model is computed.
  setModel: (model: GridModel) => void;
}

export function useGridNavigation(): GridNavigation {
  const modelRef = useRef<GridModel>({ rows: [] });
  const [editSeed, setEditSeed] = useState<string | null>(null);

  const [navState, dispatch] = useReducer((state: NavState, action: Action): NavState => {
    if (action === 'reset') return INITIAL_NAV_STATE;
    if (typeof action === 'object') {
      return { active: action.set, mode: action.edit ? 'edit' : 'navigation' };
    }
    return navReducer(state, action, modelRef.current);
  }, INITIAL_NAV_STATE);

  const cellEls = useRef(new Map<string, HTMLElement>());
  const registerCell = useCallback((key: string, el: HTMLElement | null) => {
    if (el) cellEls.current.set(key, el);
    else cellEls.current.delete(key);
  }, []);

  // Move DOM focus to the active cell while navigating; in edit mode the editor
  // owns focus, so leave it alone. Keyed by the cell's stable identity, so it
  // survives re-sorts of the underlying rows.
  useEffect(() => {
    if (!navState.active || navState.mode !== 'navigation') return;
    const row = modelRef.current.rows[navState.active.row];
    if (!row) return;
    cellEls.current.get(cellKey(row.key, navState.active.col))?.focus();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [navState.active?.row, navState.active?.col, navState.mode]);

  const setModel = (model: GridModel) => {
    modelRef.current = model;
  };

  const cellStatus = (key: RowKey, col: ColId): CellStatus => {
    const { active, mode } = navState;
    if (!active) return { active: false, editing: false };
    const row = modelRef.current.rows[active.row];
    const isActive =
      !!row &&
      row.key.txId === key.txId &&
      row.key.splitId === key.splitId &&
      active.col === col;
    return { active: isActive, editing: isActive && mode === 'edit' };
  };

  const activate = (key: RowKey, col: ColId, opts?: { edit?: boolean }) => {
    const row = modelRef.current.rows.findIndex(
      (r) => r.key.txId === key.txId && r.key.splitId === key.splitId
    );
    if (row === -1) return;
    setEditSeed(null);
    dispatch({ set: { row, col }, edit: !!opts?.edit });
  };

  const clearActive = () => dispatch('reset');
  const dispatchIntent = (intent: Intent) => dispatch(intent);

  const commitAndMoveDown = () => {
    setEditSeed(null);
    dispatch('commit-down');
  };
  const commitClose = () => {
    setEditSeed(null);
    dispatch('commit-close');
  };
  const cancelEdit = () => {
    setEditSeed(null);
    dispatch('cancel');
  };

  return {
    navState,
    editSeed,
    registerCell,
    cellStatus,
    activate,
    clearActive,
    dispatchIntent,
    setEditSeed,
    commitAndMoveDown,
    commitClose,
    cancelEdit,
    setModel,
  };
}
