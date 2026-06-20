# Datastar server-authoritative rewrite — architecture & plan

**Status:** Phases R0 (renderer foundation) + R1 (pure view engine) done. **Branch:** `spike/replicant-datastar`. Next: R2.
Supersedes the client-heavy approach in `replicant-datastar-progress.md` for the
transactions workspace. Memory: `project_replicant_datastar_spike`.

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

## Lingering, reconsidered

Server-side it gets simpler: when a mutation would filter a row out (review it while in
Needs-review scope), the server can render that row with an `is-stale` tag and keep it
until the next view change — no per-row `$linger` signals, no signal-patch reset regex.
Decide the exact UX in R2.

## Phases (e2e specs are the behavior contract — refactor against them)

- **R0 — Renderer foundation. ✅ DONE.** hiccup2 dep; `web/render.clj`; spike converted to
  it (Replicant-free) and browser-verified (`e2e/spike-hiccup2.mjs` 5/5). Replicant stays
  on the classpath only for the not-yet-migrated `/` + `/setup`.
- **R1 — Server view engine. ✅ DONE.** `web/view.clj`: pure `view` (filter → sort →
  paginate) porting every baked rule; `web/view_test.clj` 8 tests / 32 assertions. No UI yet.
- **R2 — New server-authoritative `/`.** Toolbar (search/scope/chips/funnels) + sort +
  paginate via `GET /transactions/rows` morph; edits (reviewed/desc/category) re-render
  fragments; ephemeral state `_`-signalled; reuse grid-nav/combobox/col-resize islands.
  Settle lingering here. Search is the one latency-sensitive control — debounce + measure
  on the VPS; keep client-side only if needed.
- **R3 — Column vis/width persistence** via URL + client CSS; thin `replaceState` reflector.
- **R4 — Delete the old.** Replicant dep, `web/hiccup.clj`, `web/layout.clj`, the old
  transactions page, dead islands, the scaffold. Convert `/setup` to the hiccup2 seam.
  Full backend + e2e suites green.
- **R5 — 3e on the new pattern.** Split editor, transfer modals, rollup, row actions —
  built natively server-authoritative (split balance via `splitMath`, etc.).

## How to verify (current)

```bash
cd backend && export JAVA_HOME=/opt/homebrew/Cellar/jabba/0.15.0/jdk/zulu@25.0.3/Contents/Home
E2E_PORT=8099 clojure -M:e2e -m finance-aggregator.dev.e2e-server &
# compare: http://localhost:8099/ (client) vs http://localhost:8099/spike (server, hiccup2)
BASE_URL=http://localhost:8099 node e2e/spike-hiccup2.mjs   # 5/5
```
