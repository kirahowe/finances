# Pre-ship review — tightening the Datastar rewrite before shipping

**Mission for the next session.** The React→Datastar migration is *functionally* complete — every
feature is on the server-authoritative stack and the suite is green. This session is the **review +
harden + polish pass before we ship the full rewrite**: scrutinize the new code for correctness and
edge cases, close the production-readiness gaps, do an a11y/cross-browser pass, and triage what's
actually blocking ship vs. nice-to-have.

This is the **mission doc** for that session. The **resume/orientation** doc (state, file map,
architecture idiom, gotchas, run/verify) is `datastar-handoff.md`; depth/why is
`datastar-server-authoritative-rewrite.md`; memory entry `project_replicant_datastar_spike`.
**Branch:** `spike/replicant-datastar`.

> **Status (post-session).** Single-user ship was **confirmed** (no real auth in scope for v1 —
> see C). The **hardening pass is complete**: server errors are now surfaced (B), modals have a
> focus trap + restore and SSE count updates announce via `aria-live` (E), plus a dead-code cleanup.
> Shipped in commit `b80fc65`. New regression specs `e2e/v2-errors.ts` (server-error surfacing) and
> `e2e/v2-modal-focus.ts` (modal focus trap) cover the new behavior; the suite is now **16** TS
> browser specs. Remaining items below are the genuinely-open decisions/polish.

## Where we are (2026-06-22)

Migration **done**: server renders + SSE-morphs fragments; client holds only ephemeral UI state.
Replicant and the old React `frontend/` are deleted; hiccup2 is the only renderer; views are
strictly presentational (all data logic in pure, kaocha-tested fns). Features: faceted/composing
filters, inline edits with command-log undo/redo + lingering, keyboard grid nav, column
chooser/resize, URL view-state, split editor, transfer match/review modals, category rollup pane.
**Backend kaocha 326/0; 16 TypeScript browser specs green** (`e2e/*.ts`, run via Node native
type-stripping, `cd e2e && npm run typecheck`).

The last few working sessions also fixed a cluster of subtle UI bugs (see *Fragile areas* below) —
worth remembering that this surface is interaction-heavy and the bugs were non-obvious.

## How to run + verify (recap)

```bash
export JAVA_HOME=/opt/homebrew/Cellar/jabba/0.15.0/jdk/zulu@25.0.3/Contents/Home; export PATH="$JAVA_HOME/bin:$PATH"
cd backend && clojure -M:test -m kaocha.runner            # 326/0
cd islands && npm install && npm run build && npm test     # build islands + vitest (libs)
cd e2e && npm install && npm run typecheck                 # e2e TS typecheck
# seeded server, then the browser suite:
cd backend && E2E_PORT=8099 E2E_DB_PATH=./data/e2e.db clojure -M:e2e -m finance-aggregator.dev.e2e-server
for f in e2e/*.ts; do s=$(basename "$f" .ts); curl -s -X POST localhost:8099/e2e/reset >/dev/null; BASE_URL=http://localhost:8099 node "e2e/$s.ts"; done
```
Note: the seeded server accumulates LMDB readers across many runs — if `/e2e/reset` starts returning
`MDB_READERS_FULL`, restart it. Real data: `cd backend && clojure -M:dev -m finance-aggregator.main` → :8080.

## Pre-ship review checklist

Roughly prioritized. `[ship-blocker?]` = decide if it blocks; `[harden]` = robustness; `[polish]`
= nice-to-have; `[decision]` = needs your call.

### A. Correctness review (the big one)
- [ ] **Systematic review of the new stack.** This is a near-total rewrite — worth a structured
  pass, not ad-hoc reading. Suggested: run `/code-review ultra` over the branch (multi-agent cloud
  review of the diff), or a Workflow that fans reviewers across `web/` + `islands/` by dimension
  (correctness / edge cases / a11y / security), then triage. Focus on the handlers
  (`pages/transactions.clj`), the command log, the pure engines (`view`, `view_state`), and the
  islands.
- [ ] **Command-log invariants** `[harden]`: undo/redo across every command type (`:set-reviewed`,
  `:set-category`, `:set-description`, `:set-splits`, `:set-match`, `:reject-match`). Confirm
  before/after capture is always correct (e.g. undoing a category change after the category was
  deleted; undoing a match whose partner changed). Mixed sequences + the redo-stack-clears-on-edit
  rule.
- [ ] **Concurrent/duplicate actions** `[harden]`: double-clicks, rapid Enter-advance down a column,
  an edit landing while a filter @get is in flight. The morph-vs-island races are where this
  session's bugs lived.

### B. Error handling + edge cases
- [x] **Server errors aren't surfaced** `[ship-blocker?]` — **DONE.** Handlers previously didn't wrap
  the command mutations, so a thrown `ex-info` (e.g. `set-splits!` validation, `confirm-match!`
  `:conflict`) propagated to a raw 500 / SSE error and the user saw **nothing** change. Now surfaced
  via a global error bar (and the split modal's inline error). Regression coverage: `e2e/v2-errors.ts`.
- [ ] **Empty / boundary states** `[polish]`: empty month, every-row-filtered-out, last page after a
  delete-that-shrinks-the-list, a single-row month, a $0 transaction (split is suppressed — verify
  match/category still sane).
- [ ] **Datastar expression robustness** `[harden]`: server data only goes into *morphed HTML*, but
  several expressions interpolate render-time ids/labels (funnel search JSON-encodes labels — audit
  that nothing else can inject a quote/`;` into an expression; recall the rollup comma-operator and
  the missing-`__self` gotchas).

### C. Production-readiness `[decision]`
- [ ] **Single-user is hardcoded.** `auth/user-id` = `"test-user"`. Real auth + per-user data
  scoping is out of scope for the rewrite but is a hard ship-blocker for a real deploy — confirm the
  intended deployment is still single-user (it is, per `project_single_user_first`) or scope the auth
  work.
- [ ] **Undo is in-memory + per-process** (`commands/log` is a `defonce` atom). Undo doesn't survive
  a server restart, and two tabs share one "test-user" log (could surprise). Acceptable for a
  single-user app — just confirm and document the expectation.
- [ ] **No CSRF/auth on the mutation endpoints.** Same-origin Datastar `@put/@post` only, single
  user — likely fine, but confirm the deploy doesn't expose them cross-origin. Server-side validation
  *is* solid (every mutation re-validates against the DB).
- [ ] `/e2e/reset` is **dev-only** (lives in `env/e2e/.../e2e_server.clj`, not the prod router) — good.
  Sanity-check no other dev-only affordance leaks into `web/routes.clj`.

### D. Performance
- [ ] **Search debounce + SSE latency on the VPS** `[decision]`: 300 ms debounce + a fragment
  round-trip per change felt fine locally; measure the real felt latency on the VPS (the original
  concern that motivated the server-authoritative design).
- [ ] **Faceting/rollup recompute cost** `[harden]`: every view change runs `facet-counts` + 3 funnel
  option passes (`patch-filter-feedback!`) + (on edits) a whole-month `category-rollup`. Trivial at a
  month's size; profile if months can get large. No DB N+1 in the hot path (everything works off one
  `list-for-month` pull) — confirm.

### E. Accessibility + cross-browser
- [~] **A11y audit** `[harden]`: WCAG AA was the design bar (memory `feedback_design_system`).
  **Done in the hardening pass:** modal focus trap + restore and `aria-modal` (focus-trap island;
  regression `e2e/v2-modal-focus.ts`), and `aria-live` announcements for SSE-morphed counts.
  **Still open:** grid roving-tabindex/roles audit, combobox (Zag handles most), and contrast in
  both light + dark (`prefers-color-scheme`).
- [ ] **Cross-browser** `[harden]`: the suite runs only Chromium. `:has()` (grid-navigation.css —
  the combobox cell-ring suppression + hover ring) gates Chromium 105+/Safari 15.4+/Firefox 121+;
  also Zag, MutationObservers, `position:fixed` floats. Spot-check Safari + Firefox.

### F. Cleanup `[polish]`
- [ ] **Deferred R5 split depth**: split *child* rows aren't keyboard-navigable or inline-editable
  (no `data-cell`); split-part reviewed checkboxes are read-only (`db.transactions/set-split-reviewed!`
  exists but isn't wired). Decide if inline split-part editing ships or stays modal-only.
- [ ] **Column widths aren't URL-persisted** (only visibility/sort/page/filters are) — auto-fit on
  load + client-side freeze, lost on reload.
- [x] **Dead code** — **DONE** in the hardening pass (unused CSS rules, defunct spike verify scripts
  that `createRequire`'d the deleted `frontend/`). The `v2-` spec prefix is now just a name.
- [ ] **Description Enter doesn't walk the column** the way the combobox advance does (it commits +
  stays, relying on the focus-restore). Minor inconsistency — decide if Enter should advance.

## Fragile areas — focus the correctness review here

These are interaction-heavy and where this session's bugs hid; review with extra care:
- **The combobox ↔ grid-cell ↔ morph dance** (`islands/combobox.ts`, `grid-nav.ts`,
  `grid-navigation.css`, `category-dropdown.css`). Bugs fixed this session: doubled border (cell-ring
  showing through), outline *jump* (flush input must wear the cell ring, not its own border),
  background mismatch (transparent input picks up the row tint), **doubled text on Enter-advance**
  (an in-flight `@put` morph re-morphs the next anchor button and strips `combobox-open` → the island
  now guards the anchor with a MutationObserver and re-applies it), and **arrow nav dying after an
  edit** (closing an editor dropped focus to `<body>` outside the scroll container → Esc dispatches
  `gridedit` cancel; the morph observer restores focus to the active cell). All have regression
  checks in `e2e/v2-grid.ts` / `v2-category.ts`, but the area is delicate.
- **The morph observer** (`grid-nav.ts`): rebuilds + repaints + restores focus on every `#tx-tbody`
  morph. Its focus-restore is guarded (`activeElement === body` or a stranded hidden input, and no
  editor open) to avoid stealing focus from other controls — verify that guard holds across every
  morph trigger (filter, sort, paginate, edit, undo, modal save).
- **The resize island** (`resize.ts`): the table-width math folds in the fixed actions column and
  pins an exact width on drag (no redistribution). It structurally assumes the actions `<col>` is the
  last one — fragile if the column set changes.
- **Combobox bundle boundary**: `split-editor.ts` reuses the grid combobox via `window.__openCombobox`
  (NOT an import) so esbuild doesn't ship Zag twice. Don't "fix" it into an import.

## Conventions to preserve (don't regress these in review)
Dumb views (logic in pure tested fns); two axes of state (survives-reload→URL × server-renders vs
client-applies); server data never in client expressions; edits = commands → undo/redo + lingering +
faceted counts; no inline styles (design tokens; dark mode via `prefers-color-scheme` only); `gitp`
for commits; never stage `doc/plans/feature-requests.md`.

## Suggested approach for the session
1. **Kick off a systematic review** (`/code-review ultra`, or a review Workflow fanned by dimension)
   over the branch while you triage findings.
2. **Walk the checklist**, converting each item to either a fix (do it) or a tracked deferral.
3. **Decide the ship line** with the user: which `[ship-blocker?]`/`[decision]` items are in scope for
   v1 vs. fast-follow. Error surfacing (B) and the prod-auth confirmation (C) are the likeliest gates.
4. Keep the suite green; add regression coverage for anything fixed.

## Open questions for the user
- ~~**Ship scope:** single-user deploy as-is, or is real auth in scope before shipping?~~
  **Resolved:** single-user deploy as-is for v1; real auth is fast-follow.
- ~~**Error UX:** invest in graceful server-error surfacing now, or accept the rare raw-500 for v1?~~
  **Resolved:** invested now — global error bar + inline modal error (`e2e/v2-errors.ts`).
- **Split editing:** inline split-part editing (keyboard nav + reviewed checkboxes) before ship, or
  modal-only is enough?
- **Cross-browser:** which browsers must v1 support (the `:has()`/modern-evergreen baseline OK)?

## Separately tracked (not part of this review)
Backend hardening — reconciliation + account sync, blocked on provider-seam debt (SimpleFin/Plaid/CSV
bypass the seam) — see memory `project_backend_hardening`. That's its own track, not the rewrite ship.
