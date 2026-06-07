import type { CategoryType } from './categoryTypes';

export interface ParsedCategory {
  name: string;
  /** Name of the top-level parent this line falls under, or null if top-level. */
  parentName: string | null;
}

export interface BulkCategoryRow {
  tempId: string;
  name: string;
  type: CategoryType;
  /** tempId of this row's parent within the batch, or null if top-level. */
  parentTempId: string | null;
}

const BULLET = /^[-*+]\s*/;

const indentOf = (line: string): number => line.length - line.trimStart().length;

const stripBullet = (line: string): string => line.trimStart().replace(BULLET, '').trim();

/**
 * Parses a pasted, markdown-style list of categories into a flat, ordered list.
 *
 * Lines indented past the paste's baseline indentation become children of the
 * nearest preceding top-level line (single level only — deeper nesting collapses
 * onto the same top-level parent). Leading markdown bullets (-, *, +) are
 * stripped, and blank lines are ignored.
 */
export function parseCategoryList(text: string): ParsedCategory[] {
  const lines = text.split('\n').filter((l) => l.trim() !== '');
  if (lines.length === 0) return [];

  const baseIndent = Math.min(...lines.map(indentOf));
  const result: ParsedCategory[] = [];
  let currentParent: string | null = null;

  for (const line of lines) {
    const name = stripBullet(line);
    if (name === '') continue;

    if (indentOf(line) > baseIndent && currentParent !== null) {
      result.push({ name, parentName: currentParent });
    } else {
      result.push({ name, parentName: null });
      currentParent = name;
    }
  }

  return result;
}

/**
 * Parses pasted text into editable preview rows. Each row gets a stable tempId,
 * and child rows link to their parent by tempId (so renaming a parent in the
 * preview never breaks the link). Every row defaults to `defaultType`.
 */
export function buildBulkRows(
  text: string,
  defaultType: CategoryType = 'expense'
): BulkCategoryRow[] {
  const parsed = parseCategoryList(text);
  let currentParentTempId: string | null = null;

  return parsed.map((p, index) => {
    const tempId = `t${index}`;
    let parentTempId: string | null = null;

    if (p.parentName === null) {
      currentParentTempId = tempId;
    } else {
      parentTempId = currentParentTempId;
    }

    return { tempId, name: p.name, type: defaultType, parentTempId };
  });
}
