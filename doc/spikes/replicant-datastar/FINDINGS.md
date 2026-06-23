# Spike: replacing the React frontend with server-rendered hiccup + Datastar

> **Status: spike complete; decision acted on.** This is a *summary* of the research
> that led to removing React. The prototype code (its own `deps.edn`, server, islands,
> CLJS/web-component experiments, Playwright `verify*.mjs`, vendored runtime) has been
> **deleted** — it served its purpose. The real implementation shipped and lives in
> `backend/src/finance_aggregator/web/` + `islands/` + `e2e/`; orientation is in
> `doc/plans/datastar-handoff.md`. The full original record is in git history if needed.

## The question

Could we drop the standalone React Router frontend and render reactive, server-rendered
pages on the JVM (hiccup → HTML) with [Datastar](https://data-star.dev) for hypermedia
interactivity — given how complex the table is (spreadsheet keyboard nav, optimistic
overlays, inline edit, splits, transfer matching, CSV wizard)?

## Outcome

**GO — feasible and architecturally attractive.** A working end-to-end prototype passed
22/22 real-Chromium checks covering the patterns we were most worried about, including the
full TanStack-Table / downshift feature set (header filters, column resize/hide, type-ahead
combobox with a `position:fixed` portal, optimistic toggle + SSE-reconciled counts, inline
edit, client search, spreadsheet keyboard nav).

The realistic end state — and what we built — is **server-rendered hiccup + Datastar for
~80% of the UI, plus a handful of small JS "islands"** for the latency/pointer-heavy 20%.
That deleted React, React Router, TanStack Table, downshift, and the JSON-API/optimistic-
overlay plumbing, unifying rendering in Clojure. Projected ~70% cut in hand-written
frontend code (~10.4k TS/TSX → ~2.5–3k Clojure + JS); CSS carried over largely untouched.

### Why it fit this app
- Backend is already the source of truth (Datalevin + computed fields); "dumb frontend"
  is a stated value — hypermedia is the natural shape.
- The frontend was already SSR (React Router loaders/actions) — this was "drop React's
  client runtime," not "add SSR."
- The hardest interactions were *already* isolated as pure, React-free modules
  (`gridNavigation`, `splitMath`, `dragAndDrop`, `columnAutoSizing`), so they ported to
  islands almost verbatim instead of being rewritten.

### The one structural caveat
Latency-critical **spreadsheet keyboard nav cannot ride server round-trips** — it must stay
client-side JS (measured 0.20ms/keystroke locally; even 20ms over the wire would wreck the
feel). This was fine: the keymap was already a pure state machine that ported to a ~120-line
vanilla-JS island. Datastar's intended escape hatch is exactly "drop to a JS island and bind
through signals," which is what the islands do.

## The irreducible JavaScript (islands)

Everything else became Clojure hiccup + Datastar attributes. The islands that survive:
keyboard grid nav, category combobox, split-editor math/UI, drag-drop reorder, column
resize — a large fraction lifted from existing pure modules. (`react-plaid-link` stays as a
small island; the Plaid Link SDK is JS-only.)

## Island layer: TS+Zag vs CLJS (both built and measured)

The remaining decision was what the islands run on. Both options were prototyped end-to-end:

- **TS + esbuild + Zag.js (vanilla):** ~100 LOC glue, esbuild build ~23ms, full WAI-ARIA
  combobox for free (`aria-activedescendant`, live-region, dismissable, popper). Smooth,
  mature, predictable. Bundle ~32 KB gz self-contained; a 2nd Zag widget adds only its
  machine (shared `@floating-ui`/core infra).
- **CLJS (shadow/cljs.main) + headless web-component:** one language end-to-end, Replicant
  on the client, and — the genuine prize — **proven `.cljc` logic sharing**: one file
  (`cents->str`, category rules) compiled to both targets and ran on the JVM *and* in the
  browser. Cost: ~39 KB gz runtime baseline (amortized across all islands), Closure build
  times, a thinner ecosystem of high-quality headless web components, and a CLJS-specific
  `:advanced` property-renaming tax on every JS↔CLJS seam.

**Chosen: TS + Zag (2026-06-19); CLJS deferred.** It's the lower-risk default and reuses the
existing `.ts` modules + tests. The `.cljc` sharing payoff is real and remains the reason to
revisit CLJS later.

> **Honest open item for the deferred CLJS path:** despite the CLJS combobox verifier going
> green, it was still observed **failing on real keyboard interaction** — a test/repro gap,
> not a clean bill of health. Resolve that and add coverage for the exact failing path
> before relying on a CLJS island.

## Gotchas that became conventions

These were learned by building it and now shape the real code (see `datastar-handoff.md`):

1. **http-kit must be ≥ 2.9.0-beta2** — the Datastar http-kit SSE adapter requires it
   (the app was pinned to 2.8.0). Adopted in `backend/deps.edn`.
2. **The JVM hiccup renderer escapes attribute values** (and the prototype's Replicant did
   not escape `"`, corrupting `data-signals` JSON). The shipped stack uses **hiccup2**,
   which escapes correctly — but don't grep rendered HTML assuming attribute order/quoting.
3. **Server data never goes into a client expression** — it lives in morphed HTML; islands
   reconstruct their model from the server-rendered DOM (`[data-cell]`), so the HTML is the
   single source of truth, no duplicated model.
4. **Datastar can't filter `data-on:keydown` by key** (no `@keydown.enter`); fine for one
   input, not for a mode-switching grid reducer → keyboard nav is an island.
5. **Datastar checkbox-array binding seeds `["",""]` for unchecked boxes**, not `[]` — count
   non-empty entries for "no filter" guards.
6. **No `__self` modifier** — guard backdrop/outside-click with `evt.target === el`; use
   `__stop` on a toggle so its open-click doesn't bubble to an `__outside` listener.

## Migration note

Replicant (used in the spike for JVM hiccup→HTML) was ultimately replaced by **hiccup2** in
the shipped stack; the "two pre-1.0 libs + single-maintainer" risk called out here was a
real factor in that choice. Everything else — the patterns (debounced write-behind,
optimistic signal + SSE reconciliation, pure-logic islands, URL-persisted view state) — is
what the production code now uses.
