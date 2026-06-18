# Spike: server-rendered frontend with Replicant + Datastar

**Status:** research complete, prototyping paused (waiting on a proper Clojure/Maven
environment). Nothing here is production code — it's a feasibility investigation.

## Question

Can we replace the standalone React Router frontend with reactive, server-rendered
pages driven by the Clojure backend, using:

- **Replicant** (https://replicant.fun) — data-driven hiccup rendering, JVM + CLJS
- **Datastar** (https://data-star.dev) — hypermedia framework: client-side signals +
  SSE-driven HTML/signal patches from the server

## What's established so far

### Backend (good fit)
- http-kit 2.8.0 + Reitit, Datalevin, pure JSON API today (no HTML/templating libs
  on the classpath yet).
- There is an official **`datastar-clojure` SDK with a first-class http-kit adapter**
  (`starfederation.datastar.clojure.adapter.http-kit`), so it drops into the existing
  server. Key fns: `->sse-response`, `patch-elements!`, `patch-signals!`, `close-sse!`;
  broadcast by holding SSE generators in an atom (same pattern as the current `/ws`
  Plaid-sync socket).
- HTML routes plug into `http/router.clj` alongside the JSON API; middleware applies
  uniformly.

### Replicant's role shrinks under Datastar
- `replicant.string/render` runs on the JVM and renders hiccup → HTML string. Good.
- BUT in a Datastar architecture Replicant's event model and DOM-diffing are bypassed
  (Datastar owns client reactivity + DOM morphing). So Replicant is effectively a
  **templating layer** here; its value over plain hiccup is modest for pure SSR.

### Datastar is much stronger client-side than HTMX
- Real client reactivity: `$signals`, `data-bind`, `data-computed`, `data-show`,
  `data-text`, `data-class`, `data-on-*` with `__debounce`/`__throttle`/`__once`
  modifiers. Several "hard" interactions (live split math, debounced inline edits)
  can be done client-side with **zero** server round-trips.
- Server pushes `datastar-patch-elements` (morph by element id) and
  `datastar-patch-signals` over SSE.
- **Caveat (licensing):** `data-persist`, `data-query-string` (URL sync),
  `data-replace-url`, view-transitions, animate are **paid Pro features**. This app
  leans heavily on URL view-state (filters/sort/columns/month/pagination), so URL sync
  needs either the Pro plugin or a hand-rolled equivalent.

### Hardest interactions to port (from the frontend inventory)
1. Optimistic transaction table — multi-layer overlays (reviewed + description) +
   debounced write-behind + split child rows + live column resize. **Highest risk.**
2. Plaid Link orchestration — external SDK + token exchange + WS sync + retry/timeout.
3. Category drag-and-drop with hierarchy + inline-create row.
4. Split modal live math (likely fully solvable client-side with `data-computed`).
5. CSV import wizard (multi-step state machine).

## Prototyping: blocked by network allowlist

- Available: babashka (bundles hiccup2, http-kit, cheshire), Java 21, node/pnpm,
  playwright **with chromium pre-installed at `/opt/pw-browsers`** (so headless
  browser testing of Datastar reactivity IS possible).
- Blocked: `repo.clojars.org` and the playwright download CDN are not on the egress
  allowlist, so Replicant and the `datastar-clojure` SDK can't be pulled as Maven deps.
  Only GitHub hosts are reachable.

## To resume (needs from the environment)

1. Clojure CLI + Maven access with **Clojars allowlisted** (the main blocker), so the
   real Replicant + `datastar-clojure` SDK + Datalevin backend can run.
2. Decision: prototype against the **real backend** (wire Datastar routes into Reitit +
   Datalevin) vs. an isolated bench. Recommendation: one thin real-backend spike of the
   single most load-bearing flow — **inline description edit with debounced optimistic
   update** — since that's the make-or-break feasibility question.

`bench/` holds throwaway scratch only (a Replicant `replicant.string` smoke test).
Downloaded artifacts (Datastar JS bundle, jars) are gitignored.
