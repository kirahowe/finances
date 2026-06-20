# Replicant + Datastar migration — Phase 3c–3d session handoff

**Written:** 2026-06-19. **Branch:** `spike/replicant-datastar`. **Commits:** `60358db..4f54b7e`.

This is the **deep record of the 3c–3d session** — the patterns it established, the
gotchas it hit and fixed, the file-level map, and exactly how to continue. Read order
for a fresh session:

1. **`replicant-datastar-progress.md`** — status-at-a-glance + the full roadmap (read first).
2. **this doc** — the *how/why* of everything built in 3c–3d, plus the new gotchas.
3. **`replicant-datastar-migration.md`** — the original decision/design brief (the why).
4. **`../spikes/replicant-datastar/FINDINGS.md`** — research + prototypes of every hard interaction.

Memory entry: `project_replicant_datastar_spike`.

---

## 1. What shipped this session

The `/` transactions table went from read-only-with-a-reviewed-toggle (end of 3b) to a
full editing workspace. Each unit is a commit + a real-Chromium `e2e/*.mjs` check.

| Commit | Phase | What it does | e2e |
|---|---|---|---|
| `60358db` | **3c** | inline description edit + category combobox (first Zag.js island) | `edit.mjs` 9, `combobox.mjs` 13 |
| `72f51f2` | **3d-1** | keyboard grid-nav island — navigation + a11y | `grid-nav.mjs` 19 |
| `e2e6182` | **3d-2** | keyboard editing (grid-nav drives the editors) | `grid-edit.mjs` 15 |
| `70b7465` | **3d-3a** | column visibility (picker + signals + CSS) | `columns.mjs` 9 |
| `41740a7` | **3d-3b** | client-side column sorting island | `sort.mjs` 12 |

(`c6dadda`, `e4de49f`, `4f54b7e` are the accompanying progress-doc updates.)

**Verified at wrap:** 122 e2e checks across 11 specs · backend **274 tests / 1197
assertions / 0 failures** · islands `tsc --noEmit` clean + vitest **64** + esbuild build clean.

---

## 2. Patterns established this session (new beyond the design brief)

These are the canonical implementations to copy for the remaining units.

### 2a. Inline description edit (pure Datastar, no island) — 3c
`web/pages/transactions.clj` `editable-description` + `desc-open-js`/`desc-keydown-js`/`desc-blur-js`.

- The cell renders **both** a view `<button.description-button>` and a hidden
  `<input.description-input>`, swapped by an `editing` class on the `.description-cell`
  (CSS in `transactions-table.css`). Opening never shifts the row.
- **Both** the button (`data-text`) and the input (`data-bind`) read the *same*
  `desc.tx<id>` signal, so the optimistic value is instant and the (hidden) view tracks
  it live. `$descOrig` snapshots the pre-edit value for Escape-revert.
- Enter/blur persist via `@put('/transactions/:id/description')` → `set-user-description!`.
- **The server echoes back here** (unlike the reviewed toggle): it `patch-signals!`s
  `desc.tx<id>` to the *effective* description, because the displayed value is derived
  (a cleared override falls back to the imported description, which the optimistic blank
  can't know). This is the sanctioned exception to "server patches only derived state".

### 2b. Category combobox (Zag.js vanilla island) — 3c
`islands/src/combobox.ts` + `web/pages/transactions.clj` `editable-category`/`category-options`.

- First real Zag widget. Reuses **`src/lib/categoryHierarchy.ts`** (`buildCategoryDropdownModel`)
  for the grouped/filtered/hierarchical option list; renders into the **existing
  `.category-dropdown-*` CSS classes** (carried over from React — zero new combobox CSS).
- **Floating root** `.category-dropdown.is-floating` (`position:fixed` + `z-index` in
  `category-dropdown.css`; only `left/top/width` set inline from the cell rect). This is
  the **reusable portal-positioning approach** the header funnels (3d, TODO) must reuse.
- **Category model travels in the DOM**, not JSON: a hidden `<ul id="category-options">`
  with `data-id/data-type/data-parent/data-sort` per `<li>` (Replicant escapes JSON in a
  `<script>` — gotcha §2). The island reconstructs `Category[]` from it.
- **Persistence seam:** each normal-row category cell has a hidden `<input data-bind="cat.tx<id>"
  data-on:change="@put('/transactions/:id/category')">`. The island sets that input's
  value + dispatches `input` then `change` → Datastar updates the signal then fires the
  `@put` → `update-category!` → server re-patches the toolbar counts (uncategorized moves).
- `cat.tx<id>` is **seeded as a string** (`category-signals`) — see gotcha 3a.

### 2c. Keyboard grid-nav island — 3d-1/3d-2
`islands/src/grid-nav.ts`, importing the already-vitest'd pure reducer `src/lib/gridNavigation.ts`.

- The island is only the imperative shell: `buildModel()` rebuilds the navigable grid
  from the DOM (each editable `<td>` carries `data-cell="txId:splitId|tx:col"`, matching
  the reducer's `cellKey`); `paint()` sets the `grid-cell-active` ring + roving tabindex
  + `aria-selected`; `focusActive()` moves DOM focus. Movement/mode logic is all in the reducer.
- **Editing reuses the 3c editors.** The island OPENS an editor (Enter/type-to-edit →
  clicks the description button, or dispatches `open-combobox`); the editors report back
  via a **bubbling `gridedit` DOM event** with `detail.action` `advance` (Enter: persist
  + walk down the column, re-opening the next editor) or `cancel` (Escape / combobox close:
  return focus to the cell). The island yields the keyboard whenever an editor owns focus
  and only claims Tab while editing (commit-then-move).
- `data-cell` is on **normal-row editable cells only**; split rows are NOT navigable yet
  (split editing = 3e).

### 2d. Column visibility (Datastar + CSS, no island) — 3d-3a
`web/pages/transactions.clj` `column-picker`/`cols-hide-class`/`hideable-columns`.

- Toolbar "Columns" dropdown (reuses `filter.css`'s `.filter-button`/`.filter-dropdown`),
  checkboxes `data-bind="cols.<id>"`. The table's `data-class` flips a `hide-<id>` class;
  CSS collapses that column **by fixed nth-child position** (source-order based, so hiding
  one never shifts the others). `__stop` on the toggle button (Datastar outside-click trap).
- **Only read-only columns are hideable** (date/account/institution/payee/amount), so this
  never interacts with grid-nav (which navigates description/category/reviewed). Hiding an
  *editable* column would need the reducer's `navigableColumns` filter + a grid rebuild —
  deliberately out of scope.

### 2e. Client-side sort island — 3d-3b
`islands/src/sort.ts` + server bakes sort keys + sortable headers.

- Clicking a sortable header reorders **row-groups** (a transaction's leader `<tr>` + any
  following split-child rows) in the DOM, cycling asc → desc → cleared, with an arrow
  indicator + `aria-sort`. **Chosen client-side** (not server `@get?sort=`) so it keeps
  every Datastar signal — honouring "never drop filters" ([[feedback_workspace_viewport_shell]]).
- Server bakes **numeric** sort keys (`data-sort-date` epoch, `data-sort-amount`) on leader
  rows; **string** columns are read from the rendered cell text via `th.cellIndex` (so
  arbitrary text can't break an attribute — gotcha §1).
- After a reorder the island fires **`grid-refresh`** on the scroll container; grid-nav
  rebuilds its model (`buildModel()`) so keyboard navigation follows the new order. The
  active cell, keyed by `RowKey`, survives (its `<td>` moved; `cellEls` re-points to it).

### 2f. Shared backend helpers
`web/routes.clj` `sse-respond` (the `->sse-response` + `on-open` + `close-sse!` triad,
now used by all hypermedia handlers). `web/pages/transactions.clj` `unsplit-signal-map`
(shared by `description-signals`/`category-signals`).

---

## 3. Gotchas discovered + fixed this session (append to migration §5)

1. **Category signal type-coercion.** Seeding `cat.tx<id>` with the numeric `db/id` makes
   Datastar type the signal numeric; clearing it (Uncategorized) then yields `0`, which is
   **truthy in Clojure**, so `update-category!` never cleared. **Fix:** stringify the ids
   in `category-signals` (`""` for none). Handler parses string→long, `""`→nil.
2. **Escape double-handling.** The description input's Escape reverts + refocuses the cell,
   so by the time the keydown bubbles to grid-nav's container handler, focus is on the
   `<td>` → grid-nav re-handled Escape and **cleared the active cell**. **Fix:** the
   description input's Enter/Escape handlers `evt.stopPropagation()` (Tab is NOT handled
   there, so it still bubbles to grid-nav for commit-and-move).
3. **Zag re-entrancy.** The combobox dispatched its `gridedit:advance` from *inside* Zag's
   `onValueChange`, so grid-nav synchronously started the **next** Zag machine while the old
   one was mid-transition → the next combobox silently didn't open. **Fix:** `combobox.ts`
   `close()` defers the `gridedit` dispatch via `queueMicrotask`.
4. **TS "script vs module" trap.** A `.ts` island with **no import/export** is treated as a
   global script, so `const scroll` collided with the ambient `window.scroll` and cascaded
   type errors. **Fix:** `export {};` at the top of `sort.ts` (the only island with no imports).
5. **TS closure narrowing.** A `const` narrowed by `if (x && y)` loses the narrowing inside a
   **deferred** nested function (an event listener) — use `!` there (`tbody!`, `thead!`).
   (Synchronously-called nested functions keep it.)
6. **Dev-loop ops.** Any `.clj` change needs an **e2e-server restart**; islands are static
   (rebuild + reload the page, no restart). Always `lsof -ti tcp:8099 | xargs kill -9` before
   restarting or you get `Address already in use` and the OLD code keeps serving (stale
   markup → confusing e2e failures). The zsh `path` reserved-array trap is still real.

---

## 4. File map (this session)

```
backend/src/finance_aggregator/web/
  pages/transactions.clj   ← MOST of the work. New: editable-description (+desc-*-js),
                             editable-category, category-options, grid-cell, sort-keys,
                             sortable th-cell, column-picker, cols-hide-class, hideable-columns,
                             description-signals/category-signals/unsplit-signal-map, grid-edit-js.
                             :islands ["combobox" "grid-nav" "sort"]; signals gained
                             desc/descOrig/cat/cols/colsOpen.
  routes.clj               ← sse-respond helper; set-description-handler, set-category-handler;
                             routes PUT /transactions/:id/{description,category}.
backend/resources/public/css/components/
  transactions-table.css   ← .editing swap, .th-sort-indicator, column-hide nth-child rules.
  category-dropdown.css     ← .category-dropdown.is-floating (fixed portal).
  grid-navigation.css       ← generalized .table.table-resizable → .table (active ring works now).
islands/
  src/combobox.ts          ← Zag combobox island (NEW).
  src/grid-nav.ts          ← grid-nav island (NEW).
  src/sort.ts              ← client sort island (NEW).
  build.mjs                ← entries: hello, combobox, grid-nav, sort.
  package.json             ← +@zag-js/combobox +@zag-js/vanilla.
e2e/                       ← NEW specs: edit, combobox, grid-nav, grid-edit, columns, sort.
```

Reused unchanged: `src/lib/gridNavigation.ts` (+test), `src/lib/categoryHierarchy.ts`,
`categoryFiltering.ts`, `types.ts`, `splitMath.ts`.

---

## 5. How to run & verify

JDK 25 isn't on a non-interactive PATH — set `JAVA_HOME`:
```bash
export JAVA_HOME=/opt/homebrew/Cellar/jabba/0.15.0/jdk/zulu@25.0.3/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
```
- **Backend tests:** `cd backend && clojure -M:test -m kaocha.runner`
- **Islands:** `cd islands && npm install && npm run build` (→ `backend/resources/public/js/islands/*.js`, gitignored); `npm run typecheck`; `npm test` (vitest).
- **Seeded server:** `cd backend && E2E_PORT=8099 E2E_DB_PATH=./data/e2e-3c.db clojure -M:e2e -m finance-aggregator.dev.e2e-server` then open `http://localhost:8099/?month=2025-01`. `POST /e2e/reset` re-seeds.
- **Browser checks:** `BASE_URL=http://localhost:8099 node e2e/<spec>.mjs` — full list + counts in the progress doc §2. `grid-edit.mjs` and the mutating specs reset/restore the seed.

Seed (2025-01): 10 transactions, 4 categories (flat, no nesting), **no splits** — so split
paths are untested by design (split editing = 3e). Counts: needs-review 10, total 10,
uncategorized 6, transfers-hidden 2.

---

## 6. Remaining work (resume here)

The progress doc §6 is the authoritative roadmap; this is the per-unit detail for what's left.

**Rest of Phase 3d** (independent of the editors):
- **Header-filter funnels** (gaps #2/#3) — per-column account/institution/category filter
  dropdowns. **Reuse the combobox's `.is-floating` fixed-portal** positioning to escape the
  table overflow. Datastar array signals + `data-show` per row (mind the checkbox-array
  `["","",""]` seeding — filter empties, gotcha §6). The Uncategorized chip currently uses
  one predicate; React had two by-sign tokens — revisit with the category funnel.
- **Column resize/auto-fit island** — port `src/lib/columnAutoSizing.ts` (already a pure
  module) as a pointer-event island writing `<col>` widths; **then switch the table from
  `.table` to `.table-resizable`** (fixed layout). NB: the grid-nav active-ring CSS was
  already generalized to `.table`, so it keeps working after the switch.
- **Pagination** — *likely unneeded* at seed size (<25 rows/month); confirm with the owner
  before building `page`/`pageSize`.
- **Lingering rows** (gap #1) — reviewing/categorizing a row in a filtered scope hides it
  immediately; React lingers until the next filter/sort/page reset. Key the scope/uncat
  `data-show` off a *snapshot* signal refreshed only on a filter/sort/page reset, not the
  live edit signals.
- **URL view-state** (gap #6, [[feedback_url_view_state]]) — reflect sort + filters + column
  visibility into the URL and read them back on load (a small `data-on-signal-patch` +
  History API helper). This also makes sort/filters **survive month navigation** (currently
  month nav is a full reload that resets the client signals).

**Phase 3e** — split editor modal (port `splitMath.ts`; then add `data-cell` to split rows +
make their category open the modal + give the inert split category button `aria-disabled`),
transfer match/review modals, category rollup pane, row-actions menu, active-filter chips.

**Phase 4** — CSV import wizard, bulk category modal (server-rendered preview —
[[feedback_bulk_confirm_ui]]), Plaid (vanilla Link SDK or remove — confirm), WebSocket sync
→ long-lived Datastar SSE stream.

**Phase 5** — delete `frontend/` (React, react-router, TanStack, downshift, zod, the JSON
client/overlay/write-behind/URL-codec plumbing). Keep CSS. Extract any surviving islands +
their vitest out of `frontend/`. Update DEVELOPMENT.md / README / Procfile.

---

## 7. Conventions in force (don't relitigate)

- **Backend authoritative** — server owns business logic + derived state; client reads
  computed fields, re-patches after writes ([[feedback_backend_authoritative]]).
- **Optimistic projection** — persisted per-row edits: optimistic overlay + write-behind;
  server patches only derived state (counts), **never echoes the edited cell** — *except*
  where the displayed value is itself derived (the description→effective fallback, §2a).
- **Never drop filters** ([[feedback_workspace_viewport_shell]]) — drove the client-side sort choice.
- **URL view-state, never localStorage** ([[feedback_url_view_state]]) — still TODO (gap #6).
- **No inline styles** except dynamic runtime rect coords (the combobox's `left/top/width`,
  the server `<col>` width estimate); everything static lives in CSS.
- **One service layer** — pages + JSON API call the same `db.*` reads/mutations.
- **Single-user-first** — hardcoded `test-user` is intentional.
- **Commit in logical chunks**, test-drive before each, use `gitp`; no PRs/pushes without
  confirmation. **TDD** — a Playwright/unit check with each unit.

---

## 8. Non-obvious state at wrap

- Working tree has only **pre-existing** untracked/modified files I did NOT touch:
  `doc/plans/feature-requests.md` (modified before this session) and
  `backend/src/finance_aggregator/auth.clj` (untracked identity seam). Leave them be.
- The built island bundles under `backend/resources/public/js/islands/` are **gitignored** —
  a fresh checkout must `cd islands && npm install && npm run build` before the e2e server
  serves working pages.
- React still runs on `:5173` (untouched, for before/after comparison). Flip + delete at Phase 5.
