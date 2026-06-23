# Datastar workspace — resume-here handoff

**Read this first.** Single entry point (state, file map, idiom, gotchas, run/verify) for the
server-authoritative transactions workspace. Depth/why is in
`datastar-server-authoritative-rewrite.md`; memory entry `project_replicant_datastar_spike`.
**Branch:** `spike/replicant-datastar`.

> **The migration is complete, and the pre-ship hardening pass shipped** (global error bar, modal
> focus trap/restore, `aria-live` count announcements; single-user ship confirmed) — see
> **`pre-ship-review.md`** for what was closed and what stays open. Remaining work is the cross-cutting
> backend hardening tracked separately (memory `project_backend_hardening`: reconciliation + account
> sync). This doc is the orientation for the workspace itself.

## Where we are (2026-06-21)

The React→Datastar migration is structurally **done**. The server-authoritative workspace is the
canonical `/` (server renders + SSE-morphs fragments; client holds only ephemeral UI state).
**Replicant is gone — hiccup2 is the only renderer.** The old client-heavy page, the spike, the
scaffold, and the dead islands are deleted. Views are strictly presentational; all data logic is
in pure, tested fns. Filter counts are faceted and compose. The old React `frontend/` is **deleted**
(Phase 5). **Suite 326/0; 16 TypeScript browser specs green** (`e2e/*.ts`, run via Node native TS).

Done: R0 (hiccup2 seam) · R1 (pure view engine) · R2 (table + toolbar + edits with undo/lingering)
· cp1b (funnels) · cp2-tail (grid-nav + resize) · R3 (column chooser + URL state) · R4 (delete old
stack, flip `/v2`→`/`) · 2 UI-polish rounds (8 bugs + faceted counts + active-filter chips) ·
**R5 COMPLETE** — R5a (split editor + row-actions menu) · R5b (transfer match/review modals) ·
R5c (category rollup pane).

**R5a DONE** — a row's caret (trailing always-on chrome column, not a hideable data column) opens a
shared floating menu → "Split transaction" @get's the split-editor modal into `#modal-root`. The
`split-editor` island (built on the already-tested `lib/splitMath`) runs the live balance math in a
native-`<select>` editor; Save serialises the signed payload through `#split-courier` →
`PUT /transactions/:id/splits` → `:set-splits` command (undo/redo + lingering + faceted counts for
free) → response re-patches `#modal-root` empty (closes the modal). Cancel/Esc/backdrop close
client-side. Files: `view/split-editor-seed`, `view-state/parse-splits-value`,
`db.transactions/{current-splits,by-id}`, `commands/:set-splits`, `pages/transactions`
(row-actions-menu + split-editor-modal), `islands/split-editor.ts`, `css/{row-actions,split-modal}`,
`e2e/v2-split.ts`.

**R5b DONE** — transfer match/review, **no island** (interactions are plain @put's). A second
row-actions item ("Match transfer"/"Matched transfer", driven by `$_rowMenuMatched`) opens
`GET /:id/match`: unmatched → a `match-candidates` list (each button confirms), matched → the
partner card + Unmatch. A toolbar "Review transfers" opens `GET /transactions/review-transfers`: the
`suggest-matches` list, each row Confirm/"Not a transfer" acting in place (refreshes `#review-list`,
modal stays open). All mutations are commands (`:set-match`, `:reject-match` + `db.transfers/unreject!`)
so they undo/redo. Island-less modals close on backdrop-click (guarded `evt.target===el` — Datastar
has **no `__self` modifier**) / Esc / Close. Files: `commands/{:set-match,:reject-match}`,
`pages/transactions` (match-modal + review-modal + handlers; `edit-response :after-patch`),
`css/transfer-modal.css`, `e2e/{v2-match,v2-review}.ts`.

**R5c DONE** — the `#category-rollup` aside (whole-month per-category breakdown: income/expense/
transfer sections, subtotals, signed Net) renders beside the table (the carried-over
`category-rollup.css` + `dashboard.css` already style it into the fixed-viewport shell). Built on
the ported pure `view/category-rollup`. Clicking a row toggles `$filter.category` (or `$uncat` for
Uncategorized) — pure reuse of the funnel signals, so the funnel checkboxes + active-filter chips
stay in sync and the row highlights via `data-class`. Whole-month → re-patched by id on edits only.
Files: `view/category-rollup`, `pages/transactions` (rollup-pane + handlers), `e2e/v2-rollup.ts`.

**Phase 5 DONE** — the old React `frontend/` is **deleted** (168 MB; every feature is on the new
stack). Playwright was relocated into `e2e/` (its own `package.json`, pinned to the exact version
whose browser is already in the global cache) and the e2e specs were **converted to TypeScript**:
`e2e/*.ts` use a plain `import { chromium } from '@playwright/test'` (no more `createRequire`
borrow) and run directly via **Node's native type-stripping** (`node e2e/<spec>.ts`, no build),
type-checked with `cd e2e && npm run typecheck`. The obsolete pre-R4 specs were dropped; the
canonical (`v2-*` + `setup`) survive as `.ts` — now 16 specs (incl. `v2-errors`, `v2-modal-focus`
from the pre-ship pass).

**The migration is complete.** What's left is optional: the deferred R5 polish below, plus
cross-cutting backend work tracked separately (`project_backend_hardening` — reconciliation +
account sync). The throwaway `doc/spikes/replicant-datastar/` prototype has since been deleted (its
research is summarised in `doc/spikes/replicant-datastar/FINDINGS.md`). Minor: the `v2-` spec prefix
is now just a name; measure the search debounce on the VPS.

## Run & verify

JDK is jabba-managed; set it once per shell (`jabba use zulu@25.0.3`, or export `JAVA_HOME`):
```bash
export JAVA_HOME=/opt/homebrew/Cellar/jabba/0.15.0/jdk/zulu@25.0.3/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
```
The **`bb` tasks wrap all of this**: `bb test` (kaocha + vitest), `bb build` (islands + pinned
Datastar), `bb e2e` (build + boot the seeded server + run every spec + teardown; `bb e2e <spec>` for
a subset), `bb dev` (app on :8080). The raw underlying steps:
- **Backend tests:** `cd backend && clojure -M:test -m kaocha.runner`  → 326/0
- **Build the islands** (NOT automatic; gitignored output; a fresh checkout has none):
  `cd islands && npm install && npm run build`  → `backend/resources/public/js/islands/*.js`
- **e2e deps + typecheck** (Playwright + types live here now): `cd e2e && npm install && npm run typecheck`
- **Seeded server** (no secrets; seed lives in 2025-01): `cd backend &&
  E2E_PORT=8099 clojure -M:e2e -m finance-aggregator.dev.e2e-server` → http://localhost:8099/?month=2025-01
  - `POST /e2e/reset` re-seeds + clears the in-memory command log.
- **Browser specs** (real Chromium; TypeScript run via Node native type-stripping — no build):
  `for f in e2e/*.ts; do s=$(basename "$f" .ts); curl -s -X POST $BASE/e2e/reset >/dev/null;
   BASE_URL=http://localhost:8099 node "e2e/$s.ts"; done`  (reset between specs that mutate)
- **Real data:** the main dev server serves the same pages: `cd backend && clojure -M:dev -m finance-aggregator.main` → :8080.

## File map (the canonical stack)

```
backend/src/finance_aggregator/web/
  render.clj      hiccup2 seam: render / render-page / signals(JSON) / raw / read-signals
  layout.clj      base HTML document (fonts, datastar.js, per-page islands)
  shell.clj       masthead
  routes.clj      / (page) + /transactions/{rows,:id/reviewed/:v,:id/description,:id/category,
                  :id/split-editor,:id/splits,:id/match,:id/match/:partner,:id/unmatch,
                  review-transfers,review/:out/confirm/:in,review/:a/reject/:b,undo,redo} + /setup
  view.clj        PURE engine: filter-txs / sort-txs / paginate / view / view-with-linger (lingering)
                  + facet-counts + account/institution/category-funnel-options (all take a view-state)
  view_state.clj  PURE codec: query-params ↔ view-state ↔ Datastar signals (+ parse-category-value); column config
  commands.clj    per-user in-memory undo/redo/linger log (apply!/undo!/redo!/linger); NOT event-sourced
  accounts.clj    PURE /setup display rules (display-type, provider-label, sort)
  format.clj month.clj   PURE formatters / month date-math (all tested)
  pages/transactions.clj  THE page — dumb: hiccup renderers + static Datastar-attr JS strings + thin handlers
  pages/setup.clj         dumb /setup
islands/src/   combobox.ts (Zag) · grid-nav.ts · url.ts (window.__syncUrl) · resize.ts (window.__resetWidths) · split-editor.ts · lib/* (pure, vitest)
e2e/           v2*.ts + setup.ts (TypeScript; "v2" prefix is just a name) + package.json/tsconfig
```

## Conventions (FOLLOW THESE)

- **Dumb views.** Views render hiccup + thin handlers only. ALL data fetch/rearrange/parse lives
  in pure, kaocha-tested fns (`view`, `view_state`, `accounts`). A bug once hid in a private view
  fn and escaped the tests — don't repeat it.
- **Two axes of state.** (survives reload? → URL) × (server renders it vs client applies it).
  Filter/sort/paginate/edits = URL + SSE-morph. Ephemeral UI (popover open, active cell, in-funnel
  query) = `_`-prefixed signals (Datastar omits them from requests), pure client, no round-trip.
- **Server data NEVER goes into a client expression** — it lives in morphed HTML; expressions are
  tiny static literals. (Where a label must go into an expression — funnel search — JSON-encode it.)
- **Round-trip:** control sets a signal + `@get` (reads) / `@put`/`@post` (mutations) → server reads
  signals (or URL) → renders fragment → morph **by id**. GET for reads (no body → sidesteps the
  wrap-json-request body gotcha). Every patch target has a stable id.
- **Edits = commands** (`commands.clj`): apply runs the db mutation to `:after`, undo to `:before`.
  Undo/redo = toolbar buttons + Cmd/Ctrl+Z. **Lingering** keeps a just-edited row visible+`is-stale`
  in place; **faceted counts** keep filter feedback consistent (`patch-filter-feedback!`).
- **Persistent view-state in the URL** (server seeds on load; `url.ts` reflects it back). No localStorage.

## Gotchas

- **Islands aren't auto-built.** After any `islands/src/*.ts` change: `cd islands && npm run build`
  + hard-refresh (browser caches `/js/islands/*.js`). A "missing feature" is usually a stale build.
- **hiccup2 escapes attribute values** (incl. `'`→`&apos;`) and **sorts attributes alphabetically** —
  the browser decodes, Datastar fires fine, but don't grep rendered HTML assuming attribute order.
- **SSE multi-patch ordering**: a response patches several fragments as separate events in order;
  e2e gates on the LAST patch (undo-redo) where reading a mid-stream value would flake.
- **grid-nav vs morph**: edits morph `#tx-tbody`, which wipes the active-cell highlight; the island
  runs a guarded MutationObserver to rebuild+repaint (active cell keyed by stable RowKey).
- **e2e specs are TypeScript run by Node directly** (native type-stripping; `node e2e/<spec>.ts`,
  no build). Playwright lives in `e2e/node_modules`, pinned to **the exact version whose Chromium is
  in the global `~/.../ms-playwright` cache** — bump it and you must `npx playwright install chromium`.
  The tsconfig is `strict` (with `noImplicitAny:false` for the inline browser callbacks) — keep specs
  type-strippable (no enums/namespaces); `cd e2e && npm run typecheck`.

## Modal idiom reference (R5 complete) + what's deferred

**The modal idiom is established — two flavours.** A row's caret sets `$_rowMenu` and the shared
`#row-actions-menu` item (or a toolbar button) `@get`s a fragment into `#modal-root`. Two ways to
wire the interaction:
- **Island modal** (rich client widget — the split editor): fragment carries data via `data-*`;
  an island (MutationObserver on `#modal-root`) owns the live UI; a hidden courier input's
  `data-on:change` @put's; the PUT closes the modal server-side (`edit-response :close-modal?`).
- **Island-less modal** (the transfer modals): interactions are plain `@put`s on buttons in the
  fragment; the action either closes the modal (`:close-modal?`) or refreshes a sub-fragment in
  place (`:after-patch`, e.g. `#review-list`). Backdrop/Esc/Close close client-side via
  `close-modal-js` (guard backdrop clicks with `evt.target===el` — **no `__self` modifier**).

- **Deferred polish (nice-to-have, not blocking Phase 5):** split *child* rows aren't
  keyboard-navigable or inline-editable (no `data-cell` on split cells); split-part reviewed
  checkboxes are read-only (`db.transactions/set-split-reviewed!` exists for when they become
  editable); a matched transfer shows no in-row marker — only the row-menu reads "Matched transfer"
  (the `.transfer-status` CSS is carried over if an in-cell ⇄ marker is wanted later).
