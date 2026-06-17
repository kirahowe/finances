// Defer-removal ("linger") projection for edits to a filtered, EDITABLE field.
//
// When a filter on a field the user can edit in place (category) is active and an edit
// makes a row stop matching, removing it instantly is jarring — the row "vanishes out
// from under you" mid-task. Categorization is annotate-and-keep-context work, not triage,
// so a just-edited row LINGERS: it stays visible but marked stale (the caller dims it and
// shows a moved badge) until the next natural reset — a filter / sort / page change or
// month navigation clears the linger set. This is deliberately the OPPOSITE policy from
// the reviewed flag, which is a triage queue and *should* disappear the moment you check
// it off (that disappearance is the satisfying part). See the owner (home.tsx) for where
// the linger set is populated and cleared.

import type { Transaction } from './api';

export interface VisibleRows {
  /** Rows to display: those matching the filters, plus still-present lingering rows,
   *  in the original candidate order. */
  rows: Transaction[];
  /** Of `rows`, the ids that are lingering (kept despite no longer matching) — the
   *  caller renders these stale. */
  staleIds: Set<number>;
}

// Given the full candidate set (the overlaid, pre-filter month), the rows that currently
// MATCH the active filters, and the set of ids the user has edited-away-but-kept,
// return the rows to show (matched ∪ still-present lingering, in candidate order) and
// which of them are stale. A lingering row that matches again (e.g. the edit was undone
// or the category was reassigned back) is no longer stale and just rejoins the matches.
export function withLingeringRows(
  candidates: Transaction[],
  matched: Transaction[],
  lingeringIds: Set<number>
): VisibleRows {
  if (lingeringIds.size === 0) return { rows: matched, staleIds: new Set() };

  const matchedIds = new Set(matched.map((t) => t['db/id']));
  const staleIds = new Set<number>();
  const rows = candidates.filter((t) => {
    const id = t['db/id'];
    if (matchedIds.has(id)) return true;
    if (lingeringIds.has(id)) {
      staleIds.add(id);
      return true;
    }
    return false;
  });

  return { rows, staleIds };
}
