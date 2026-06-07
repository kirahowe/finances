import type { Category } from './api';

export interface CategoryNode {
  category: Category;
  /** 0 = top-level, 1 = child of a top-level category. */
  depth: number;
}

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
