# Spike: replacing the React frontend with Replicant (SSR) + Datastar

**Question.** Can we replace the standalone React Router frontend with reactive,
server-rendered pages using [Replicant](https://replicant.fun) for hiccup→HTML on
the JVM and [Datastar](https://data-star.dev) for hypermedia interactivity — given
how complex our table interactivity is (spreadsheet keyboard nav, optimistic
overlays, inline edit, splits, transfer matching, CSV wizard)?

**Verdict: feasible, with one structural caveat.** The architecture composes
cleanly on our existing stack, and a *working* end-to-end prototype in this folder
passes 22/22 browser checks covering the patterns we were most worried about
(including the full TanStack-Table / downshift feature set). The
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
✅  optimistic flip is instant (<200ms, no round-trip)  [60ms]
✅  write-behind → SSE patched counts chip (server-authoritative)  [12 → 13]
✅  client-side search filters instantly (only Spotify row)  [1 rows]
✅  click selects a cell
✅  horizontal arrow nav hops editable columns  [2:tx:category → 2:tx:reviewed]
✅  vertical arrow nav preserves column, changes row  [3:tx:description]
✅  single keystroke nav latency < 5ms (pure client)  [0.30ms]
✅  Space toggles reviewed (island → Datastar handler)  [true→false]
✅  Enter opens inline editor & focuses input
✅  inline edit commits optimistically to the cell  [ EDITED]
✅  combobox island loaded
✅  Enter on a category cell opens the combobox (grid→combo handoff)
✅  Escape closes the combobox
✅  combobox type-ahead filters & highlights  [Dining]
✅  combobox Enter selects → cell updates optimistically  [Dining]
✅  category persisted via JSON API (survives reload)  [Dining]
✅  column hide toggles a column off (pure client)
✅  header filter shows only matching account (Savings)
✅  column resize drag widens the column  [369 → 485px]
----------------------------------------------------
22/22 checks passed
```

**Round 2** added the TanStack-Table / downshift features specifically, because
"we render with TanStack but use much more than rendering" is the right concern:

| Feature | TanStack/downshift today | Path proven in the spike | Result |
|---|---|---|---|
| Category combobox | downshift | `combobox.js` island (~110 LOC): type-ahead filter, arrow highlight, Enter select, Escape/click-outside, **position:fixed portal** escaping table overflow | ✅ |
| Header filter (by account) | TanStack column filters | Datastar array signal + `data-show`, pure client | ✅ |
| Column show/hide | TanStack visibility | Datastar `cols.*` signals + CSS, pure client | ✅ |
| Column resize / auto-fit | TanStack column sizing | `table-tools.js` island (~50 LOC): pointer-drag + dbl-click auto-fit, writes `<col>` widths | ✅ |
| JSON API coexists | — | combobox persists via `PUT /tx/:id/category` **as JSON**, not Datastar, same server + data layer | ✅ |

Mapped to the round-1 experiments:

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

6. **Datastar's checkbox-array binding seeds one empty string per unchecked box.**
   Three `data-bind="acct"` checkboxes left the signal as `["","",""]`, not `[]`,
   so a `$acct.length === 0` "no filter" guard never fired and every row hid.
   Fix: count non-empty entries (`$acct.filter(x => x).length`). A real gotcha for
   any multi-select filter.

7. **Dropdown toggle vs. `data-on:click__outside` race.** A menu that closes on
   outside-click will treat the click on its own toggle button as "outside" and
   close immediately. Fix: `__stop` on the toggle so the open-click doesn't bubble
   to the document `__outside` listener.

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
- **Interaction-polish papercuts.** Mature widget libraries (downshift, TanStack)
  encode years of edge-case fixes — focus management, full ARIA, IME/composition,
  touch, scroll-into-view, key-repeat races. Hand-rolled islands start without
  them. The spike already hit one: Tab inside the combobox trapped focus instead
  of closing-and-advancing (a one-line `data-on`/island fix, but downshift gives
  it free). Expect a tail of these on any *rich* widget you reinvent.

  Where this risk does and doesn't live:
  - **Low/none**: table chrome (filters, hide, resize, search, sort) — shallow,
    declarative. And the grid keyboard nav + drag-reorder, which you *already*
    hand-roll today — migrating them adds no new edge-case ownership.
  - **Concentrated**: the category combobox (was downshift) and, to a lesser
    degree, the split editor. ~1–2 widgets, not the whole UI.
  - **Mitigation that retires most of it**: a Datastar "island" is just a DOM node
    + JS — that JS can mount a *mature headless* widget. You don't have to reinvent
    downshift; mount it (a one-component Preact/React island), or a framework-
    agnostic headless combobox (Zag.js, Ariakit, a Shoelace web component) inside
    the island. So per-widget you *choose* hand-roll vs. wrap; the papercut tax is
    opt-in, not a blanket cost.

Suggested path if pursued:
1. Bump http-kit to 2.9.x and confirm nothing in the current server regresses
   (this is independently useful and de-risks the biggest unknown).
2. Migrate a **read-only** page first (e.g. `/setup` account list) to
   Replicant+Datastar behind a route, running alongside React.
3. For the one high-papercut widget (the combobox), prototype it as a *headless-
   library* island (Zag.js or a mounted downshift) rather than hand-rolled — to
   confirm the "wrap, don't reinvent" mitigation before relying on it.
4. Migrate the transactions table next, reusing the existing pure modules as
   islands. Keep React for anything not yet ported.
5. Re-evaluate once the table is shipped — that's where 80% of the value and risk
   concentrate, and this spike shows it lands.

If the pre-1.0 dependency risk is unacceptable, the same *patterns* (server
renders HTML, debounced write-behind, optimistic signal + SSE reconciliation,
pure-logic islands) are reusable without betting the app on these specific libs.

---

## 7. LOC estimate

Measured today (`frontend/app`, excluding tests):

| Bucket | LOC | Fate |
|---|---:|---|
| `components/` (React) | 4,294 | → Clojure hiccup views |
| `lib/` | 3,878 | mixed: API client + URL/state codecs + overlay/write-behind plumbing → **deleted** (Datastar + server state); pure logic (`gridNavigation`, `splitMath`, `dragAndDrop`, `columnAutoSizing`, `categoryHierarchy`) → **islands** |
| `routes/` (loaders/actions/JSX) | 1,634 | → Clojure handlers + hiccup |
| `hooks/` | 499 | → mostly deleted (Datastar) |
| **Total app TS/TSX** | **10,399** | |
| CSS (`styles/`) | 3,297 | **kept ~as-is** (carries over) |
| Tests | 1,274 | pure-logic tests follow the islands; Playwright e2e mostly reusable |

Projected target (hand-written application code):

| New code | Est. LOC | Basis |
|---|---:|---|
| Clojure hiccup views + SSE/hypermedia handlers | ~1,800–2,500 | the spike renders the *entire* transactions table + 8 interactions in **~230 lines** of `views.clj`; scale up for modals, setup, CSV wizard |
| Vanilla-JS islands | ~600 | spike's three islands are ~350; add combobox a11y, split editor, sorting |
| **Total** | **~2,400–3,100** | vs. **10,399** today |

**Headline: roughly a 70% cut in hand-written frontend application code**
(~10.4k TS/TSX → ~2.5–3k Clojure+JS), and that *understates* the win because it
also **removes an entire dependency tree and build pipeline**: React, React-DOM,
React-Router, `@tanstack/react-table`, downshift, zod, plus the JSON API-client
and the optimistic-overlay / write-behind / URL-state-codec plumbing that
hypermedia makes unnecessary. CSS (~3.3k) carries over largely untouched.
(`react-plaid-link` is the one dependency you likely keep as a small island — the
Plaid Link SDK is JS-only.) The islands need no bundler — the spike's are
hand-authored ES modules served as static files.

Caveat: this is an estimate from one (large, representative) view plus a module
inventory, not a line-by-line port. Treat it as ±15%.

## 8. Answers to your three questions

**Can we really drop downshift without losing combobox functionality?** Yes —
verified. `combobox.js` (~110 LOC) reproduces downshift's core: type-ahead
filtering, arrow-key highlight with clamping, Enter-to-select, Escape/click-outside
to close, and **`position:fixed` portal positioning** so the dropdown escapes the
table's overflow (downshift's headline concern). The honest caveat: downshift's
*other* value is exhaustive **ARIA** (`role=combobox`, `aria-activedescendant`,
`aria-expanded`, live-region announcements) and a pile of battle-tested
focus/mobile edge cases. The spike didn't build all of that. Reimplementing it is
maybe ~50 more lines of attributes + care — doable, but it's real work and the one
place downshift genuinely earns its keep. So: removable, but budget for
accessibility.

**Keep the JSON API for future mobile/other clients — is that duplicative?**
Agreed, and no, barely. The spike already runs both: the combobox persists through
`PUT /tx/:id/category` as plain JSON while the rest of the page is Datastar HTML/SSE
— same server, same `data` layer. With reitit you'd expose two route trees (or
content-negotiate on `Accept`) over **one shared service layer**; malli validates
both. The only duplication is *response shaping* (HTML fragment vs. JSON body),
which is a few lines per endpoint — never business logic. The hypermedia handlers
and the JSON API are two thin presentations of the same core.

**TanStack Table — we use header filters, column resize, hiding columns, not just
rendering. Do those port?** Yes, all three are in the 22/22 run:
- **Header filters** → Datastar array signal + `data-show` per row. Pure client,
  instant. (Sorting, not built, is the same shape: a signal, or `@get?sort=` for a
  server re-render.)
- **Hiding columns** → `cols.*` boolean signals toggling `hide-<id>` classes on the
  table via `data-class`. Pure client.
- **Column resize + auto-fit** → a ~50-line pointer island writing `<col>` widths.
  In the app these widths would persist to the URL per your URL-view-state rule.

The one place TanStack would still win is **virtualization** for very large tables.
Yours is monthly transactions (hundreds of rows), so you don't need it — but if a
future "all transactions, all time" view appears, a virtualized list is the kind
of thing you'd keep as a dedicated island (or reconsider). Everything you use
*today* ports.

## 9. Island layer: TS + Zag vs CLJS (prototype data)

Decision under test: now that React is going entirely, what do the islands run on?
Built a **TS + Zag.js (vanilla adapter)** combobox to get real numbers
(`islands/combobox-zag.ts`, served at `?combo=zag`, verified by `verify-zag.mjs`).

What Zag's vanilla adapter cost & delivered:
- **~100 LOC of glue** (build the DOM, subscribe to the machine, apply its
  prop-getters via `spreadProps`, persist on select) — see `islands/combobox-zag.ts`.
- **Build: 23ms** (`esbuild --bundle --minify`, zero config).
- **8/8 browser checks**, including the a11y the hand-rolled one omits:
  `role=combobox/listbox/option`, `aria-expanded`, `aria-controls`, and
  **`aria-activedescendant` tracking the highlighted option** (screen-reader virtual
  focus) — plus `@zag-js/live-region`, `dismissable`, `interact-outside`, popper
  positioning, all for free.

Size (gzipped), for context:

| Asset | gzip | note |
|---|---:|---|
| `datastar.js` (runtime, every page) | 13.3 KB | the framework |
| hand-rolled `combobox.js` | 1.8 KB | **no real ARIA** |
| **`combobox-zag.js` (TS+Zag)** | **32.4 KB** | full WAI-ARIA combobox |
| `grid-nav.js` | 3.3 KB | |
| `table-tools.js` | 0.9 KB | |

~18 KB of the Zag bundle is `@floating-ui` (positioning) + `@zag-js/core`/`dom-query`/
`dismissable` — **shared infrastructure**, so a *second* Zag widget (menu, dialog,
date-picker) adds only its machine (~5–15 KB), not another 32 KB. The hand-rolled
1.8 KB is the honest floor *without* real accessibility.

### So what are the real alternatives? (and is CLJS-hand-roll the only one?)

No — "hand-roll everything in CLJS" is only the *worst* corner of the CLJS option.
The island layer has four realistic shapes:

| Option | Lang(s) | A11y widgets | `.cljc` share w/ backend | Toolchain | Verdict |
|---|---|---|---|---|---|
| **1. TS + esbuild + Zag** (prototyped) | Clojure + **TS** | Zag (mature, free a11y) | ✗ | esbuild (trivial) | lower-risk default; reuse existing `.ts` + tests |
| **2. CLJS + shadow + web-component widgets** | **Clojure only** | a headless **web component** (`[:my-combobox]` in hiccup, DOM events — clean interop, no JS prop-getters) | ✓ | shadow-cljs | the one-language path that *keeps a11y cheap* |
| **3. CLJS + shadow, hand-rolled a11y** | Clojure only | you own the ARIA tail | ✓ | shadow-cljs | one language, but the papercut tax now in CLJS |
| **4. CLJS + Zag via interop** | Clojure only | Zag | ✓ | shadow-cljs | rejected — fighting JS-shaped prop-getters |

The decision is **not** "Zag-TS vs hand-roll-CLJS." It's really:

- **TS+Zag keeps a second language.** You still write+build TS (a thin island layer,
  not a SPA, but TS nonetheless). You *don't* get `.cljc` logic sharing
  (`splitMath`/category rules live once, run on server+client).
- **CLJS gives one language end-to-end** + `.cljc` sharing + Replicant on the client.
  To keep a11y cheap there, you reach for **web components** (option 2), which
  interop with Replicant/Datastar as plain custom elements — *not* Zag-from-CLJS.
  Cost: shadow-cljs, a CLJS runtime baseline (~tens of KB gz, ballpark Zag), porting
  the existing pure `.ts` modules to `.cljc` once, and — the real variable — the
  thinner ecosystem of *high-quality headless combobox web components* (Zag/downshift
  are more mature here than any framework-agnostic web component).

**Bottom line for "is Zag worth foregoing CLJS":** Zag is excellent and cost ~100
LOC + 32 KB for a genuinely accessible combobox — but choosing it is a vote to keep
TS as a second language and forgo server/client code sharing. If one-language +
`.cljc` unification is a primary goal, the honest comparison is **TS+Zag** vs
**CLJS + web-component widgets** — and the deciding question is whether a
framework-agnostic widget (web component) meets your a11y bar as well as Zag does.
That's the one piece still worth a prototype (a CLJS island + a headless web-
component combobox) before committing.

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
- `src/spike/server.clj` — http-kit routing, the SSE handlers, and the JSON
  category endpoint (showing the JSON API coexists).
- `resources/public/grid-nav.js` — the keyboard-nav island (ported reducer).
- `resources/public/combobox.js` — the category combobox island (downshift replacement).
- `resources/public/table-tools.js` — the column resize / auto-fit island.
- `resources/public/spike.css` — the Ledger styling.
- `resources/public/datastar.js` — vendored Datastar v1.0.2 runtime (34 KB).
- `verify.mjs` — the Playwright verification (22 checks).
