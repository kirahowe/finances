# Replicant + Datastar migration — implementation progress / resume-here

**Last updated:** 2026-06-19. **Branch:** `spike/replicant-datastar`.

This is the **living status + resume document** for the React→Replicant/Datastar
migration. The *design/decision* is in `replicant-datastar-migration.md` (read it
for the why, the deps, the full gotchas list, and the phased plan); the *research*
is in `../spikes/replicant-datastar/FINDINGS.md`; the **deep record of the 3c–3d
session** (every pattern built, the new gotchas + fixes, the file map, per-unit resume
notes) is in **`replicant-datastar-3c-3d-handoff.md`** — read that before continuing
3d/3e. **This doc says where we are and how to continue.** Memory entry:
`project_replicant_datastar_spike`.

---

## 1. Status at a glance

Phases **0–3c done; 3d DONE** (grid-nav, keyboard editing, column visibility, sorting,
header-filter funnels, column resize/auto-fit, lingering rows, URL view-state, pagination)
— on `spike/replicant-datastar`. Backend suite green throughout (**274 tests / 1197
assertions / 0 failures**); islands `tsc` clean + **64** vitest. Every UI unit has a
real-Chromium `e2e/*.mjs` check — **16 specs / 200 checks** green. Next up: **Phase 3e**
(split editor + transfer modals + rollup + row actions).

| Phase | What shipped | Check |
|---|---|---|
| 0 | http-kit 2.8.0 → **2.9.0-beta3** | suite green before/after + server smoke |
| 1 | `web/` view layer; `islands/` toolchain (esbuild+vitest, 6 pure modules + tests); vendored `datastar.js` v1.0.2; full design-system CSS carried over; static MIME fix | `e2e/scaffold.mjs` **6/6** |
| 2 | `/setup` account list (read-only); shared reads → `db/accounts`, `db/stats` | `e2e/setup.mjs` **8/8** |
| 3a | `/` transactions table (read-only): 9 cols, split rows, signed CAD amounts, transfer ⇄ status, month nav; shared read `db.transactions/list-for-month`+`list-all` | `e2e/transactions.mjs` **12/12** |
| 3b | optimistic **reviewed toggle** (signal + write-behind); client filters (**search**, **Needs-review/All** scope, **Uncategorized**/**Hide-transfers** chips); server-authoritative counts patched by id | `e2e/reviewed.mjs` **5/5**, `e2e/filters.mjs` **14/14** |
| 3c | **inline description edit** (class-swap, optimistic via `data-text`/`data-bind`, `@put`→`set-user-description!`, signal reconciled to effective desc); **category combobox** (first **Zag.js vanilla** island, reuses `buildCategoryDropdownModel`, `position:fixed` floating root, `@put`→`update-category!`→counts patch) | `e2e/edit.mjs` **9/9**, `e2e/combobox.mjs` **13/13** |
| 3d-1 | **keyboard grid-nav island** (`grid-nav.ts`, imports the ported reducer): arrows/Tab/Home/End/click move the active cell, Space toggles reviewed, `role=grid/row/gridcell` + roving tabindex + active ring; yields to open editors. `data-cell` on the normal-row editable cells | `e2e/grid-nav.mjs` **19/19** |
| 3d-2 | **keyboard editing**: Enter/type-to-edit open the editor, Enter walks down the column (advance), Tab commits+moves, combobox opens by keyboard + advances on select. grid-nav drives the 3c editors via a bubbling `gridedit` event (`advance`/`cancel`) | `e2e/grid-edit.mjs` **15/15** |
| 3d-3a | **column visibility**: toolbar "Columns" picker toggles read-only columns (`cols.<id>` signals → `hide-<id>` class → nth-child CSS collapse); reuses filter-dropdown styling | `e2e/columns.mjs` **9/9** |
| 3d-3b | **client-side sorting**: header click reorders rows asc→desc→clear (island; numeric keys baked, strings read from cells); keeps all filter/edit signals ("never drop filters"); fires `grid-refresh` so grid-nav follows | `e2e/sort.mjs` **12/12** |
| 3d-4 | **header-filter funnels** (account/institution/category): floating `position:fixed` popovers (reuse the 3c portal), checkbox-array signals gated `.filter(x=>x)`, in-funnel search, OR-within/AND-across, split-aware category clause that **unions** the funnel with the Uncategorized chip (also lands split-aware category matching) | `e2e/funnels.mjs` **22/22** |
| 3d-5 | **column resize/auto-fit island** (`col-resize.ts`): table switched to `.table-resizable` (fixed layout); auto-fit on load + ResizeObserver/visibility-change; drag handles (min/max clamp) + double-click re-fit; sort-collision guard; hides hidden columns' `<col>` so widths map 1:1 | `e2e/resize.mjs` **11/11** |
| 3d-6 | **lingering rows**: pin-on-edit (`$linger.tx<id>`) so reviewing/categorizing a row keeps it in place (`.is-stale` + "→" breadcrumb) until a filter change clears the pins (centralized `data-on-signal-patch` reset); category clause now reads the live `$cat` signal | `e2e/lingering.mjs` **12/12** |
| 3d-7 | **URL view-state**: server seeds signals from query params; `url-state` island (`__syncUrl`) + sort island write search/scope/chips/funnels/cols/sort back; month nav preserves the params | `e2e/url-state.mjs` **16/16** |
| 3d-8 | **pagination** (client-side, filter→paginate): `pagination` island slices filter-visible row-groups via `.page-hidden` (composes with data-show); page-size buttons (25/50/100/250) + First/Prev/"Page X of Y"/Next/Last; URL-persisted (`page` 1-indexed, `pageSize`); server seeds + clamps the initial page; page resets on filter/month change | `e2e/pagination.mjs` **17/17** |

React still runs on `:5173` (untouched, for before/after comparison). The new app
is served by the backend at `:8080` (`/`, `/setup`). Flip + delete React at Phase 5.

---

## 2. How to run & verify

JDK 25 is jabba-managed but not on a non-interactive PATH — set `JAVA_HOME`:
```bash
export JAVA_HOME=/opt/homebrew/Cellar/jabba/0.15.0/jdk/zulu@25.0.3/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"            # or: jabba use zulu@25.0.3 in an interactive shell
```

**Backend tests:** `cd backend && clojure -M:test -m kaocha.runner`

**Seeded server (no secrets), for browsing + e2e:**
```bash
cd backend
E2E_PORT=8099 clojure -M:e2e -m finance-aggregator.dev.e2e-server &
# open http://localhost:8099/?month=2025-01   (seed lives in 2025-01)
```
Seed (`backend/env/e2e/.../seed.clj`): 1 institution, 4 accounts, 10 transactions
(real income/expense, two matched transfer pairs = 4 legs, one unmatched
transfer-typed row). Counts for 2025-01: needs-review 10, total 10, uncategorized 6,
transfers-hidden 2.

**Islands build:** `cd islands && npm install && npm run build`
(→ `backend/resources/public/js/islands/*.js`, gitignored). `npm test` runs vitest.

**Browser checks** (reuse the frontend's Playwright via `createRequire`):
```bash
BASE_URL=http://localhost:8099 node e2e/scaffold.mjs      # 6
BASE_URL=http://localhost:8099 node e2e/setup.mjs         # 8
BASE_URL=http://localhost:8099 node e2e/transactions.mjs  # 12
BASE_URL=http://localhost:8099 node e2e/reviewed.mjs      # 5   (mutates + restores)
BASE_URL=http://localhost:8099 node e2e/filters.mjs       # 14
BASE_URL=http://localhost:8099 node e2e/edit.mjs          # 9   (mutates + restores)
BASE_URL=http://localhost:8099 node e2e/combobox.mjs      # 13  (mutates + restores)
BASE_URL=http://localhost:8099 node e2e/grid-nav.mjs      # 19
BASE_URL=http://localhost:8099 node e2e/grid-edit.mjs     # 15  (mutates + resets seed)
BASE_URL=http://localhost:8099 node e2e/columns.mjs       # 9
BASE_URL=http://localhost:8099 node e2e/sort.mjs          # 12
BASE_URL=http://localhost:8099 node e2e/funnels.mjs       # 22
BASE_URL=http://localhost:8099 node e2e/resize.mjs        # 11
BASE_URL=http://localhost:8099 node e2e/lingering.mjs     # 12  (mutates + resets seed)
BASE_URL=http://localhost:8099 node e2e/url-state.mjs     # 16
BASE_URL=http://localhost:8099 node e2e/pagination.mjs    # 17  (forces pages via ?pageSize=3)
```

---

## 3. Where the new code lives

```
backend/src/finance_aggregator/
  web/
    hiccup.clj      a (string→keyword attrs), js-str, signals (JS-literal), render,
                    render-page, read-signals          ← the Datastar/Replicant seam
    layout.clj      base-page document shell (fonts, datastar.js, per-page islands)
    shell.clj       masthead (wordmark, nav tabs, live stats)
    format.clj      amount (CAD, thread-safe), date (UTC)  — matches React lib/format.ts
    month.clj       parse/serialize/display/prev/next     — matches React monthState.ts
    routes.clj      html-routes tree + SSE handlers (sync-reviewed, set-description,
                    set-category, scaffold) + the shared sse-respond helper
    pages/
      setup.clj         /setup account list (read-only)
      transactions.clj  / transactions table + toolbar + filters + counts; inline
                        description edit + category-combobox cells; category-options
                        (DOM-carried category model for the island)
  db/
    accounts.clj    list-with-institution          ← shared by JSON API + pages
    stats.clj       entity-counts                  ← shared
    transactions.clj list-for-month, list-all, month-counts, needs-category?,
                     sync-reviewed!  (+ existing pull pattern, with-derived-fields,
                     set-reviewed!/update-category!/set-user-description!/set-splits!…)
backend/resources/public/
  js/datastar.js    vendored v1.0.2 runtime
  js/islands/       esbuild output (gitignored)
  css/**            full design-system tree; app.css @imports it
islands/            TS island toolchain (package.json, build.mjs, tsconfig, src/lib/*)
  src/combobox.ts   category combobox (Zag.js vanilla; reuses src/lib/categoryHierarchy)
e2e/                *.mjs Playwright checks + README
```

JSON API handlers refactored to call the shared `db` reads (one service layer):
`http/handlers/{stats,entities}.clj`. Router wires `web/html-routes` **before**
`static-routes` (so `/` is the new page, not index.html). Static serving now infers
content-type by extension (ES modules → `text/javascript`).

---

## 4. Architecture & conventions in play — FOLLOW THESE

- **Read signals** only via `web.hiccup/read-signals` (returns `:body-params` for
  POST/PUT, the `datastar` query param for GET/DELETE). Do **not** call the SDK's
  `get-signals` directly — the global `wrap-json-request` consumes the body it reads.
- **Render** with `web.hiccup`. Build full pages with `web.layout/base-page`. **Keep
  SSE fragments as hiccup**; render to a string (`h/render`) only at the
  `patch-elements!` boundary (Replicant escapes an embedded pre-rendered string).
- **Datastar colon-attrs** (`data-on:click`, `data-bind`, `data-class`) go through
  `h/a` (string keys). Embed JS strings with `h/js-str`; signal maps with `h/signals`
  (single-quoted JS object literals — Replicant doesn't escape `"` in attrs).
- **Optimistic edits**: bind the control to a signal (instant) + a **per-element
  debounced `@put` write-behind**. The server **persists only** and patches derived
  state (counts) **by id** — it never echoes the edited cell back. Pattern lives in
  the reviewed toggle (`reviewed-checkbox` + `sync-reviewed-handler` + `sync-reviewed!`).
- **Filtering** is client-side `data-show` per row: bake per-row constants (search
  haystack, transfer-hidden, uncategorized, the reviewed expression) into the
  expression; the toolbar signals (`search`, `scope`, `hideTransfers`, `uncat`) are
  the reactive inputs. Instant, no round-trip.
- **Counts** are server-authoritative (`db.transactions/month-counts`): rendered on
  load and re-patched by id (`counts-fragment`) after a write-behind.
- **One service layer**: pages and the JSON API call the same `db` reads. When you
  need a new read, add it to `db/*` and call from both.
- **CSS**: the whole design system is under `backend/resources/public/css`; `app.css`
  `@import`s it. No inline styles **except dynamic `<col>` widths** (React/TanStack
  precedent — column width is data, not styling).
- **Islands**: TS entry in `islands/src/`, pure logic in `src/lib/` with vitest;
  add the entry to `islands/build.mjs`; output is `…/js/islands/<name>.js`. Island↔
  Datastar interop goes through DOM events. Load per-page via `base-page`'s `:islands`.
- **Tables**: now `.table-resizable` (fixed layout); the server renders a `<col>` width
  estimate for the pre-/no-JS view and the `col-resize` island refines them client-side
  (and hides hidden columns' `<col>` so widths map 1:1 in fixed layout).
- **Month change = full navigation**, but the prev/next links now swap only the `month`
  param on the current URL (preserving the live view-state the `url-state` island keeps
  in the query string); the plain `/?month=` href is the no-JS fallback.

---

## 5. Known gaps / deliberate simplifications to revisit

Resolved during the rest of 3d (kept here for history):
- ~~Lingering rows~~ — **done (3d-6)**, via per-row `$linger.tx<id>` pin-on-edit +
  centralized `data-on-signal-patch` reset (not the snapshot-namespace approach
  originally sketched). The category match clause now reads the live `$cat` signal.
- ~~Header-filter funnels~~ — **done (3d-4)**, pure Datastar + the 3c `.is-floating`
  fixed-portal; rendered outside the table so clicks don't reach the sort handler.
- ~~Uncategorized two-token / split-aware category~~ — **resolved (3d-4)**: the category
  clause unions the funnel selection with the `$uncat` chip and is split-aware. Kept the
  single `$uncat` boolean (the chip = "any uncategorized", matching React's observable
  behaviour) rather than two literal by-sign tokens.
- ~~URL view-state~~ — **done (3d-7)**: server seeds signals from query params; the
  `url-state` island + the sort island write them back; month nav preserves them.

- ~~Pagination~~ — **done (3d-8)**: client-side (filter→paginate) via the `pagination`
  island + URL-persisted page/pageSize. Page-size from the URL accepts any positive int
  (buttons offer 25/50/100/250); the e2e forces multi-page over the 10-row seed with
  `?pageSize=3`.

Still open:
1. **Column WIDTHS not persisted to the URL.** Visibility + sort + page persist; the
   resize island's manual `userWidths` don't yet. Fold in via a `colw=id:width,…` param
   the col-resize island reads/writes.
2. **Sort doesn't clear lingering rows / reset page.** React resets both on sort too; our
   sort is a client island with no signal, so the `data-on-signal-patch` reset doesn't see
   it (the pagination island DOES re-slice on `grid-refresh`, so the page stays valid).
   Benign — wire a signal if it ever matters.
3. **Pagination edge: filtered-on-load page count.** The server seeds the initial page
   count from the TOTAL tx count; a URL that loads with a filter AND a high page can briefly
   over-count until the first interaction (the island re-slices correctly throughout; only
   the count badge is briefly high). No-filter load is exact.
4. **Inline edit / split deferrals (3e).** **Split** memo + category editing is untouched
   (split-row buttons stay inert; no `data-cell` on split cells; the inert split category
   button has no `aria-disabled` yet). Split rows aren't keyboard-navigable or resizable-
   column-aware for category, and use baked (not live) category tokens in the filter.
5. **Actions column empty**; **transfer ⇄ glyphs are inert** (row-actions + transfer
   modals = 3e).
6. **reviewed checkbox** relies on `data-bind` to set `checked` from the signal on
   load (possible 1-frame flash; acceptable).

---

## 6. Remaining work (the roadmap)

**3c — inline edit + category combobox (first real island) — ✅ DONE.** Shipped exactly
as specced below, normal rows only (split editing deferred to 3e). Persistence is
Datastar `@put` + SSE (not the spike's JSON-API fetch): category patches counts;
description patches its signal back to the authoritative effective description. The
combobox reuses `buildCategoryDropdownModel` and renders into the existing
`.category-dropdown-*` classes; its floating root is the reusable portal-positioning
approach for 3d/3e funnels. Original spec, for reference:
- *Inline description edit*: span/input class-swap in the description cell. Bind the
  input to a `desc.tx<id>` signal; the view button click adds an `editing` class +
  focuses the input; Enter → optimistically set the span + `@put('/transactions/:id/
  description')` → `set-user-description!`; Escape reverts; blur commits. Split memo →
  `set-split-memo!`. (Spike reference: `views.clj` `payee-cell`.)
- *Category combobox*: a **Zag vanilla** island (`islands/src/combobox.ts`) reusing
  `categoryHierarchy.ts` (`buildCategoryDropdownModel`). **Reference: the spike's
  `islands/combobox-zag.ts`** (subscribe→render→`spreadProps`, full ARIA, `position:
  fixed` portal to escape table overflow). On select: optimistic cell update +
  `@put('/transactions/:id/category')` → `update-category!` → **patch counts** (a
  category change moves the uncategorized count). Split-row category opens the split
  modal instead (3e). Establish the portal-positioning approach here — the header
  funnels reuse it.

**3d — grid-nav + columns + sort + funnels + resize + linger + URL state — ✅ DONE.**
Shipped as units 3d-1…3d-7 (see the status table §1 for each + its e2e spec):
- **grid-nav + keyboard editing** (3d-1/3d-2): `grid-nav.ts` over the ported reducer.
- **column visibility** (3d-3a) + **client-side sorting** (3d-3b).
- **header-filter funnels** (3d-4): pure Datastar floating popovers; split-aware category
  clause unioning the funnel with the `$uncat` chip.
- **column resize/auto-fit** (3d-5): `col-resize.ts`; table is now `.table-resizable`
  (fixed layout); auto-fit + drag + double-click re-fit; hides hidden columns' `<col>`.
- **lingering rows** (3d-6): pin-on-edit `$linger` + centralized signal-patch reset.
- **URL view-state** (3d-7): server seeds from params; `url-state`/sort islands write back.
- **pagination** (3d-8): client-side filter→paginate via the `pagination` island
  (`.page-hidden` over filter-visible groups); page-size buttons; URL-persisted page/pageSize;
  server seeds + clamps the initial page; resets on filter/month change.

Remaining 3d-adjacent items are deferred (see §5): *column-width URL persistence* and the
*split-row* editing paths (3e). Split-row navigation/editing stays in 3e (no `data-cell` on
split cells; baked category tokens).

**3e — modals + rollup + row actions**
- *Split editor*: port `splitMath.ts`; live balance; `@put('/transactions/:id/splits')`
  → `set-splits!`. *Transfer match modal* (per-row): candidates via
  `db.transfers/match-candidates`, `confirm-match!`/`unmatch!`. *Transfer review modal*
  ("Find transfers"): `suggest-matches`, `confirm-match!`/`reject-match!`. *Category
  rollup pane* (right side, income/expense/transfer totals, click-to-filter — port
  `categoryRollup.ts`). *Row-actions menu* (split/edit-split). *Active-filter chips*.

**Phase 4** — CSV import wizard (server-held step state), bulk category modal
(server-rendered preview — [[feedback_bulk_confirm_ui]]), Plaid (vanilla Link SDK
island *or* remove — confirm with owner), WebSocket sync progress → long-lived
Datastar SSE stream.

**Phase 5** — delete `frontend/` (React, react-router, TanStack, downshift, zod, the
JSON-client/overlay/write-behind/URL-codec plumbing). Keep CSS (already carried).
Update `DEVELOPMENT.md`, `README.md`, `Procfile`. Extract the surviving islands +
their vitest tests out of `frontend/` if any still reference it.

---

## 7. Service-layer cheat-sheet (what to call — don't rewrite logic)

- **Reads:** `db.transactions/{list-for-month, list-all, month-counts, needs-category?}`,
  `db.accounts/list-with-institution`, `db.stats/entity-counts`, `db.categories/list-all`.
- **Tx mutations** (each returns the refreshed tx with derived fields):
  `db.transactions/{set-reviewed!, set-split-reviewed!, update-category!,
  set-user-description!, set-split-memo!, set-splits!}`; batch reviewed write-behind:
  `sync-reviewed!`.
- **Transfers:** `db.transfers/{suggest-matches, match-candidates, confirm-match!,
  unmatch!, reject-match!}`.
- **Categories:** `db.categories/{list-all, create!, update!, delete!, in-use?,
  create-many!, batch-update-sort-orders!}`.
- **Tx shape:** `db.transactions/transaction-pull-pattern` + `with-derived-fields`
  (adds `:transaction/effective-description`, effective `:transaction/reviewed`,
  `:transaction/splits-balanced`, `:transaction/transfer-hidden`). Amounts are signed
  BigDecimals (inflows +, outflows −).

---

## 8. Gotchas reference

The full hard-won list is in `replicant-datastar-migration.md` §5 (now including the
two found while implementing: **embedded pre-rendered HTML strings get escaped** —
keep fragments as hiccup; **`read-signals` must use `:body-params`** because
wrap-json-request consumes the body and v1.0.2 sends no `datastar-request` header).
Also real: the **zsh `path` reserved-array** trap bit a verification loop.
