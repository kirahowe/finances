import type { Category } from './types';
import { categoryMatchesFilter, textMatchesFilter } from './categoryFiltering';

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
 * One selectable row in the grouped category dropdown. Parents and children are
 * both assignable; `depth` drives indentation and `isParent` drives the
 * group-label emphasis.
 */
export interface CategoryDropdownEntry {
  option: DropdownOption;
  /** 0 = top-level, 1 = child. */
  depth: number;
  /** Whether this category has children (rendered emphasized as a group label). */
  isParent: boolean;
}

export interface CategoryDropdownModel {
  /** All selectable rows in render/navigation order; entries[0] is "Uncategorized". */
  entries: CategoryDropdownEntry[];
  /** Index in `entries` to highlight after a filter change: the first row whose own
   *  name matches the filter, or 0 (Uncategorized) when nothing matches. */
  firstMatchIndex: number;
}

const UNCATEGORIZED: DropdownOption = { id: null, name: 'Uncategorized' };

const sortKey = (c: Category): number =>
  c['category/sort-order'] ?? Number.MAX_SAFE_INTEGER;

export const parentIdOf = (c: Category): number | null =>
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
 * The ids of categories that have at least one child present in the list — i.e.
 * the parent categories. A parent reference to a missing category is ignored.
 */
export function categoriesWithChildren(categories: Category[]): Set<number> {
  const present = new Set(categories.map((c) => c['db/id']));
  const parents = new Set<number>();
  for (const c of categories) {
    const pid = parentIdOf(c);
    if (pid !== null && present.has(pid)) parents.add(pid);
  }
  return parents;
}

const firstDirectMatchIndex = (entries: CategoryDropdownEntry[], filter: string): number => {
  if (!filter.trim()) return 0;
  const idx = entries.findIndex(
    (e, i) => i > 0 && e.option.id !== null && textMatchesFilter(e.option.name, filter)
  );
  return idx >= 0 ? idx : 0;
};

/**
 * Builds the grouped category dropdown model from the flat category list and the
 * current filter text. Every category is selectable — parents in their own right,
 * children indented beneath them — and "Uncategorized" always leads so the
 * category can be cleared.
 *
 * Filtering keeps parent context: a parent is shown when its own name matches OR
 * any of its children match (so a matching child is never orphaned from its
 * group); children are shown only when they match.
 */
export function buildCategoryDropdownModel(
  categories: Category[],
  filter: string
): CategoryDropdownModel {
  const nodes = orderCategoriesHierarchically(categories);
  const parents = categoriesWithChildren(categories);

  const entries: CategoryDropdownEntry[] = [
    { option: UNCATEGORIZED, depth: 0, isParent: false },
  ];

  const pushEntry = (category: Category, depth: number, isParent: boolean) => {
    entries.push({
      option: { id: category['db/id'], name: category['category/name'] },
      depth,
      isParent,
    });
  };

  let pendingParent: Category | null = null;
  let parentShown = false;

  for (const { category, depth } of nodes) {
    if (depth === 0 && parents.has(category['db/id'])) {
      // A parent: show it now if it matches, else defer until a child survives
      // the filter so the match keeps its group context.
      pendingParent = category;
      parentShown = false;
      if (categoryMatchesFilter(category, filter)) {
        pushEntry(category, 0, true);
        parentShown = true;
      }
    } else if (depth === 1) {
      if (!categoryMatchesFilter(category, filter)) continue;
      if (pendingParent && !parentShown) {
        pushEntry(pendingParent, 0, true);
        parentShown = true;
      }
      pushEntry(category, 1, false);
    } else {
      // A childless top-level category: a selectable leaf, ends any open group.
      pendingParent = null;
      parentShown = false;
      if (categoryMatchesFilter(category, filter)) pushEntry(category, 0, false);
    }
  }

  return { entries, firstMatchIndex: firstDirectMatchIndex(entries, filter) };
}
