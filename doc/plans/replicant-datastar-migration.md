# Replicant + Datastar migration — implementation handoff

**Status:** DECIDED (2026-06-19), ready to implement. This is the start-here brief
for the implementation fleet. The *research* (with measurements, screenshots, and a
working prototype of every hard interaction) lives in
`doc/spikes/replicant-datastar/FINDINGS.md` and the spike code beside it on branch
`spike/replicant-datastar`. Read this doc first, then mine the spike for canonical
examples — every pattern below has a runnable reference there.

---

## 1. The decision

Replace the standalone React Router frontend with **server-rendered hypermedia**:

- **Replicant** renders hiccup → HTML strings on the JVM (`replicant.string/render`).
- **Datastar** drives client reactivity + SSE-patched updates over the existing
  http-kit server (official **Datastar Clojure SDK**).
- **TS + esbuild islands** for the latency/pointer-heavy bits, using **Zag.js
  (vanilla adapter)** for headless accessible widgets (combobox, etc.).
- **Keep the JSON API** (reitit + malli) alongside the hypermedia routes for future
  mobile/other clients — one shared service layer, two presentations.
- **React, React-Router, TanStack Table, downshift, zod → removed.** Plaid: drop
  `react-plaid-link` for Plaid's vanilla Link SDK (or remove Plaid entirely).
- **CLJS islands are a deferred stretch goal** — proven viable (see FINDINGS §9) and
  much easier from this baseline, but not now. (Known open item: the spike's CLJS
  combobox still has a keyboard-interaction bug the click-path tests didn't catch.)

Why this fits the app: the backend already owns state + computed fields
([[feedback_backend_authoritative]]); the frontend is already SSR (React Router
loaders). This isn't "add SSR" — it's "drop React's client runtime" and unify
rendering in Clojure. Expected ~70% cut in hand-written frontend code (~10.4k TS →
~2.5–3k Clojure + ~600 LOC JS islands); CSS (~3.3k) carries over.

---

## 2. Target architecture

```
Browser                                JVM (Clojure, http-kit ≥ 2.9.0-beta3)
  datastar.js (13 KB gz, every page)     Replicant: hiccup → HTML string
  data-* signals / bindings  ──GET──►     reitit routes:
  @get/@post/@put            ──SSE──►       /…            hypermedia (HTML + SSE)
  DOM morph ◄── patch-elements!             /api/…        JSON (same service layer)
  TS islands (esbuild):                    Datastar Clojure SDK:
   · grid-nav  (keyboard)                    get-signals → parse JSON (BYO parser)
   · combobox  (Zag vanilla)                 ->sse-response / on-open
   · table-tools (resize)                    patch-elements! ← Replicant fragment
   · split-editor, drag-reorder              patch-signals!
```

**Dependencies to add/bump** (coordinates verified in the spike):
- `http-kit/http-kit {:mvn/version "2.9.0-beta3"}` — **BUMP from 2.8.0** (the
  Datastar SSE adapter needs ≥ 2.9.0-beta2; 2.8.0 does not stream SSE correctly).
- `dev.data-star.clojure/sdk {:mvn/version "1.0.0-RC11"}`
- `dev.data-star.clojure/http-kit {:mvn/version "1.0.0-RC11"}`
- `no.cjohansen/replicant {:mvn/version "2025.12.1"}`
- Datastar browser runtime **v1.0.2**, vendored (don't rely on CDN at runtime).
- Frontend island toolchain: `esbuild` + `@zag-js/<widget>` + `@zag-js/vanilla`
  (1.41.x). Islands are TS, bundled per-island (or one bundle); minify on build.
- Keep `metosin/reitit` + `metosin/malli` for the JSON API.

---

## 3. Reuse from the existing frontend (don't rewrite — port)

These are already pure, framework-free TS modules. Keep them as TS island logic
(+ their unit tests); only the React shell around them is discarded:

| Module | LOC | Becomes |
|---|---:|---|
| `app/lib/gridNavigation.ts` | 330 | keyboard grid-nav island core (verbatim) |
| `app/lib/splitMath.ts` | 106 | split-editor island math |
| `app/lib/dragAndDrop.ts` | 155 | category drag-reorder island |
| `app/lib/columnAutoSizing.ts` | 120 | column resize/auto-fit island |
| `app/lib/categoryHierarchy.ts` | 165 | combobox option tree |
| `app/lib/categoryReorder.ts` | 48 | reorder math |

CSS (`app/styles/**`, ~3.3k LOC) carries over largely unchanged — keep the design
tokens / Ledger language ([[feedback_design_system]], [[project_app_ia_and_design]]).

---

## 4. Established patterns (each has a canonical example in the spike)

Spike files are under `doc/spikes/replicant-datastar/`:

| Pattern | Spike reference | Notes |
|---|---|---|
| SSR page (Replicant on JVM) | `src/spike/views.clj` | `replicant.string/render`; `a` helper for `data-*` attrs |
| Datastar SSE handler (http-kit) | `src/spike/server.clj` `sync-reviewed` | `->sse-response` + `on-open` + `patch-elements!` on a Replicant fragment |
| Optimistic toggle + write-behind + server-authoritative counts | `views.clj` reviewed-cell + `data-on-signal-patch__debounce.700ms` | instant signal flip on click; **separate** debounced effect persists; server patches only derived state (counts), never the checkbox — [[feedback_optimistic_projection]] |
| Inline edit | `views.clj` payee-cell | class-swap (span ↔ input) + `data-bind` + Enter `@put`; optimistic span sync |
| Client search / filter / column-hide | `views.clj` row-show / column-picker / account-filter | pure Datastar signals + `data-show` / `data-class`; zero round-trips |
| Header filter (multi-select) | `views.clj` account-filter | checkbox-array `data-bind` → **filter empties** (see gotchas) |
| Keyboard grid nav | `resources/public/grid-nav.js` | JS island porting `gridNavigation.ts`; ~0.2ms/keystroke; island↔Datastar via DOM events; click-to-select |
| Headless combobox (Zag) | `islands/combobox-zag.ts` | `@zag-js/vanilla` subscribe→render→`spreadProps`; full WAI-ARIA free |
| Column resize / auto-fit | `resources/public/table-tools.js` | pointer-event island writing `<col>` widths |
| JSON API coexistence | `server.clj` `set-category` (PUT JSON) | same data layer, JSON response instead of SSE |
| URL view-state | (apply pattern) | column widths/visibility/sort/filters → URL params, not localStorage — [[feedback_url_view_state]] |

Verification templates: `verify.mjs` (23 checks), `verify-zag.mjs` (8). They drive
real Chromium via the frontend's Playwright (`createRequire` rooted at
`frontend/node_modules`). **Test every interaction path** — a click-only test hid a
broken keyboard path in the CLJS spike.

---

## 5. Gotchas / hard rules (learned the hard way — do not relearn)

**Replicant SSR**
1. It does **NOT escape `"` in attribute values.** Any `data-*` expression in an
   attribute must avoid double quotes — emit Datastar signals as **single-quoted JS
   object literals** (`{reviewed: {tx1: false}, search: ''}`), and escape single
   quotes/backslashes in embedded data (see `js-str` in `views.clj`).
2. It **DOES escape `<script>` body text** (`&quot;`). Don't embed JSON in a
   `<script>`. Rebuild client model from the DOM (e.g. `[data-cell]` order) or fetch
   from an endpoint.
2a. **It also escapes any pre-rendered HTML string you embed as a hiccup child**
   (same mechanism as the script-body case). Keep fragments as **hiccup** and embed
   them directly; render to a string (`h/render`) **only at the SSE boundary**
   (`patch-elements!`). A page that does `[:div (rs/render frag)]` ships
   `&lt;div…&gt;` text, not an element — and a curl text-grep won't catch it (the
   escaped text still contains the substring); the browser check will. (Phase 1.)
3. Datastar's colon attrs (`data-on:click`, `data-bind:x`) can't be Clojure keyword
   literals — build attr maps from string keys → `(keyword "...")` (the `a` helper).

**Datastar**
4. **http-kit ≥ 2.9.0-beta2** required for SSE.
5. `get-signals` returns a raw **String** (GET/DELETE, in query) or **InputStream**
   (POST/PUT, body) — parse JSON yourself (`json/read-str (slurp …)`).
5a. **But the app's global `wrap-json-request` already consumes the POST/PUT body**
   (it parses `application/json` into `:body-params`), so `get-signals`' body path
   comes back empty. And the v1.0.2 runtime sends **no `datastar-request` header** to
   branch on. The project seam is `web.hiccup/read-signals`: read `:body-params`
   (POST/PUT) else the `datastar` query param (GET/DELETE) — keyword-keyed, zero
   middleware change. Use it instead of `get-signals` directly. (Phase 1.)
6. Checkbox-array `data-bind` seeds the signal with **one empty string per unchecked
   box** (`["","",""]`), not `[]` — gate filters on `($sig.filter(x=>x).length)`,
   not `.length === 0`.
7. A menu with `data-on:click__outside` to close will treat the **toggle button's
   click as "outside"** and close immediately — put `__stop` on the toggle.
8. No key filtering on `data-on:keydown` — write `evt.key === 'Enter' && …` in the
   expression.
9. Optimistic pattern: flip the signal on click (instant) **and** persist via a
   **separate** `data-on-signal-patch__debounce` effect. Never debounce the click
   itself, and don't have the server patch the optimistic cells back.
10. SDK surface used: `patch-elements!`, `patch-signals!`, `close-sse!`,
    `get-signals` (api ns); `->sse-response`, `on-open`, `on-close` (http-kit adapter).

**Islands / build**
11. Keyboard grid nav, combobox type-ahead, split math, drag, resize **must stay
    client-side** (latency/pointer). Everything else → hypermedia round-trips.
12. Island ↔ Datastar and island ↔ island interop go through **DOM events**
    (`.click()`, `CustomEvent`) — the clean seam. Keep the JS-interop surface small.
13. Zag vanilla: `@zag-js/<widget>` + `@zag-js/vanilla` (`VanillaMachine`,
    `normalizeProps`, `spreadProps`). Pattern: instantiate machine → `subscribe`
    re-render → `connect` → `spreadProps` the prop-getters onto your DOM. ~18 KB of a
    Zag widget bundle is shared infra (floating-ui + core) that amortizes across
    widgets; a 2nd widget adds only its machine.

**Environment**
14. JDK 25 lives at `/opt/homebrew/Cellar/jabba/0.15.0/jdk/zulu@25.0.3/Contents/Home`;
    jabba isn't on a non-interactive PATH — set `JAVA_HOME` directly (see the spike's
    `run.sh`). The real app runs under overmind (`Procfile`).
15. zsh: `path` is a reserved array — never use it as a loop variable.
16. Playwright: resolve via `createRequire('…/frontend/')` from any cwd.

**CLJS (deferred — only if/when the stretch goal is picked up)**
17. `:advanced` **renames JS-created object properties** read via accessor
    (`(.. e -detail -td)`) → `undefined` at runtime. Read JS↔CLJS-boundary props by
    string key (`unchecked-get`/`goog.object`). Test every seam.
18. `.cljc` shared logic must be portable (no `Math/abs`, no `clojure.core/format`).
19. `combobox-framework` WC is finicky (autoselect-on-type fires `change`,
    focus-moves-into-listbox, Popover API top-layer, CSS `anchor()` positioning).

---

## 6. Migration plan (phased; phase 3 fans out for the fleet)

**Phase 0 — de-risk (1 task, do first, blocks nothing else if it passes)**
- Bump http-kit 2.8.0 → 2.9.0-beta3 in `backend/deps.edn`; run the full backend test
  suite (`clojure -M:test -m kaocha.runner`) and smoke the running server. Acceptance:
  green tests, SSE-capable server. This is independently useful and retires the
  biggest unknown.

**Phase 1 — scaffolding (small, sequential; unblocks everything)**
- Add Datastar SDK deps; create a Replicant base-layout render helper + the `a`
  attr helper + `js-str` (lift from the spike).
- Wire one hypermedia route end-to-end through reitit alongside the JSON API; vendor
  `datastar.js` v1.0.2; set up the TS island build (esbuild) + first island scaffold.
- Acceptance: a trivial server-rendered page with one working Datastar interaction
  + one esbuild island, served by the real backend.

**Phase 2 — read-only page first**
- Migrate `/setup` (account list) to Replicant+Datastar behind a route, React still
  serving everything else. Acceptance: visual + behavior parity, no React on that route.

**Phase 3 — the transactions table (THE work; fan out — units are mostly independent
once the table SSR shell + signal namespace exist).** Build the SSR table shell +
the `data-signals` schema first (1 task), then parallelize:
- `reviewed toggle` — optimistic + write-behind + counts (SSE).
- `inline description edit`.
- `category combobox` — Zag vanilla island (reuse `categoryHierarchy.ts`).
- `search` + `header filters` (account/institution/category) + `hide transfers`.
- `column visibility` + `column resize/auto-fit` island (reuse `columnAutoSizing.ts`).
- `sorting` (Datastar `@get?sort=` or island) + `pagination` + `month navigation`.
- `keyboard grid nav` island (port `gridNavigation.ts` verbatim).
- `split editor` modal/island (reuse `splitMath.ts`) — see [[project_splits_as_transactions]].
- `transfer match/review` modals (server-rendered + SSE) — see [[project_transfer_model]].
Each unit: server-renders its fragment, persists via existing service fns, optimistic
where the UX needs it, URL for view-state. Acceptance per unit: a Playwright check
mirroring the spike's verify pattern + parity with the React behavior.

**Phase 4 — remaining surfaces**
- CSV import wizard (server-held step state; file in a temp/session slot).
- Bulk category modal (server-rendered preview — [[feedback_bulk_confirm_ui]]).
- Plaid: vanilla Link SDK island (or remove). WebSocket sync progress → a long-lived
  Datastar SSE stream (`->sse-response` broadcasting `patch-elements!`).

**Phase 5 — delete React**
- Remove `frontend/` React app, react-router, TanStack, downshift, zod, the JSON
  API-client + overlay/write-behind/URL-codec plumbing now made redundant. Keep CSS.
  Update DEVELOPMENT.md / README / Procfile.

Full per-feature difficulty map: FINDINGS §3 (the complete inventory of all ~19
interactive features and where each lands).

---

## 7. Conventions the fleet must follow

- **No inline styles** — CSS files only (`frontend` CLAUDE.md). Carry over the design
  tokens; dark mode via `prefers-color-scheme` only ([[feedback_design_system]]).
- **Backend authoritative** — server owns business logic + derived state; the client
  reads computed fields and re-renders from server truth ([[feedback_backend_authoritative]]).
- **Optimistic projection** for persisted per-row toggles ([[feedback_optimistic_projection]]).
- **URL view-state**, never localStorage ([[feedback_url_view_state]]).
- **Workspace shell**: fixed-viewport, panes scroll internally, never drop filters
  ([[feedback_workspace_viewport_shell]]); dense Lunch-Money-style table
  ([[feedback_finance_ui_prefs]]).
- **Signed-amount** convention (inflows +, outflows −) ([[project_provider_conventions]]).
- **Commit in logical chunks**, test-drive before each ([[feedback_commit_workflow]]);
  use `gitp`. No PRs/pushes without confirmation ([[feedback_no_pr_without_confirm]]).
- **TDD**: write the Playwright/unit check before/with each unit.

---

## 8. Open items / risks to track
- a11y: Zag covers the widgets; ensure the **table itself** gets proper ARIA
  (`role=grid`/row/cell, sort state) — not provided by any library here.
- Accessibility (screen-reader) testing for the islands — budget for real AT passes.
- Plaid migration is its own task (vanilla SDK vs removal — confirm with owner).
- CLJS stretch goal: re-evaluate after the table ships; the spike's CLJS combobox has
  an unresolved keyboard bug to fix if pursued.
- Backend production push depends on provider-seam debt ([[project_backend_hardening]]) —
  coordinate so this migration doesn't collide with reconciliation/account-sync work.
