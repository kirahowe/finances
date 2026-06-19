# Spike: replacing the React frontend with Replicant (SSR) + Datastar

**Question.** Can we replace the standalone React Router frontend with reactive,
server-rendered pages using [Replicant](https://replicant.fun) for hiccup→HTML on
the JVM and [Datastar](https://data-star.dev) for hypermedia interactivity — given
how complex our table interactivity is (spreadsheet keyboard nav, optimistic
overlays, inline edit, splits, transfer matching, CSV wizard)?

**Verdict: feasible, with one structural caveat.** The architecture composes
cleanly on our existing stack, and a *working* end-to-end prototype in this folder
passes 13/13 browser checks covering the patterns we were most worried about. The
caveat is the one we predicted: the latency-critical spreadsheet keyboard
navigation **cannot** ride server round-trips and must stay client-side JavaScript.
But that turns out to be cheap, because our keyboard layer (`gridNavigation.ts`)
is *already* a pure, React-free state machine — it ports to a ~120-line vanilla-JS
"island" almost verbatim. The realistic end state is **server-rendered hiccup +
Datastar for ~80% of the UI, plus a handful of small JS islands** for the
latency- and pointer-heavy 20% (keyboard grid, category combobox, split math,
drag-reorder, column resize). That deletes React, React Router, TanStack Table,
and downshift, and unifies rendering in Clojure — at the cost of adopting two
pre-1.0 libraries and bumping http-kit.

This document records what I proved, the architecture, a feature-by-feature
migration map, the real gotchas I hit, and a recommendation. The prototype is in
this directory; see [How to run](#how-to-run-the-spike).

---

## 1. What the prototype proves

`src/spike/` is a self-contained http-kit server (no Datalevin, in-memory fake
data) that renders one transactions table — a realistic slice of the real app:
normal rows, a split transaction (parent + child parts), signed amounts, reviewed
checkmarks, sticky header, the "Ledger" visual language. Every interaction is
wired with Datastar attributes produced by Replicant on the JVM. `verify.mjs`
drives it in real Chromium (the frontend's Playwright):

```
✅  island loaded  [grid-nav island ready: 43 navigable rows]
✅  no page errors
✅  Datastar bound initial state (tx3 checked ✓)
✅  optimistic flip is instant (<200ms, no round-trip)  [56ms]
✅  write-behind → SSE patched counts chip (server-authoritative)  [12 → 13]
✅  client-side search filters instantly (only Spotify row)  [1 rows]
✅  click selects a cell
✅  horizontal arrow nav hops editable columns  [2:tx:category → 2:tx:reviewed]
✅  vertical arrow nav preserves column, changes row  [3:tx:category]
✅  single keystroke nav latency < 5ms (pure client)  [0.20ms]
✅  Space toggles reviewed (island → Datastar handler)  [true→false]
✅  Enter opens inline editor & focuses input
✅  inline edit commits optimistically to the cell  [ EDITED]
----------------------------------------------------
13/13 checks passed
```

Mapped to the four experiments:

| # | Experiment | App pattern it stands in for | Result |
|---|------------|------------------------------|--------|
| 1 | Reviewed toggle: instant optimistic flip + **debounced write-behind** + server-authoritative count chip patched via SSE | `reviewedOverlay.ts` + `useWriteBehind` + ["optimistic projection"](../../..) memory | ✅ flip 56ms, counts patched server-side |
| 2 | Inline description edit: click→input, type, Enter commits optimistically | `EditableDescriptionCell.tsx` | ✅ |
| 3 | Client-side search filter, zero round-trips | `searchTransactions.ts` | ✅ instant |
| 4 | **Spreadsheet keyboard nav** (the crux): arrows/Tab/Home, column-preserving vertical move, split-row handling, Space-toggle, Enter-to-edit | `gridNavigation.ts` + `useGridNavigation.ts` | ✅ **0.20ms/keystroke** |

The keystroke latency (0.20ms) is the headline: navigation runs entirely in the
browser, so it is exactly as instant as React — because it's the *same logic*,
just without React around it.

---

## 2. The architecture that works

```
Browser                                   JVM (Clojure, http-kit)
┌──────────────────────────────┐          ┌─────────────────────────────────┐
│ datastar.js (34 KB, vendored)│  GET /   │ Replicant: hiccup → HTML string  │
│  · signals ($reviewed,$search)│◄─────────│  (replicant.string/render)       │
│  · data-* bindings           │   HTML   │                                  │
│  · @put/@get → SSE            │          │                                  │
│                              │  @put    │ Datastar Clojure SDK:            │
│  data-on-signal-patch ───────┼─────────►│  get-signals → parse JSON        │
│   __debounce.700ms           │          │  business logic (set-reviewed!)  │
│                              │   SSE    │  patch-elements! ← Replicant     │
│  DOM morph (counts chip) ◄───┼──────────│   (counts chip re-rendered)      │
│                              │ fragment │                                  │
│ JS islands (vanilla):        │          │                                  │
│  grid-nav.js ← ports          │          │                                  │
│   gridNavigation.ts verbatim │          │                                  │
└──────────────────────────────┘          └─────────────────────────────────┘
```

Dependencies (all resolved & exercised — see `deps.edn`):

- `no.cjohansen/replicant 2025.12.1` — renders hiccup to an HTML **string on the
  JVM** via `replicant.string/render` (it's `.cljc`; the JVM path uses a
  `StringBuilder`). This is the load-bearing fact that makes the whole idea
  possible: the *same* hiccup we'd write for a CLJS client renders server-side.
- `dev.data-star.clojure/sdk 1.0.0-RC11` + `dev.data-star.clojure/http-kit 1.0.0-RC11`
  — the official Datastar Clojure SDK with a first-class **http-kit adapter**,
  which is exactly the server we already run. `->sse-response` + `on-open` +
  `patch-elements!` / `patch-signals!` / `get-signals` is the entire surface used.
- `http-kit 2.9.0-beta3` — **required**: the adapter docs state http-kit
  `< 2.9.0-beta2` does not stream SSE correctly. The real app pins **2.8.0**, so a
  migration must bump http-kit (see gotchas).

The request lifecycle is pure hypermedia: the server sends HTML; the client's
declarative `data-*` attributes drive local reactivity and fire `@put`/`@get`;
those hit normal Clojure handlers that read signals, mutate state, and stream back
HTML fragments (re-rendered by Replicant) which Datastar morphs into the DOM by
`id`. There is no JSON API contract, no client store, no re-derivation — which
lines up exactly with the project's "backend authoritative, dumb frontend" stance.

---

## 3. Feature-by-feature migration map

Drawn from a full inventory of `frontend/app` (~11,700 LOC), refined by what the
spike actually demonstrated. "Path" is how each feature would be rebuilt.

| Feature | Today | Path under Replicant+Datastar | Difficulty |
|---|---|---|---|
| Transaction table render | TanStack Table | Replicant hiccup (SSR) | Trivial ✅ proven |
| Reviewed toggle + counts | overlay + write-behind | Datastar signal + `data-on-signal-patch__debounce` + SSE patch | Easy ✅ proven |
| Search | client filter | Datastar signal + `data-show` per row | Easy ✅ proven |
| Filters / hide-transfers / month / pagination / column visibility | URL state | `data-bind` + `@get` with query params (or pure client `data-show`) | Easy |
| Sorting | TanStack | `@get?sort=` → server re-render, or client island | Easy |
| Inline description edit | controlled input | class-swap + `data-bind` + `@put` | Easy ✅ proven |
| Transfer match / review modals | React modals | server-rendered modal + `@get`/`@post` | Easy–Moderate |
| Bulk category modal | client parse | `@post` preview → server-rendered table | Moderate |
| WebSocket sync progress | `useSyncSocket` | **Datastar's native model is SSE** — a long-lived `->sse-response` broadcasting `patch-elements!`; arguably *simpler* than the WS | Moderate |
| CSV import wizard | multi-step + file in memory | server-held step state; file in a temp/session slot | Moderate–Hard |
| **Keyboard grid nav** | `gridNavigation.ts` (pure) | **JS island** — ports verbatim | Hard, but ✅ proven & cheap |
| Category combobox (type-ahead) | downshift | small island *or* Datastar `data-computed` filtered list | Moderate (not built) |
| Split editor + live balance | `splitMath.ts` (pure) | island reusing `splitMath` *or* `data-computed` | Moderate (not built) |
| Drag-drop category reorder | `dragAndDrop.ts` (pure) | pointer-event island, persist on drop | Moderate (not built) |
| Column resize / auto-fit | pointer + measure | pointer-event island | Moderate (not built) |

---

## 4. Real technical limits & gotchas discovered

These are the things you only learn by building it:

1. **http-kit 2.8.0 → 2.9.0-beta is mandatory.** The Datastar http-kit adapter
   requires `>= 2.9.0-beta2` for correct SSE streaming; the app pins 2.8.0. That
   means depending on a **beta** http-kit in production — the single biggest
   adoption risk here (plus Datastar itself being at RC11 and Replicant being a
   one-maintainer library). Pre-1.0 all around.

2. **Replicant's string renderer does not escape `"` in attribute values.** I
   emitted `data-signals` as JSON and got broken HTML (`data-signals="{"reviewed"...`).
   Fix: emit Datastar signal maps as a **single-quoted JS object literal**
   (`{reviewed: {tx1: false}, search: ''}`) — the style Datastar's own docs use —
   and escape any single quote in embedded data (e.g. `Trader Joe\'s`). See
   `js-str` in `views.clj`. This is a sharp edge: any Datastar expression with a
   double quote in an attribute will silently corrupt the markup.

3. **Replicant *does* escape `<script>` body text** (`&quot;`), which breaks
   embedded JSON/JS. So you can't hand the keyboard island its grid model via an
   inline `<script type="application/json">`. Fix that turned out *better* anyway:
   the island **reconstructs its model from the server-rendered DOM** (`[data-cell]`
   attributes in document order). The HTML is the single source of truth; no
   duplicated model. (For genuinely needed inline scripts you'd need a raw-string
   escape hatch outside Replicant.)

4. **Datastar can't filter `data-on:keydown` by key.** Unlike Vue's
   `@keydown.enter`, you write `evt.key === 'Enter' && …` in the expression. Fine
   for one input; *not* a vehicle for a 350-line, mode-switching, split-aware grid
   reducer. Which leads to the central finding:

5. **The keyboard grid must be a client-side island — and that's fine.** Per
   keystroke navigation over the network is a non-starter (even 20ms would wreck
   the spreadsheet feel; we measured 0.20ms locally). The crucial mitigation is
   that `gridNavigation.ts` was *designed* pure and React-free ("a thin imperative
   shell feeds the model in… the whole keymap is unit-testable without rendering a
   single component"). `grid-nav.js` in this spike is that file's `resolveIntent`
   + `navReducer` + movement helpers with the TypeScript types stripped — it ran
   first try. Datastar's intended escape hatch for exactly this is "drop to a JS
   island / web component and bind through signals," which is what we do (Space and
   the optimistic toggle interoperate island↔Datastar through normal DOM events).

---

## 5. The irreducible JavaScript

The honest accounting of what JS survives the migration (everything else becomes
Clojure hiccup + Datastar attributes):

| Island | Source today | Already pure/portable? | Rough size |
|---|---|---|---|
| Keyboard grid nav | `gridNavigation.ts` | ✅ yes | ~120 lines (proven) |
| Category combobox | `CategoryDropdown.tsx` + `categoryHierarchy.ts` | hierarchy logic yes; downshift no | ~150 lines |
| Split editor math/UI | `splitMath.ts` | ✅ math pure | ~150 lines |
| Drag-drop reorder | `dragAndDrop.ts` | ✅ manager pure | ~100 lines |
| Column resize | `columnAutoSizing.ts` | ✅ pure | ~80 lines |

Call it **~600 lines of vanilla JS islands**, a large fraction lifted from
existing pure modules — versus the current ~11,700 LOC React app plus React,
React Router, TanStack Table, downshift, and the JSON API layer that feeds them.
The islands need a tiny build step (or can be hand-authored ES modules, as here,
with zero tooling). State that the islands own (focus, drag, combobox highlight)
stays local; everything persisted flows through Datastar/SSE.

---

## 6. Recommendation

**Technically feasible and architecturally attractive — but gate it on the pre-1.0
risk, and do it as a staged migration, not a rewrite.**

Why it's attractive for *this* app specifically:
- The backend is already the source of truth (Datalevin + computed fields), and
  the team value is "dumb frontend." Hypermedia is the natural shape.
- The frontend is already SSR (React Router loaders/actions) — this isn't
  "add SSR," it's "drop React's client runtime." Rendering unifies in Clojure.
- The hardest interactions are *already* isolated as pure modules, so they port
  to islands instead of being rewritten.

Why to be cautious:
- **Pre-1.0 everywhere**: Datastar RC11, a beta http-kit requirement, Replicant
  is single-maintainer. This is the real risk, not the interactivity.
- You trade a mature, well-staffed React ecosystem for a small-community stack.
  Hiring/onboarding and library longevity are non-code costs.

Suggested path if pursued:
1. Bump http-kit to 2.9.x and confirm nothing in the current server regresses
   (this is independently useful and de-risks the biggest unknown).
2. Migrate a **read-only** page first (e.g. `/setup` account list) to
   Replicant+Datastar behind a route, running alongside React.
3. Migrate the transactions table next, reusing the existing pure modules as
   islands. Keep React for anything not yet ported.
4. Re-evaluate once the table is shipped — that's where 80% of the value and risk
   concentrate, and this spike shows it lands.

If the pre-1.0 dependency risk is unacceptable, the same *patterns* (server
renders HTML, debounced write-behind, optimistic signal + SSE reconciliation,
pure-logic islands) are reusable without betting the app on these specific libs.

---

## How to run the spike

```bash
cd doc/spikes/replicant-datastar
./run.sh                 # starts http-kit + Datastar + Replicant on :7777
open http://localhost:7777

# in another shell, browser-driven verification (uses the frontend's Playwright):
node verify.mjs          # prints the 13-check report + /tmp/spike-screenshot.png
```

`run.sh` points `JAVA_HOME` at the project's jabba-managed JDK 25 so it works in a
non-interactive shell. The spike is fully isolated: in-memory data, its own
`deps.edn`, its own port — it touches nothing in `backend/` or `frontend/`.

### Files
- `deps.edn` — pinned library coordinates (note the http-kit override).
- `src/spike/data.clj` — fake transactions shaped like the real ones.
- `src/spike/views.clj` — Replicant hiccup + Datastar attributes (the `js-str` and
  attribute-quote notes live here).
- `src/spike/server.clj` — http-kit routing + the two SSE handlers.
- `resources/public/grid-nav.js` — the keyboard-nav island (ported reducer).
- `resources/public/spike.css` — the Ledger styling.
- `resources/public/datastar.js` — vendored Datastar v1.0.2 runtime (34 KB).
- `verify.mjs` — the Playwright verification.
