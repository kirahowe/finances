// Category combobox island — the first real Zag.js (vanilla adapter) widget.
//
// Zag's combobox state machine owns the accessible behaviour: role=combobox/listbox/
// option, aria-activedescendant/expanded, typeahead, scroll-into-view, dismissable /
// interact-outside, and screen-reader announcements. We own only the glue: reuse the
// shared `buildCategoryDropdownModel` for the grouped, filtered, hierarchy-aware
// option list, render it into the existing `.category-dropdown-*` styles, position a
// `position:fixed` floating root so the list escapes the table's overflow, and commit
// the chosen category through a caller-supplied callback (see CommitMode below).
//
// TWO COMMIT MODES, ONE CORE
// --------------------------
// `openCombobox()` is the reusable core: it builds the floating root, runs the Zag
// machine, drives the typeahead highlight, and on selection invokes `onCommit(id)`
// with the chosen category id (number) or null (Uncategorized). It does NOT know how
// the selection is persisted — that's the caller's job:
//
//   (a) Grid path (this file, bottom): opens on a `.category-button.combo-cell` click
//       or the `open-combobox` event. Its onCommit writes the chosen id into the cell's
//       sibling hidden input and dispatches input+change, so Datastar updates $catValue
//       and @put's /transactions/:id/category. Keyboard select hands off to grid-nav
//       (advance/cancel via the `gridedit` event). UNCHANGED contract — see commit().
//
//   (b) Split editor (split-editor.ts): calls `window.__openCombobox` (NOT an import —
//       see the window-hook note at the bottom) and passes an onCommit that updates the
//       island's LOCAL row state + the row button label, with NO @put (the split isn't
//       persisted until "Save split").
//
//   (c) Form-modal triggers (this file, bottom): a `.form-combo-trigger` button opens
//       either the category mode or the generic FLAT-LIST mode (OpenOptions.options —
//       a plain {id, label} list, e.g. the add-transaction modal's accounts) and
//       commits through a hidden courier input named by its data attributes.
//
// Server seam (transactions.clj `editable-category`): each normal-row category cell is
// a `.category-button.combo-cell` (the view) plus a hidden input bound to `$catValue`.

import * as combobox from '@zag-js/combobox';
import { normalizeProps, spreadProps, VanillaMachine } from '@zag-js/vanilla';
import type { Category } from './lib/types';
import {
  buildCategoryDropdownModel,
  type CategoryDropdownEntry,
} from './lib/categoryHierarchy';
import { filterFlatOptions, type FlatOption } from './lib/flatOptions';

// A selectable combobox item. `code` is Zag's opaque value (a real value is required,
// so "Uncategorized" — id null — uses a sentinel mapped back to null on commit).
interface Item {
  code: string;
  label: string;
  depth: number;
  isParent: boolean;
}

const UNCAT_CODE = '__uncat__';

/** Map an Item's opaque `code` back to a category id (null = Uncategorized). */
const codeToCategoryId = (code: string): number | null =>
  code === UNCAT_CODE ? null : Number(code);

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

// The filtered option list plus the value Zag should highlight: the closest
// (typeahead) match, so Enter selects it without arrowing. Zag's own
// `inputBehavior: 'autohighlight'` highlights the collection's *first* value,
// which is always "Uncategorized" here — so we drive the highlight ourselves
// from the model's `firstMatchIndex`.
interface FilterResult {
  items: Item[];
  /** Value (`Item.code`) of the best match to highlight, or null for none. */
  highlight: string | null;
}

const itemsForFilter = (filter: string): FilterResult => {
  const { entries, firstMatchIndex } = buildCategoryDropdownModel(categories, filter);
  const items = entries.map(entryToItem);
  // firstMatchIndex is 0 (Uncategorized) when nothing matches; only highlight a
  // real match so an empty query / no-match doesn't preselect Uncategorized.
  const highlight = firstMatchIndex > 0 ? (items[firstMatchIndex]?.code ?? null) : null;
  return { items, highlight };
};

// Flat-list mode's filter: the caller-supplied {id, label} options filtered by
// case-insensitive substring on label (lib/flatOptions — pure, vitest-covered), no
// hierarchy and no Uncategorized sentinel. The filtered list contains only matches,
// so the highlight is simply its first row — and only for a non-blank query,
// mirroring the category mode's rule that an empty filter preselects nothing.
const flatItemsForFilter = (options: FlatOption[], filter: string): FilterResult => {
  const items: Item[] = filterFlatOptions(options, filter).map((o) => ({
    code: String(o.id),
    label: o.label,
    depth: 0,
    isParent: false,
  }));
  return { items, highlight: filter.trim() && items.length > 0 ? items[0].code : null };
};

const collectionOf = (items: Item[]) =>
  combobox.collection({
    items,
    itemToValue: (i) => i.code,
    itemToString: (i) => i.label,
  });

// How a closing combobox should hand off focus once it's gone. The grid path
// dispatches a `gridedit` event so grid-nav can `advance` (walk down the column) or
// `cancel` (refocus the cell); other callers (the split editor) ignore the hand-off.
type CloseAction = 'advance' | 'cancel' | null;

/**
 * Options for the reusable combobox core. Both commit modes share this surface.
 */
interface OpenOptions {
  /** Element the floating root aligns over (its rect seeds left/top/width). */
  anchor: HTMLElement;
  /** Text shown in the empty input (the current category name). */
  placeholder: string;
  /** A typed seed to pre-filter with (type-to-edit from the grid); null = open empty. */
  seed?: string | null;
  /** Extra class on the floating root — the grid passes `is-in-cell` so its input goes flush
   *  (the active-cell ring is the outline); the split modal omits it (the input owns a border). */
  rootClass?: string;
  /** Flat-list mode: when supplied, the item list is these options filtered by
   *  case-insensitive substring on label — no hierarchy, no Uncategorized sentinel —
   *  and onCommit receives the chosen option's id (never null). Omitted = the default
   *  category mode, which is completely unchanged. */
  options?: FlatOption[];
  /** Called with the chosen id when a value is picked: a category id (null =
   *  Uncategorized) in category mode, the option's id in flat-list mode. */
  onCommit: (categoryId: number | null, label: string) => void;
  /**
   * Called once the floating root is torn down. `action` is the keyboard/click
   * hand-off the grid path forwards to grid-nav; non-grid callers can ignore it.
   * `committedViaKeyboard` is true when the close followed an Enter-selection.
   */
  onClose?: (action: CloseAction, committedViaKeyboard: boolean) => void;
}

// The one live combobox (only ever one open at a time). `teardown` removes the DOM +
// stops the machine; `onClose` is the caller's post-teardown hand-off.
let current: {
  teardown: () => void;
  onClose?: OpenOptions['onClose'];
} | null = null;

// Tracks the live machine so the deferred applyHighlight only fires for the open one.
let currentMachine: VanillaMachine<any> | null = null;

/** Close any open combobox. `action` rides through to the caller's onClose hand-off. */
function close(action: CloseAction = null): void {
  if (!current) return;
  const { teardown, onClose } = current;
  // Snapshot before clearing: teardown stops the machine, which can re-enter close()
  // via onOpenChange — guard against double-firing by nulling `current` first.
  const committed = committedViaKeyboard;
  current = null;
  teardown();
  onClose?.(action, committed);
}

// Whether the in-flight selection was made by keyboard (Enter) rather than a click —
// keyboard select advances down the column (like the description editor), a click just
// closes. Set by a capture-phase keydown listener (before Zag selects).
let committedViaKeyboard = false;

/**
 * Open the category typeahead over `anchor`. Reusable across commit modes: it owns the
 * Zag machine, rendering, and typeahead highlight; the caller owns persistence via
 * `onCommit` and any focus hand-off via `onClose`.
 */
export function openCombobox(opts: OpenOptions): void {
  close();
  committedViaKeyboard = false;
  const { anchor, placeholder, seed, onCommit } = opts;
  // One filter fn per mode: the flat list when `options` is supplied, else the category model.
  const filterItems = (filter: string): FilterResult =>
    opts.options ? flatItemsForFilter(opts.options, filter) : itemsForFilter(filter);
  let { items, highlight } = filterItems(seed ?? '');

  const root = document.createElement('div');
  // `.is-floating` (position:fixed + z-index, in category-dropdown.css) floats this
  // container above the table's horizontal-scroll overflow; the list stays absolute
  // within it (existing .category-dropdown-list styles), so no portal variant is needed.
  root.className = `category-dropdown is-floating${opts.rootClass ? ` ${opts.rootClass}` : ''}`;
  root.innerHTML =
    `<input class="category-dropdown-input" aria-label="Category"/>` +
    `<ul class="category-dropdown-list"></ul>`;
  // Property assignment, not innerHTML interpolation — a category name with a quote/
  // angle bracket would otherwise break the attribute (or inject markup).
  root.querySelector<HTMLInputElement>('.category-dropdown-input')!.placeholder = placeholder;
  // Only the runtime rect coordinates are dynamic, so only these are set inline.
  const r = anchor.getBoundingClientRect();
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
    // We don't use `inputBehavior: 'autohighlight'`: it highlights the
    // collection's first value, which is always "Uncategorized" here. Instead we
    // re-filter on each keystroke and explicitly highlight the model's best
    // (typeahead) match via `applyHighlight` below, so Enter selects it.
    onOpenChange(d: any) {
      if (!d.open) close('cancel');
    },
    onInputValueChange({ inputValue }: any) {
      ({ items, highlight } = filterItems(inputValue ?? ''));
      applyHighlight();
    },
    onValueChange({ value }: any) {
      const code = value[0];
      if (code == null) return;
      const item = items.find((i) => i.code === code);
      // Keyboard select walks down the column (re-opens the next combobox); a click
      // select just returns focus to the anchor — the caller decides via onClose.
      const action: CloseAction = committedViaKeyboard ? 'advance' : 'cancel';
      // Close first (tears down this machine) so a re-opening caller (grid advance)
      // isn't started re-entrantly; the commit itself is synchronous DOM/state work.
      close(action);
      if (item) onCommit(codeToCategoryId(code), item.label);
    },
  });

  // Highlight the best match in the freshly-filtered collection. Deferred a
  // microtask: `onInputValueChange` fires mid-INPUT.CHANGE transition (which
  // clears the highlight and rebuilds the collection), so we set the highlight
  // after that settles. Setting `null` is a no-op — we leave Zag's cleared
  // highlight as-is when nothing matches.
  const applyHighlight = () => {
    const value = highlight;
    if (value == null) return;
    queueMicrotask(() => {
      if (currentMachine === machine) {
        combobox.connect(machine.service, normalizeProps).setHighlightValue(value);
      }
    });
  };

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

  // A pointer interaction (clicking an option) means the next selection is a CLICK, not a
  // keyboard commit — clear the flag in the capture phase, before Zag's click-select fires
  // its onValueChange. Without this, an Enter that didn't select (e.g. no highlighted match)
  // leaves committedViaKeyboard stuck true, so a subsequent click is mis-routed as a
  // keyboard "advance" (opens the next row's combobox). See combobox.ts finding #14.
  root.addEventListener('pointerdown', () => { committedViaKeyboard = false; }, true);

  const input = root.querySelector<HTMLInputElement>('.category-dropdown-input')!;
  // Capture phase (before Zag) so the flag is set before a selection fires, and Tab is
  // claimed before Zag acts on it.
  input.addEventListener(
    'keydown',
    (e) => {
      if (e.key === 'Enter') committedViaKeyboard = true;
      if (e.key === 'Tab') {
        // Close and let the caller move on. The grid path's onClose forwards a
        // synthetic Tab from the cell to grid-nav; other callers just close.
        e.preventDefault();
        const shiftKey = e.shiftKey;
        // Stash the anchor: close() nulls `current`, but we need it for the hand-off.
        const handoff = anchor;
        close(null);
        // The grid path keys its Tab hand-off off the anchor; re-dispatch here so
        // grid-nav advances exactly as before. Non-grid anchors simply ignore it.
        handoff.dispatchEvent(
          new KeyboardEvent('keydown', { key: 'Tab', shiftKey, bubbles: true })
        );
      }
    },
    true
  );
  // A typed seed (type-to-edit from the grid) opens the list already filtered.
  if (seed != null) {
    input.value = seed;
    input.dispatchEvent(new Event('input', { bubbles: true }));
  }
  input.focus();

  // Flag the anchor so its at-rest/hover border is muted while the floating input sits
  // over it — the floating root is a <body> sibling positioned over the anchor, so
  // without this the anchor's own (hover) border shows through behind the input's accent
  // border as a doubled outline. CSS keys off `.combobox-open` (category-button.css /
  // split-modal.css); the floating input owns the single border + focus ring.
  anchor.classList.add('combobox-open');

  // A server-authoritative edit can morph the tbody WHILE this combobox is open over the anchor:
  // the Enter-advance opens the next row's combobox before the previous row's category @put
  // response lands, and that response re-renders the whole tbody. idiomorph morphs the id-matched
  // anchor button in place and resets its class to the server's value, stripping `combobox-open`
  // — so the button text reappears behind the (transparent) floating input as doubled text.
  // Re-apply the flag if a morph removes it; disconnected in teardown (before we remove it).
  const anchorGuard = new MutationObserver(() => {
    if (!anchor.classList.contains('combobox-open')) anchor.classList.add('combobox-open');
  });
  anchorGuard.observe(anchor, { attributes: true, attributeFilter: ['class'] });

  // teardown: stop the machine and remove the floating root. close() invokes this then
  // the caller's onClose; it must be idempotent-safe because onOpenChange may re-enter.
  const teardown = () => {
    // Clear before stop() so a deferred applyHighlight (queued mid-transition) sees this
    // machine is no longer current and skips setHighlightValue on a stopped machine.
    if (currentMachine === machine) currentMachine = null;
    anchorGuard.disconnect();
    anchor.classList.remove('combobox-open');
    machine.stop();
    root.remove();
  };
  currentMachine = machine;
  current = { teardown, onClose: opts.onClose };
}

// --- Grid commit mode -------------------------------------------------------
// Opens on a `.category-button.combo-cell` and persists through Datastar, exactly as
// before: optimistic button text, write the chosen id into the cell's sibling hidden
// input, dispatch input+change (so $catValue updates and @put fires). Keyboard select
// hands off to grid-nav via the `gridedit` event; a click just refocuses the cell.

function openGrid(cell: HTMLElement, seed?: string | null): void {
  // The current category name leads as the placeholder (the input opens empty so the
  // first keystroke filters), mirroring the React dropdown.
  const placeholder = cell.textContent?.trim() || 'Uncategorized';
  openCombobox({
    anchor: cell,
    placeholder,
    seed,
    // The grid cell already shows the active-cell ring; the input goes flush so the ring is
    // the single outline (no second border that jumps when the combobox opens).
    rootClass: 'is-in-cell',
    onCommit(categoryId, label) {
      // Optimistic: the view button shows the new category immediately.
      cell.textContent = label;
      // Persist through Datastar: write the chosen id into the hidden bound courier input.
      // Resolve it by its stable id (cat-courier-tx<id>, derived from the cell button's
      // own cat-view-tx<id>) so a tbody morph that lands mid-advance can't leave us writing
      // to a stale/replaced sibling node; fall back to the sibling lookup if the id is
      // absent (the split editor opens this core with a plain anchor). An empty string
      // clears the category (Uncategorized → categoryId null).
      const txId = cell.id.startsWith('cat-view-tx') ? cell.id.slice('cat-view-tx'.length) : '';
      const hidden =
        (txId ? (document.getElementById(`cat-courier-tx${txId}`) as HTMLInputElement | null) : null) ||
        cell.parentElement?.querySelector<HTMLInputElement>("input[type='hidden']");
      if (hidden) {
        hidden.value = categoryId === null ? '' : String(categoryId);
        hidden.dispatchEvent(new Event('input', { bubbles: true }));
        hidden.dispatchEvent(new Event('change', { bubbles: true }));
      }
    },
    onClose(action) {
      // Defer the hand-off: onClose runs from inside Zag's onValueChange/onOpenChange,
      // and `advance` makes grid-nav start the NEXT combobox — a microtask lets this
      // machine's transition finish first so the new machine isn't started re-entrantly.
      if (action) {
        queueMicrotask(() =>
          cell.dispatchEvent(new CustomEvent('gridedit', { detail: { action }, bubbles: true }))
        );
      }
    },
  });
}

document.addEventListener('click', (e) => {
  const btn = (e.target as HTMLElement).closest<HTMLElement>('.category-button.combo-cell');
  if (btn) openGrid(btn);
});

// The grid-nav island opens the combobox by keyboard (Enter / type-to-edit on a
// category cell), passing the category button and any typed seed.
document.addEventListener('open-combobox', (e) => {
  const { cell, seed } = (e as CustomEvent).detail;
  openGrid(cell, seed);
});

// --- Form-modal trigger mode -------------------------------------------------
// A `.form-combo-trigger` button (the add-transaction modal's Account / Category
// fields) declares what it opens via data attributes:
//   data-combo="account"  → flat-list mode over the modal's hidden #account-options
//                           list (data-id attrs + name text — the DOM-carried model,
//                           mirroring #category-options);
//   data-combo="category" → the standard category mode;
//   data-combo-courier    → the id of the hidden courier input the committed id is
//                           written into (dispatching input+change so its
//                           data-on:change sets the Datastar signal — the same
//                           courier pattern as the grid's editable-category).
// The trigger's `.form-combo-label` span is updated optimistically on commit, and
// onClose refocuses the trigger: the floating input is a <body> child OUTSIDE the
// modal's focus trap, so without the refocus a close would drop focus to <body>.

function readAccountOptions(): FlatOption[] {
  return [...document.querySelectorAll<HTMLLIElement>('#account-options li')].map((li) => ({
    id: Number(li.dataset.id),
    label: (li.textContent ?? '').trim(),
  }));
}

function openFormTrigger(btn: HTMLElement): void {
  const label = btn.querySelector<HTMLElement>('.form-combo-label');
  openCombobox({
    anchor: btn,
    placeholder: label?.textContent?.trim() || '',
    options: btn.dataset.combo === 'account' ? readAccountOptions() : undefined,
    onCommit(id, itemLabel) {
      if (label) label.textContent = itemLabel;
      const courier = btn.dataset.comboCourier
        ? (document.getElementById(btn.dataset.comboCourier) as HTMLInputElement | null)
        : null;
      if (courier) {
        courier.value = id === null ? '' : String(id);
        courier.dispatchEvent(new Event('input', { bubbles: true }));
        courier.dispatchEvent(new Event('change', { bubbles: true }));
      }
    },
    onClose() {
      btn.focus();
    },
  });
}

document.addEventListener('click', (e) => {
  const btn = (e.target as HTMLElement).closest<HTMLElement>('.form-combo-trigger');
  if (btn) openFormTrigger(btn);
});

// Expose the reusable core on `window` (the established island-interop pattern, like
// window.__resetWidths / __syncUrl) so split-editor.ts can open the SAME combobox WITHOUT
// importing this module — which would make esbuild inline a second ~99 kB copy of Zag into
// split-editor.js and run this file's grid listeners twice. With the window hook, combobox.js
// is the single bundle that ships Zag and owns the one live `current`/`currentMachine` state.
declare global {
  interface Window {
    __openCombobox?: (opts: OpenOptions) => void;
  }
}
window.__openCombobox = openCombobox;
