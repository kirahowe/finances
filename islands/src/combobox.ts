// Category combobox island — the first real Zag.js (vanilla adapter) widget.
//
// Zag's combobox state machine owns the accessible behaviour: role=combobox/listbox/
// option, aria-activedescendant/expanded, typeahead, scroll-into-view, dismissable /
// interact-outside, and screen-reader announcements. We own only the glue: reuse the
// shared `buildCategoryDropdownModel` for the grouped, filtered, hierarchy-aware
// option list, render it into the existing `.category-dropdown-*` styles, position a
// `position:fixed` floating root so the list escapes the table's overflow, and persist
// the chosen category through Datastar (see commit()).
//
// Server seam (transactions.clj `editable-category`): each normal-row category cell is
// a `.category-button.combo-cell` (the view, opened here) plus a hidden input bound to
// `cat.tx<id>`. On commit we set the button text optimistically and write the chosen id
// into that hidden input, dispatching input+change so Datastar updates the signal and
// fires `@put('/transactions/:id/category')`. The server persists + re-patches the
// toolbar counts. Split-row categories (no `.combo-cell`) are ignored here — they open
// the split modal in 3e.

import * as combobox from '@zag-js/combobox';
import { normalizeProps, spreadProps, VanillaMachine } from '@zag-js/vanilla';
import type { Category } from './lib/types';
import {
  buildCategoryDropdownModel,
  type CategoryDropdownEntry,
} from './lib/categoryHierarchy';

// A selectable combobox item. `code` is Zag's opaque value (a real value is required,
// so "Uncategorized" — id null — uses a sentinel mapped back to null on commit).
interface Item {
  code: string;
  label: string;
  depth: number;
  isParent: boolean;
}

const UNCAT_CODE = '__uncat__';

const entryToItem = (e: CategoryDropdownEntry): Item => ({
  code: e.option.id === null ? UNCAT_CODE : String(e.option.id),
  label: e.option.name,
  depth: e.depth,
  isParent: e.isParent,
});

// Reconstruct the Category[] from the hidden #category-options list the server renders
// (id/parent/sort-order as data-attrs) — Replicant escapes JSON in a <script>, so the
// model travels in the DOM (migration gotcha §2).
function readCategories(): Category[] {
  return [...document.querySelectorAll<HTMLLIElement>('#category-options li')].map((li) => {
    const id = Number(li.dataset.id);
    const parent = li.dataset.parent;
    const sort = li.dataset.sort;
    const cat: Category = {
      'db/id': id,
      'category/name': (li.textContent ?? '').trim(),
      'category/type': (li.dataset.type as Category['category/type']) ?? 'expense',
    };
    if (parent) cat['category/parent'] = { 'db/id': Number(parent) };
    if (sort) cat['category/sort-order'] = Number(sort);
    return cat;
  });
}

const categories = readCategories();

const itemsForFilter = (filter: string): Item[] =>
  buildCategoryDropdownModel(categories, filter).entries.map(entryToItem);

const collectionOf = (items: Item[]) =>
  combobox.collection({
    items,
    itemToValue: (i) => i.code,
    itemToString: (i) => i.label,
  });

let current: { root: HTMLElement; machine: VanillaMachine<any>; cell: HTMLElement } | null = null;

function open(cell: HTMLElement) {
  close();
  let items = itemsForFilter('');

  // The current category name leads as the placeholder (the input opens empty so the
  // first keystroke filters), mirroring the React dropdown.
  const placeholder = cell.textContent?.trim() || 'Uncategorized';

  const root = document.createElement('div');
  // `.is-floating` (position:fixed + z-index, in category-dropdown.css) floats this
  // container above the table's horizontal-scroll overflow; the list stays absolute
  // within it (existing .category-dropdown-list styles), so no portal variant is needed.
  root.className = 'category-dropdown is-floating';
  root.innerHTML =
    `<input class="category-dropdown-input" aria-label="Category"/>` +
    `<ul class="category-dropdown-list"></ul>`;
  // Property assignment, not innerHTML interpolation — a category name with a quote/
  // angle bracket would otherwise break the attribute (or inject markup).
  root.querySelector<HTMLInputElement>('.category-dropdown-input')!.placeholder = placeholder;
  // Only the runtime rect coordinates are dynamic, so only these are set inline.
  const r = cell.getBoundingClientRect();
  root.style.left = `${r.left}px`;
  root.style.top = `${r.top}px`;
  root.style.width = `${Math.max(r.width, 180)}px`;
  document.body.appendChild(root);

  const machine = new VanillaMachine(combobox.machine, {
    id: 'category-combobox',
    get collection() {
      return collectionOf(items);
    },
    open: true,
    openOnClick: true,
    inputBehavior: 'autohighlight',
    onOpenChange(d: any) {
      if (!d.open) close(true);
    },
    onInputValueChange({ inputValue }: any) {
      items = itemsForFilter(inputValue ?? '');
    },
    onValueChange({ value }: any) {
      if (value[0] != null) commit(cell, value[0], items);
    },
  });

  const get = (sel: string) => root.querySelector<HTMLElement>(sel)!;
  const render = () => {
    const api = combobox.connect(machine.service, normalizeProps);
    spreadProps(root, api.getRootProps(), machine.scope.id);
    const input = get('.category-dropdown-input');
    spreadProps(input, api.getInputProps(), machine.scope.id);
    const list = get('.category-dropdown-list');
    spreadProps(list, api.getContentProps(), machine.scope.id);
    list.innerHTML = '';
    for (const item of items) {
      const li = document.createElement('li');
      const cls = ['category-dropdown-item'];
      if (item.depth > 0) cls.push('category-dropdown-item--child');
      if (item.isParent) cls.push('category-dropdown-item--parent');
      if (api.getItemState({ item }).highlighted) cls.push('highlighted');
      li.className = cls.join(' ');
      li.textContent = item.label;
      spreadProps(li, api.getItemProps({ item }), machine.scope.id);
      list.appendChild(li);
    }
  };
  machine.subscribe(render);
  machine.start();
  render();

  const input = get('.category-dropdown-input');
  // Tab hands focus back to the cell (the grid-nav island in 3d takes it from there).
  input.addEventListener(
    'keydown',
    (e) => {
      if (e.key === 'Tab') {
        e.preventDefault();
        close(true);
      }
    },
    true
  );
  input.focus();
  current = { root, machine, cell };
}

function commit(cell: HTMLElement, code: string, items: Item[]) {
  const item = items.find((i) => i.code === code);
  if (!item) return;
  const categoryId = code === UNCAT_CODE ? '' : code;
  // Optimistic: the view button shows the new category immediately.
  cell.textContent = item.label;
  // Persist through Datastar: write the chosen id into the hidden bound input, then
  // fire input (so data-bind updates `$cat.tx<id>`) and change (so data-on:change fires
  // the @put with the already-fresh signal).
  const hidden = cell.parentElement?.querySelector<HTMLInputElement>("input[type='hidden']");
  if (hidden) {
    hidden.value = categoryId;
    hidden.dispatchEvent(new Event('input', { bubbles: true }));
    hidden.dispatchEvent(new Event('change', { bubbles: true }));
  }
  close(true);
}

function close(refocus?: boolean) {
  if (!current) return;
  const { root, machine, cell } = current;
  machine.stop();
  root.remove();
  current = null;
  if (refocus) cell.focus();
}

document.addEventListener('click', (e) => {
  const btn = (e.target as HTMLElement).closest<HTMLElement>('.category-button.combo-cell');
  if (btn) open(btn);
});
