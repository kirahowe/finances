import type { Category } from './api';
import { categoryMatchesFilter } from './categoryFiltering';

export interface CategoryNode {
  category: Category;
  /** 0 = top-level, 1 = child of a top-level category. */
  depth: number;
}

/** A selectable entry in the category dropdown; id null is "Uncategorized". */
export interface DropdownOption {
  id: number | null;
  name: string;
}

/**
 * A row in the grouped category dropdown. Header rows are non-selectable group
 * labels (a parent category that has children); option rows are selectable and
 * carry their index into the parallel `items` list that drives keyboard nav.
 */
export type CategoryDropdownRow =
  | { kind: 'header'; name: string; key: string }
  | { kind: 'option'; option: DropdownOption; itemIndex: number; depth: number };

export interface CategoryDropdownModel {
  /** Selectable options in keyboard-navigation order; items[0] is "Uncategorized". */
  items: DropdownOption[];
  /** Render model interleaving group headers with selectable option rows. */
  rows: CategoryDropdownRow[];
  /** Ids of the categories rendered as non-selectable group headers. */
  headerIds: Set<number>;
}

const UNCATEGORIZED: DropdownOption = { id: null, name: 'Uncategorized' };

const sortKey = (c: Category): number =>
  c['category/sort-order'] ?? Number.MAX_SAFE_INTEGER;

const parentIdOf = (c: Category): number | null =>
  c['category/parent']?.['db/id'] ?? null;

const bySortOrder = (a: Category, b: Category): number => sortKey(a) - sortKey(b);

/**
 * Flattens categories into render order: each top-level category followed by its
 * children, both sorted by sort-order. Children are emitted at depth 1.
 *
 * The single-level hierarchy is enforced server-side, so deeper nesting can't
 * occur. A parent reference pointing at a category that isn't present (e.g. a
 * parent removed in the same render pass) is treated as top-level.
 */
export function orderCategoriesHierarchically(categories: Category[]): CategoryNode[] {
  const present = new Set(categories.map((c) => c['db/id']));
  const childrenOf = new Map<number, Category[]>();
  const topLevel: Category[] = [];

  for (const c of categories) {
    const pid = parentIdOf(c);
    if (pid !== null && present.has(pid)) {
      const siblings = childrenOf.get(pid) ?? [];
      siblings.push(c);
      childrenOf.set(pid, siblings);
    } else {
      topLevel.push(c);
    }
  }

  const result: CategoryNode[] = [];
  for (const parent of [...topLevel].sort(bySortOrder)) {
    result.push({ category: parent, depth: 0 });
    const kids = childrenOf.get(parent['db/id']);
    if (kids) {
      for (const kid of [...kids].sort(bySortOrder)) {
        result.push({ category: kid, depth: 1 });
      }
    }
  }

  return result;
}

/**
 * The ids of categories that are group headers: a category is a header exactly
 * when at least one present category names it as parent. A parent reference to a
 * missing category is ignored (that child is treated as top-level).
 */
export function headerCategoryIds(categories: Category[]): Set<number> {
  const present = new Set(categories.map((c) => c['db/id']));
  const headers = new Set<number>();
  for (const c of categories) {
    const pid = parentIdOf(c);
    if (pid !== null && present.has(pid)) headers.add(pid);
  }
  return headers;
}

/**
 * Builds the grouped category dropdown model from the flat category list and the
 * current filter text. Top-level categories that have children become
 * non-selectable headers with their children indented beneath; childless
 * top-level categories and children are selectable. "Uncategorized" always leads
 * the selectable items so the category can be cleared.
 *
 * Filtering keeps parent context: an option is kept when its own name matches,
 * and a header is emitted only when at least one of its children is kept.
 */
export function buildCategoryDropdownRows(
  categories: Category[],
  filter: string
): CategoryDropdownModel {
  const nodes = orderCategoriesHierarchically(categories);
  const headers = headerCategoryIds(categories);

  const items: DropdownOption[] = [UNCATEGORIZED];
  const rows: CategoryDropdownRow[] = [
    { kind: 'option', option: UNCATEGORIZED, itemIndex: 0, depth: 0 },
  ];

  const pushOption = (category: Category, depth: number) => {
    const option: DropdownOption = {
      id: category['db/id'],
      name: category['category/name'],
    };
    rows.push({ kind: 'option', option, itemIndex: items.length, depth });
    items.push(option);
  };

  let pendingHeader: Category | null = null;
  let headerEmitted = false;

  for (const { category, depth } of nodes) {
    if (depth === 0 && headers.has(category['db/id'])) {
      // A group header: defer rendering it until a child survives the filter.
      pendingHeader = category;
      headerEmitted = false;
    } else if (depth === 1) {
      if (!categoryMatchesFilter(category, filter)) continue;
      if (pendingHeader && !headerEmitted) {
        rows.push({
          kind: 'header',
          name: pendingHeader['category/name'],
          key: `header-${pendingHeader['db/id']}`,
        });
        headerEmitted = true;
      }
      pushOption(category, 1);
    } else {
      // A childless top-level category: a selectable leaf, ends any open group.
      pendingHeader = null;
      headerEmitted = false;
      if (categoryMatchesFilter(category, filter)) pushOption(category, 0);
    }
  }

  return { items, rows, headerIds: headers };
}
