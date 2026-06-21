# Datastar server-authoritative rewrite — architecture & plan

**Status:** R0–R4 **DONE** — the server-authoritative workspace is the canonical `/`; Replicant,
the old client-heavy page, the spike, and the dead islands are all deleted; hiccup2 is the only
renderer; views are strictly presentational. **Branch:** `spike/replicant-datastar`. Suite
**309/0**; 9 browser specs green. **Next: R5** (split editor + transfer modals + rollup + row
actions) and **Phase 5** (delete the old React `frontend/` — note the e2e harness currently
borrows Playwright from `frontend/node_modules`, so move that first).

`/` now has: server-side filter/scope/chips/funnels/sort/paginate; undoable reviewed /
description / category edits with lingering (rows hold position); column chooser (all columns);
keyboard grid-nav (morph-aware, skips hidden cols); column auto-size + local resize + reset-widths;
URL view-state persistence. Browser specs `e2e/v2*.mjs` + `e2e/setup.mjs`, all green.
Supersedes the client-heavy approach in `replicant-datastar-progress.md` for the
transactions workspace. Memory: `project_replicant_datastar_spike`.

## Conventions locked in (the dumb-views rule)

Surviving views are **strictly presentational** — hiccup renderers + thin handlers (read
signals → call pure fns → hit db → render) only. **All data fetching/rearranging/parsing lives
in pure, kaocha-tested fns**, never in a view (the lingering bug proved logic-in-a-view escapes
the test net). Pure homes: `web/view.clj` (filter/sort/paginate/linger + funnel options),
`web/view_state.clj` (query↔view-state↔signals codec + value parsers), `web/accounts.clj`
(account display rules), `web/format.clj` + `web/month.clj` (formatters/date-math). All tested.

## Why

The first migration ported React's client-heavy architecture onto Datastar (optimistic
edits, client-side filter/sort/paginate, ~4 signals/row, large baked `data-show`
expressions) — the one mode Datastar's authors explicitly advise against. It worked but
fought the grain: untestable JS-in-strings, signal sprawl, a fragile signal-patch
coordinator layer, and a renderer seam (`js-str`/`signals`) that existed only to dodge
Replicant's non-escaping string renderer.

A spike (`web/pages/rows_spike.clj`, `/spike`) proved the idiomatic alternative: a
**server-authoritative thin client**. Discrete view changes round-trip a small `<tbody>`
fragment over SSE; the server owns filtering/sorting/paginating/edits; the client holds
only ephemeral UI state. Local server render is 1–3 ms, so on the VPS the felt latency is
≈ network RTT — under the ~100 ms "instant" threshold for discrete clicks.

## The core idea: two axes of frontend state

Every interaction is classified by two questions. This is the whole architecture.

| | **Server renders it** (changes *which*/​*what* data) | **Client applies it** (pure presentation) |
|---|---|---|
| **Survives reload** (→ in the URL) | **Filter, sort, paginate, data edits** — URL seeds load; controls `@get`/`@put`; server re-renders fragment; Datastar morphs by id. | **Column visibility, column widths** — URL seeds load; applied client-side (CSS class / `<col>` width); thin `replaceState` reflector. |
| **Ephemeral** (never persisted) | *(empty — doesn't occur)* | **Active-cell ring, popover open/closed, combobox highlight, in-funnel query text, drag-in-progress** — `_`-prefixed signals, pure Datastar/CSS, no round-trip. |

Rules that fall out of it:

1. **Persistent state lives in the URL**, read server-side on load (the server is the
   source of truth for what renders). Plain signal names.
2. **Ephemeral UI state is `_`-prefixed** (`$_openFunnel`, `$_activeCell`, `$_colPickerOpen`)
   so Datastar omits it from every request. Never touches the server or the URL.
3. **Server data never appears in a client expression.** Data lives in morphed HTML;
   expressions are tiny static literals (`$_openFunnel = 'account'`, `@get('/transactions/rows')`).
   This is what removes the escaping/injection hazard entirely.
4. **Round-trip pattern:** control → (set a plain signal if needed) → `@get` (reads) /
   `@put`/`@post`/`@delete` (mutations) → server reads signals or URL → renders fragment →
   morph **by id**. GET for reads (no body → sidesteps the `wrap-json-request` body
   gotcha); body methods for mutations.
5. **Every patch target has a stable id** (`#tx-tbody`, `#counts`, toolbar bits).

## Renderer: Replicant → hiccup2 (R0, done)

Replicant's value is its *client* virtual DOM, which this stack never used (Datastar +
TS islands own the client). It was only `replicant.string/render`, whose non-escaping of
attribute quotes forced the `js-str`/`js-value`/`signals` seam. hiccup2 escapes by
default, which collapses the seam:

- String/colon attribute keys render verbatim → **no `h/a` helper** (was 45 sites).
- `data-signals` is plain JSON; hiccup2 escapes the quotes, the browser decodes them,
  Datastar parses valid JSON → **no `js-str`/`signals`** (was 8 sites, ~40 lines).
- Single quotes in expressions render as `&apos;`; **browser-verified** that Datastar
  fires correctly after decode (`e2e/spike-hiccup2.mjs`, 5/5).

New seam = `web/render.clj`: `render`, `render-page`, `signals` (JSON), `raw`,
`read-signals`. That's it.

## What gets deleted vs kept

**Deleted** (at R4, once the new page lands):
- Replicant dep; `web/hiccup.clj` (`a`/`js-str`/`js-value`/`signals`); the Replicant
  `web/layout.clj`.
- Baked per-row expressions: `match-expr`, `category-show-clause`, `reviewed-expr`,
  `column-filter-clause`, `search-haystack`, `row-attrs`, `category-moved-mark`, funnel
  clauses.
- Per-row signal seeds: `reviewed-signals`, `description-signals`, `category-signals`,
  `linger-signals` (and the `reviewed.txN`/`desc.txN`/`cat.txN`/`linger.txN` namespaces).
- Islands: `sort.ts`, `pagination.ts`, `url-state.ts` (server owns these now), `hello.ts`.
- Coordinators: `linger-reset`, `url-sync`, `pagination-bridges`. The `/_scaffold` route
  + atom.

**Kept** — the ephemeral / continuous-gesture layer (axis row 2 + 3):
- `col-resize.ts` (drag is a gesture; persist the *result* to the URL), `grid-nav.ts`
  (focus/active cell), `combobox.ts` (Zag widget; commit round-trips).
- Pure tested modules: `gridNavigation`, `columnAutoSizing`, `categoryHierarchy`, `splitMath`.

**Added** — the server-side view engine:
- `db.transactions/view` (or a `web` ns): pure `(view txs view-state) → {rows counts page-info}`
  doing filter + sort + paginate in Clojure. **Ports the JS-string logic into kaocha-tested
  Clojure** — the biggest correctness win.
- Fragment renderers (`tbody`, `counts`, active toolbar states) + `GET /transactions/rows`;
  existing mutation PUTs re-render the affected fragments.

## Bug-fix + cleanup pass (DONE — 2026-06-21)

8 UI bugs fixed (each chased by a subagent, browser-verified, committed):
1. Combobox now auto-highlights the typed match (island was discarding the model's `firstMatchIndex`).
2/3. Month carets (no underline, bigger) + undo/redo glyphs bigger / buttons shorter (CSS).
4. All columns hideable + **Reset-widths** button; grid-nav skips `display:none` cells.
5. **Resize is local** (architectural): replaced "auto-fit redistributes all flexible columns"
   with **freeze-then-local** — the first gesture freezes all widths, then only the touched
   column moves. 6. Double-click always content-fits a column (was a no-op until dragged).
7/8. **Lingered rows hold position** (one root cause): linger composition was `(concat matched
   lingered)` — appended instead of holding source order; now selects by walking source order.

Then a **view-logic cleanup**: extracted all data logic out of the views into pure tested fns
(see "Conventions locked in" above). `transactions2.clj` 717→577 lines. Found + fixed real
duplication (`tx-account-id`/etc. dup'd `view.clj`; `hideable-columns` hand-synced → derived from
`columns`; `fmt-int` dup'd) and the **inverse latent gap**: `month.clj`/`format.clj` were untested
pure logic (now tested). +25 tests (290→315).

## R4 — DONE (2026-06-21)

Flipped `/v2` → `/` (page at `/`, SSE/edit endpoints under `/transactions/*`). Deleted the old
client-heavy page, the spike + scaffold, and the dead islands (`sort`/`pagination`/`url-state`/
`hello`/old `col-resize`). Removed the Replicant seam (`web/hiccup.clj` + test, `web/layout.clj`)
and the `no.cjohansen/replicant` dep — **hiccup2 is the only renderer**. Converted `/setup` to
the hiccup2 seam. Dropped the `2`/`v2` suffixes: `transactions2`→`transactions`, `layout2`→
`layout`, islands `v2-url`→`url` + `v2-resize`→`resize` (window hooks `__syncUrl`/`__resetWidths`).
Suite 309/0; 9 specs green against `/`. Final `web/` stack: `view`/`view_state`/`commands`/
`accounts`/`format`/`month`/`render`/`layout`/`shell`/`routes` + `pages/{transactions,setup}`.

## Small follow-ups (low priority)

- **Unused/dead CSS**: the `.table-dense` rule (cp1) is now unused (the table is `.table-resizable`);
  `.pagination-nav-button` still re-derives the icon-button footprint (fold into the shared rule);
  `.nav-links .button { text-decoration:none }` is dead (base `.button` carries it now).
- **e2e spec filenames** still `v2-*.mjs` (cosmetic; content + URLs are canonical). Harness still
  borrows Playwright from `frontend/node_modules` (move before Phase 5 deletes `frontend/`).
- **Known small gaps**: column widths aren't URL-persisted (auto-fit on load only); the description
  editor opens via grid-nav but doesn't Enter-walk-the-column; page index is 0-based in the URL.
- **SSE multi-patch ordering**: edit responses patch tbody → pagination → counts → undo-redo as
  separate events (applied in order; imperceptible). e2e gates on the last patch where it matters.

## cp2 design: edits + command-log undo + lingering

**Edits are commands.** Every user mutation (reviewed / category / description / split) is a
`Command` data map: `{:type :tx-id :before :after :label}`. Applying runs the existing db
mutation to `:after`; undo runs it to `:before`. The db stays the source of truth (commands
are *how to reverse*, not a parallel store) — this rides on the append-only-overlay model
([[feedback_append_only_overlays]]).

**`web/commands.clj`** — a per-user (single-user for now, `auth/user-id`) in-memory log:
`{:undo [cmd…] :redo [cmd…] :linger #{tx-id…}}` in a `defonce` atom. `apply!` runs + pushes
to undo + marks the tx lingering + clears redo; `undo!`/`redo!` move between stacks and run
the inverse/forward mutation. In-memory (ephemeral, session-scoped) is deliberate — undo is
a UI affordance, not durable state; the `Command` is plain data so it *could* be persisted
later (audit trail) without changing callers. Not over-engineered into event-sourcing.

**Lingering is server-side.** The edit re-render composes the view steps with a linger
injection: `matched = filter-txs`; `lingered = month txs whose id ∈ linger-set but ∉ matched`;
render `sort(matched ∪ lingered)` with the lingered rows tagged `is-stale`. Any pure view
change (`/v2/rows`) clears the linger set. No per-row `$linger` signals, no signal-patch reset.

**Undo affordance** = `#undo-redo` toolbar buttons (↶/↷, `@post('/v2/undo'|'/v2/redo')`),
re-rendered on every edit so their enabled state + tooltip track the log, plus a global
`Cmd/Ctrl+Z` (`data-on:keydown__window`). No toast (the user finds them obtrusive). Lingering
keeps the mistake *visible*; undo *reverses* it — both, per the "going too fast" concern.

**Edit round-trip** patches three fragments: `#tx-tbody` (with lingering), the count badges
(reviewing a row drops the unreviewed count), and `#undo-redo`. Reviewed is the first slice
(checkbox → `@put('/v2/tx/:id/reviewed/:v')`); description (input) and category (combobox
island) follow the same `apply!` path.

## Considered & deferred: server-owns-all streaming CQRS (core.async.flow)

Evaluated an architecture where the server owns all UI state through a core.async.flow
CQRS backbone, streaming the UI down one persistent SSE connection. Verdict: **over-engineered
for this workspace's CRUD, but two ingredients are worth adopting selectively.**
- **Persistent SSE stream** — adopt for *server-initiated* updates (account sync, import
  progress, webhooks); already the Phase-4 plan. NOT the transport for table clicks:
  request/response keeps state in the URL (shareable, back-button-correct, no per-connection
  session to rehydrate).
- **core.async.flow + CQRS** — over-engineering for human-rate writes + a "load a month"
  read model. Where it *would* pay off is the provider-sync ingestion pipeline (the
  provider-seam debt), not the UI.
- **Event-sourced command log → undo/redo** — the genuinely valuable part, and it needs
  *only* a reversible command stack, not the streaming/flow machinery. Fits the existing
  append-only-overlay edit model ([[feedback_append_only_overlays]]). Adopt as a backend
  feature in cp2.

## Lingering, reconsidered

Server-side it gets simpler: when a mutation would filter a row out (review it while in
Needs-review scope), the server can render that row with an `is-stale` tag and keep it
until the next view change — no per-row `$linger` signals, no signal-patch reset regex.
Decide the exact UX in cp2.

**Lingering and undo are complementary, not substitutes** (the user's concern is going too
fast and not *spotting* mistakes): lingering keeps the edited row *visible* so the change is
noticeable; undo lets you *reverse* a spotted mistake (ideally surfaced inline, Gmail-style:
"Categorized as Groceries · Undo"). Do both in cp2; neither needs the streaming-CQRS stack.

## Phases (e2e specs are the behavior contract — refactor against them)

- **R0 — Renderer foundation. ✅ DONE.** hiccup2 dep; `web/render.clj`; spike converted to
  it (Replicant-free) and browser-verified (`e2e/spike-hiccup2.mjs` 5/5). Replicant stays
  on the classpath only for the not-yet-migrated `/` + `/setup`.
- **R1 — Server view engine. ✅ DONE.** `web/view.clj`: pure `view` (filter → sort →
  paginate) porting every baked rule; `web/view_test.clj` 8 tests / 32 assertions. No UI yet.
- **R2 — New server-authoritative page (`/v2`, becomes `/` at R4).**
  - **cp1 ✅ DONE.** Read-only table + toolbar; search/scope/chips/sort/paginate via
    `GET /v2/rows` → `view` → morph `#tx-tbody` + `#pagination-bar`, `$page` patched back.
    `web/layout2.clj` = hiccup2 shell. Browser-verified `e2e/v2.mjs` 7/7. Search debounced
    300 ms (the one latency-sensitive control — measure on the VPS).
  - **cp1b ✅ DONE.** Header-filter funnels (account/institution/category): floating popovers
    (outside the table), checkbox-arrays → `filter.<col>` → `@get` (server filters), in-funnel
    search (label JSON-encoded), Clear. URL-persisted. `e2e/v2-funnels.mjs` 10/10.
  - **cp2 core ✅ DONE.** `web/commands.clj` (per-user undo/redo/linger log) + the
    server-confirmed **reviewed** edit on `/v2`: `@put('/v2/tx/:id/reviewed/:v')` applies a
    command, morphs `#tx-tbody` (lingering just-edited rows `is-stale`) + count badges +
    `#undo-redo`. Undo/redo are **toolbar buttons** (↶/↷, enabled-state tracks the log) +
    Cmd/Ctrl+Z (Shift = redo) — no toast (obtrusive). `/e2e/reset` clears the log.
    Browser-verified `e2e/v2-edit.mjs` 10/10; `commands_test` 3 tests.
  - **cp2 description ✅ DONE.** Inline editor (class-swap, `@put('/v2/tx/:id/description')`
    → `:set-description` command capturing the prior override via `db.transactions/user-description`).
    Single `$editValue` courier, no per-row signals. `e2e/v2-desc.mjs` 5/5.
  - **cp2 category ✅ DONE.** Zag combobox island wired to /v2: `.category-button.combo-cell`
    + hidden-input courier → `@put('/v2/tx/:id/category')` → `:update-category` command
    (prior id captured via `db.transactions/category-id`). Counts move; undoable. Island
    survives morphs (delegated listener). `e2e/v2-category.mjs` 8/8.
  - Row-height density fix: `.table-dense` (was `.table-resizable`-only). Matches `/` (37px).
  - **Note:** edit responses patch fragments as separate SSE events (tbody → pagination →
    counts → undo-redo), applied in order — imperceptible to a user, but e2e gates on the
    last (undo-redo) to avoid reading counts mid-stream.
  - **cp2-tail ✅ DONE.** **grid-nav** (`e2e/v2-grid.mjs` 9/9): keyboard cell nav + Space-toggle
    + Enter-edit; a guarded MutationObserver rebuilds+repaints so the active cell survives edit
    morphs (the key server-morph-vs-DOM-state bridge). **col-resize** (`e2e/v2-resize.mjs` 5/5):
    new DOM-driven `v2-resize` island (reads column metadata from headers, reuses columnAutoSizing);
    `/v2` switched to `.table-resizable` fixed layout. Still deferred: **split-row** editing.

- **R3 ✅ DONE** (column chooser + URL view-state persistence via client CSS + a thin
  `replaceState` reflector; the server seeds from the URL on load): `e2e/v2-cols.mjs` 7/7.
- **R4 — Delete the old. ✅ DONE** (see the "R4 — DONE" section above). Replicant + old page +
  spike + scaffold + dead islands gone; `/setup` on hiccup2; `/v2` → `/`; suffixes dropped.
- **R5 — split editor + transfer modals + rollup + row actions** on the new pattern
  (split balance via `splitMath`, etc.).

## How to verify (current)

```bash
cd backend && export JAVA_HOME=/opt/homebrew/Cellar/jabba/0.15.0/jdk/zulu@25.0.3/Contents/Home
clojure -M:test -m kaocha.runner                              # 315/0
E2E_PORT=8099 clojure -M:e2e -m finance-aggregator.dev.e2e-server &
# the new server-authoritative workspace + its specs (build islands first: cd islands && npm run build)
for s in v2 v2-edit v2-desc v2-category v2-cols v2-funnels v2-grid v2-resize setup; do
  BASE_URL=http://localhost:8099 node e2e/$s.mjs; done
```
