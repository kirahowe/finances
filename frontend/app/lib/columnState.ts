import type { VisibilityState, ColumnSizingState } from '@tanstack/react-table';
import { MAX_COLUMN_WIDTH } from './transactionColumns';

// URL serialization for the transactions table's column view state, mirroring
// sortingState.ts / filterState.ts so all view state stays shareable in the URL.
// Only departures from the default are stored, so an untouched table keeps a
// clean URL: a hidden-columns list and any user-resized widths.

/** Hidden columns as a comma list of ids. All-visible serializes to "". */
export function serializeColumnVisibility(visibility: VisibilityState): string {
  return Object.entries(visibility)
    .filter(([, visible]) => visible === false)
    .map(([id]) => id)
    .join(',');
}

// `allowedIds`, when given, restricts which column ids may be hidden — a hand-edited
// URL can otherwise hide a structural column (e.g. the row-actions caret) that the
// picker offers no way to bring back. Unknown ids are ignored.
export function parseColumnVisibility(
  param: string | null | undefined,
  allowedIds?: readonly string[]
): VisibilityState {
  if (!param || param.trim() === '') return {};
  const allowed = allowedIds ? new Set(allowedIds) : null;
  const result: VisibilityState = {};
  for (const id of param.split(',')) {
    const trimmed = id.trim();
    if (trimmed && (!allowed || allowed.has(trimmed))) result[trimmed] = false;
  }
  return result;
}

/** Resized widths as "id:width" pairs. Untouched columns serialize to "". */
export function serializeColumnSizing(sizing: ColumnSizingState): string {
  return Object.entries(sizing)
    .map(([id, width]) => `${id}:${Math.round(width)}`)
    .join(',');
}

export function parseColumnSizing(param: string | null | undefined): ColumnSizingState {
  if (!param || param.trim() === '') return {};
  const result: ColumnSizingState = {};
  for (const part of param.split(',')) {
    const [id, raw] = part.split(':');
    const width = Number(raw);
    if (id?.trim() && Number.isFinite(width) && width > 0) {
      // Clamp so a hand-edited URL can't force an absurdly wide column (the same cap
      // a double-click auto-fit honours); the table's minSize handles the low end.
      result[id.trim()] = Math.min(width, MAX_COLUMN_WIDTH);
    }
  }
  return result;
}
