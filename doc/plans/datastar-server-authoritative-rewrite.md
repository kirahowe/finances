# Datastar server-authoritative rewrite — architecture & plan

**Status:** R0–R4 **DONE** + two UI-polish rounds + **R5 COMPLETE** (R5a split editor · R5b transfer
match/review modals · R5c category rollup pane) — the server-authoritative workspace is the
canonical `/`; Replicant + the old page/spike/dead-islands are deleted; hiccup2 is the only
renderer; views are strictly presentational; **filter counts are faceted and compose**.
**Branch:** `spike/replicant-datastar`. Suite **326/0**; **14 browser specs** (`e2e/v2*.mjs` +
`setup`) green. **Next: Phase 5** (delete the old React `frontend/` — now fully dead; but the e2e
harness borrows Playwright from `frontend/node_modules`, so relocate that first).
**Resume doc:** `datastar-handoff.md`.

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

## UI-polish rounds (DONE — 2026-06-21)

**Round 1** (8 bugs, each subagent-chased + browser-verified): combobox auto-highlights the
typed match; month carets / undo-redo glyph sizing; all columns hideable + Reset-widths; **column
resize is local** (freeze-then-local model, the architectural one); double-click autosize; and
**lingered rows hold their position** (the `concat`-appends bug). Plus a view-logic cleanup pass:
all data logic extracted into pure tested fns (`web/view`, `web/view_state`, `web/accounts`),
`month`/`format` got tests.

**Round 2** (this session): combobox doubled border (removed a redundant focus-ring box-shadow);
toolbar controls share one `--toolbar-control-height` token (chips + icon buttons all 29px); and
**faceted filter counts**.

### Faceted counts (`web/view.clj` `facet-counts` + the funnel-options take a view-state)

The chips/scope/funnel-option counts were full-month and never re-patched on a filter change, so
composing filters showed mismatched numbers. Now **faceted-search semantics**: each count reflects
toggling *that* control given the *other* active filters (its own facet neutralized via
`drop-facet`) — `All`/`Needs-review` drop scope; `Uncategorized`/`Hide-transfers` drop their own
chip (the chip + category funnel are one OR-dimension, `:category-dim`); each funnel option drops
its own funnel. So counts stay consistent with the displayed rows, and the **displayed total lives
in the pagination footer**. Header-funnel selections render as removable `.active-chip` tokens
(`#active-filters`). The `rows` + edit handlers re-patch the count badges + the three funnel option
lists (`#funnel-list-<col>`) + the chips on every view change via `patch-filter-feedback!`.
(Note: `filter-txs` now treats scope as needs-review only when *explicitly* `:needs-review`, so a
partial faceting view-state defaults to show-all.) Verified `e2e/v2-counts.mjs` 7/7.

## R5a — split editor + row-actions menu (DONE — 2026-06-21)

First R5 slice, and the **template for every R5 modal**. A row's caret (a trailing always-on
chrome column — *not* a hideable data column, so it stays out of the `cols`/URL/picker machinery,
and the resize island skips it because it has no `data-col-id`) sets `$_rowMenu` + `$_rowMenuSplit`
and opens one shared floating `#row-actions-menu`. Its item `@get`s
`/transactions/:id/split-editor`, which patches the modal into `#modal-root`. The modal idiom:

- **Open** = `@get` patches a server-rendered fragment into `#modal-root`; the dialog
  (`[data-split-editor]`) carries `data-amount` + a JSON `data-seed` (`view/split-editor-seed`,
  pure + tested — magnitude string + signed `seed-cents` so a mixed-sign part round-trips).
- **Interact** = the `split-editor` island (MutationObserver on `#modal-root`) owns the rich
  client widget: builds rows from the seed, runs the live balance math via the already-tested
  `lib/splitMath`, a native hierarchical category `<select>` (no second Zag instance), add/remove/
  fill. Pure ephemeral UI — no round-trips while typing.
- **Save** = the island serialises the *signed* payload into the `#split-courier` hidden input;
  its `data-on:change` sets `$splitValue` + `@put`s `/transactions/:id/splits`. The handler parses
  it (`view-state/parse-splits-value`), captures the prior parts (`db.transactions/current-splits`)
  as the command `:before`, and applies a **`:set-splits` command** (full-replace via
  `db/set-splits!`; `[]` un-splits) — so splits inherit undo/redo + lingering + faceted counts for
  free. The PUT response runs the shared `edit-response` **with `:close-modal?`**, which re-patches
  `#modal-root` empty → the modal closes server-side. Cancel/Esc/backdrop close client-side (the
  island wipes `#modal-root`).

New routes: `GET /transactions/:id/split-editor`, `PUT /transactions/:id/splits`. Verified
`e2e/v2-split.mjs` 12/12 (menu → modal → live balance → save morphs to split parent+parts + closes
→ undo un-splits); full v2 suite stays green (90 checks / 11 specs). **Deferred to later R5:** split
*child* rows aren't keyboard-navigable / inline-editable (no `data-cell`); split-part reviewed
checkboxes are read-only (`db.transactions/set-split-reviewed!` exists for when they aren't).

## R5b — transfer match/review modals (DONE — 2026-06-21)

The second R5 slice, and the **island-less** variant of the modal idiom (interactions are plain
`@put`s, no client widget needed):

- **Per-row Match/Unmatch.** A second row-actions item reads "Match transfer" / "Matched transfer"
  off `$_rowMenuMatched` (set by the caret alongside `$_rowMenuSplit`) and `@get`s
  `GET /transactions/:id/match`. The server renders the right variant: **unmatched** → a
  `db.transfers/match-candidates` list where each candidate is a button that
  `@put /transactions/:id/match/:partner`; **matched** → the partner card (from the already-pulled
  `:transaction/transfer-pair`) + an Unmatch button (`@put /transactions/:id/unmatch`).
- **Bulk Review.** A toolbar "Review transfers" `@get`s `GET /transactions/review-transfers` → the
  `db.transfers/suggest-matches` list. Each row Confirms (`review/:out/confirm/:in`) or rejects
  (`review/:a/reject/:b`, "Not a transfer"); both **act in place** — the handler re-patches
  `#review-list` with the recomputed (smaller) suggestions while the modal stays open (a new
  `edit-response :after-patch` hook), so the acted-on pair drops out.
- **Commands.** All four mutations go through the command log: `:set-match` (value = partner-id to
  confirm, nil to unmatch; one type, undo flips direction) and `:reject-match` (value true/false;
  new `db.transfers/unreject!` is the clean inverse since a suggestion-reject had no link). So
  match/unmatch/reject all undo/redo.
- **Island-less close.** No island wipes `#modal-root`, so the backdrop/Esc/Close use the
  `close-modal-js` literal directly. **Gotcha:** Datastar has **no `__self` on-event modifier** —
  `data-on:click__self` silently no-ops the modifier, so the backdrop closed on *every* bubbled
  click (including Confirm). Guard the backdrop with `evt.target === el` in the expression instead
  (`backdrop-attrs`).

New routes: `:id/match` (GET) · `:id/match/:partner` + `:id/unmatch` (PUT) · `review-transfers`
(GET) · `review/:out/confirm/:in` + `review/:a/reject/:b` (PUT). Verified `e2e/v2-match.mjs` 9/9
(menu → candidates → confirm → row reads "Matched" → unmatch → undo re-matches) + `e2e/v2-review.mjs`
9/9 (Confirm and Reject each drop the pair from the list in place). **Deferred:** a matched transfer
has no in-row marker yet — only the row-menu reflects it (the `.transfer-status` CSS is carried over
for a future in-cell ⇄ marker).

## R5c — category rollup pane (DONE — 2026-06-21)

The last R5 slice — a server-rendered summary pane, no island, no new routes (clicks reuse the
existing filter signals + `/transactions/rows`).

- **Pure fn.** `web.view/category-rollup [txs categories]` ports `frontend/lib/categoryRollup.ts`
  faithfully (all 11 tests ported to kaocha): splits attribute to each part's category; an unsplit
  tx to its category; a missing category → an Uncategorized bucket split by sign. Single-level
  hierarchy — a group row carries its parent + every **same-type** child (so one click filters the
  whole group, and a misconfigured cross-type child stays in its own section). Income/expense/
  transfer sections with magnitudes + a **signed** grand total (transfers excluded).
- **Render + place.** `rollup-pane` renders the `#category-rollup` aside (sections → rows →
  subtotal, then a Net line) as a sibling of the table `.card` in `.transactions-layout`. The
  carried-over `category-rollup.css` + `dashboard.css` already lay it out as a 300px column that
  fills the fixed-viewport workspace row and scrolls internally (stacks under the table below
  1100px) — **zero new CSS**.
- **Click = filter, reusing the funnel signals.** A row is "active" when `$filter.category` exactly
  equals its ids (`data-class` → `.is-active`), or `$uncat` for the Uncategorized row. Clicking
  toggles that filter (set ⇄ clear) and `@get`s the rows. Because it writes the same
  `$filter.category` the header funnels bind, the funnel checkboxes + the active-filter chips stay
  in sync for free. **Gotcha:** the ternary branches join their assignments with the **comma
  operator** — a `;` inside the `(...)` is a JS syntax error that Datastar surfaces as a
  `GenerateExpression` console error (the trailing `; $page = 0; @get(...)` after the ternary is
  fine — those are top-level statements).
- **Whole-month, re-patched on edits.** The pane summarizes the whole month (a stable overview +
  nav), so a filter/sort change leaves it unchanged (the active highlight updates client-side); a
  recategorize/split moves money between rows, so `edit-response` re-patches `#category-rollup` by
  id (fetching categories) alongside the tbody/counts.

Verified `e2e/v2-rollup.mjs` 14/14 (pane renders sections + Net; click a category → table filters
to it + row highlights + chip appears; click again → clears). One pre-existing spec adjusted:
`v2-resize` now drags the payee column well past its content width, because the rollup pane narrows
the table card (so auto-fit squeezes payee to its min on load) and a small drag would no longer
exceed the content width that double-click fits to.

## Small follow-ups (low priority)

- **Unused/dead CSS**: the `.table-dense` rule (cp1) is now unused (the table is `.table-resizable`);
  `.pagination-nav-button` still re-derives the icon-button footprint (fold into the shared rule);
  `.nav-links .button { text-decoration:none }` is dead (base `.button` carries it now).
- **e2e spec filenames** still `v2-*.mjs` (cosmetic; content + URLs are canonical). Harness still
  borrows Playwright from `frontend/node_modules` (move before Phase 5 deletes `frontend/`).
- **Known small gaps**: column widths aren't URL-persisted (auto-fit on load only); the description
  editor opens via grid-nav but doesn't Enter-walk-the-column; page index is 0-based in the URL.
- **SSE multi-patch ordering**: edit responses patch tbody → pagination → counts → funnel lists →
  chips → undo-redo as separate events (applied in order; imperceptible). e2e gates on the last
  patch where it matters.
- **Faceting cost**: `facet-counts` + the 3 funnel options each run an extra `filter-txs` pass per
  view change (`patch-filter-feedback!`). Trivial at a month's size; revisit if months get huge.

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
