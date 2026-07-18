# Allium spec workstream — resume-here handoff

**Read this first** to resume the Allium behavioural-spec work: what's set up, the one spec that
exists and its state, the decisions locked on 2026-07-17, the flagged-but-unfixed small items, and
the candidate next phases. Cross-session memory key: `project_allium_setup`. The monthly-close
feature's own handoff is [`monthly-close-handoff.md`](monthly-close-handoff.md) — this doc
supersedes its gate description where they disagree (the 2026-07-17 weed changed gate semantics).

**Branch:** `main` (working directly on it). **Do not push.** Commit in logical chunks with `gitp`
+ brief messages; test-drive before each commit (`cd backend && clojure -M:test -m kaocha.runner`,
`bb lint`, `bb e2e`). Java per shell: `jabba use zulu@25.0.3` — in non-interactive shells use
`export JAVA_HOME=/opt/homebrew/Cellar/jabba/0.15.0/jdk/zulu@25.0.3/Contents/Home` and prefix
`$JAVA_HOME/bin` onto PATH (bb e2e needs it too).

## What Allium is here

Behavioural specs (`.allium`) live in `spec/` at the repo root, language version 3 (`-- allium: 3`
first line). The spec is the primary artefact; code is aligned to it. Skills: `/elicit` (spec from
intent, conversational), `/distill` (spec from code), `/tend` (targeted spec edits), `/weed`
(spec⇄code divergence + resolution), `/propagate` (tests from spec). The `allium` CLI (3.5.0,
installed via `brew trust juxt/allium && brew install allium`) validates on every `.allium` write
through the plugin hook; `allium check` / `allium analyse` run it manually. `allium-lsp` is NOT
installed — the `/plugin` Errors tab shows it missing; harmless, CLI checking covers it.

## Current state (2026-07-17)

**One spec exists: `spec/monthly-close.allium`** — reconciliation & monthly close (statements,
coverage-strict account status, close gate, frozen totals, drift). Built by distill → elicit →
weed in one day; **fully aligned with the code**: `allium check` 0 errors, `allium analyse` 0
findings, no DIVERGENCE notes left. Remaining warnings are expected — external entities
(User/Account/Category/Transaction) have no governing specs yet; they'll clear as sibling specs
appear.

### Decisions locked 2026-07-17 (elicitation with Kira, all implemented)

1. **"Reconciled" is ONE concept** at row / period / account granularity — the per-transaction
   tick and balance reconciliation share the word deliberately. No rename anywhere.
2. **Quiet accounts gate the close.** An account with NO month transactions but an entered period
   overlapping the month (complete boundary pair and/or statement) appears in the readout and
   gates. Stricter than txn-coverage: EVERY entered period must itself tie out; a quiet boundary
   pair must report ~zero movement (drift there = missing transactions).
3. **Re-close silently re-freezes** — no explicit reopen required first (drift is shown
   side-by-side beforehand, so the overwrite is informed).
4. **Statement polarity is explicit, not guessed.** Per-account `:account/statement-polarity`
   (`:as-signed` | `:inverted`), strict comparison; declared on the /setup account table
   ("Statements" column, `PUT /setup/account/:external-id/statement-polarity`); when never
   declared, defaults by account type at read time (`:credit` → `:inverted`), no migration.
   The old closest-delta heuristic could mask real drift and is deleted.

### Commit trail (2026-07-17, on `main`, unpushed)

```
ace9e04  spec: distilled monthly-close draft
a57c0bd  spec: elicitation decisions folded in
e8de78e  spec: polarity declared on setup card, type-based default
77e20db  weed: reject backwards statement spans + reopen of an unclosed month
512c794  weed: explicit per-account statement polarity (heuristic deleted)
2e54a31  weed: quiet accounts join the close readout + gate; :name-fallback wired
0fe56f2  spec: divergences marked resolved; focus-headline edge noted
```

Suite after all of it: **kaocha 555 / 2416 assertions green, `bb lint` clean, e2e 23/23 specs
green** (v2-reconcile 45/45).

### Code map of the weed changes

```
backend/src/finance_aggregator/
  data/ledger.clj          + effective-statement-polarity (explicit wins; :credit→:inverted),
                             reconcile-statement-period now takes :polarity (strict; heuristic gone),
                             quiet-account-status (every entered period must tie out; pair ≈ 0)
  data/schema.clj          + :account/statement-polarity (absent = type default, read-time)
  db/statements.clj        + account join pulls type/polarity; account-eids-overlapping (reverse
                             of list-overlapping: which accounts have a statement in a span)
  db/accounts.clj          + set-statement-polarity! (whitelisted to #{:as-signed :inverted})
  web/view.clj             ~ reconcile-statement takes polarity; reconcile-month takes optional
                             :quiet-accounts (default [] — old callers byte-identical)
  web/pages/transactions.clj ~ save-statement rejects start>end; reopen-month errors when not
                             closed; close-model-for computes the quiet set (reported-deltas over
                             ALL accounts + account-eids-overlapping, minus active) and finally
                             wires :name-fallback into focus-close (latent bug — was never passed)
  web/pages/setup{,_view}.clj + Statements polarity column/handler (mirrors set-account-name,
                             incl. the fake-SSE test seam in setup_test.clj)
  web/routes.clj           + PUT /setup/account/:external-id/statement-polarity
  css/components/account-table.css + .account-polarity-select
backend/env/e2e/.../seed.clj  Visa pins :account/statement-polarity :as-signed — ONLY so
                             v2-reconcile/v2-statement-step's hardcoded as-signed-shaped balances
                             keep passing; the realistic alternative is :inverted + updating those
                             two .ts specs' balance values
backend/test/.../web/pages/transactions_test.clj  NEW — the `page` handler is the ONE handler in
                             that ns callable without SSE machinery (plain ring response); used as
                             the DB-integration seam for close-model-for. The edit handlers have NO
                             lightweight seam (http-kit as-channel no-ops under ring-mock) — don't
                             build heavy mocks; setup.clj handlers DO have the datastar fake-SSE seam.
```

## Open questions still in the spec (2)

1. **Drifting-but-covered periods.** On an ACTIVE account, a drifting entered period doesn't
   block when all txns are covered by other reconciled periods (coverage wins over the
   contradiction). Same tension in the focused card: its headline (month-coverage) reads
   `reconciled` for a QUIET account with one reconciled + one drifting statement, while the
   overview row (quiet-account-status) correctly reads `partial`. Decide: coverage-wins
   everywhere, or any-drifting-period-blocks everywhere — then align `month-coverage`/
   `focus-close` or `quiet-account-status` accordingly.
2. **Adjustment-on-drift escape hatch** — close with accepted drift emitting a visible
   adjustment transaction; designed, unbuilt (see monthly-close-handoff "Deferred").

## Flagged small items, unfixed (all pre-existing)

- `patch-panel+close-modal!` never clears `#error-bar` on success — a prior rejection's banner
  lingers through a later successful statement save.
- Drilling a quiet account leaves the active-filter chip label blank ("Account —"):
  `web.view/account-options` is built from the view's transactions, and a quiet account has none.
- The focus-headline leniency above (folded into open question 1).

## Next phase — candidates, in recommended order

1. **Distill the transactions-overlay model** → `spec/transactions.allium`. Highest value: the
   monthly-close spec's external `Transaction` entity takes on faith exactly the guarantees this
   spec would own (imports immutable, edits are additive overlays, split parts sum to parent and
   parents excluded from lists, transfer links, effective-posted-date chain). Sources:
   `doc/plans/splits-as-transactions.md`, `manual-posted-dates.md`, memories
   `project_splits_as_transactions` / `feedback_append_only_overlays` / `project_transfer_model`,
   `db/transactions.clj`, `splits.clj`, `transfers.clj`, `data/ledger.clj` date fns.
2. **`/propagate` on monthly-close** — generate tests from the spec's obligations; check what the
   555 existing tests already cover, keep only tests that fail first or pin an uncovered
   obligation.
3. **Sync engine spec** (`doc/plans/sync-reconciliation*.md` + `resync.clj`, `provider/`) — would
   also give `BalanceReported` a real emitter and clear the external-entity warnings.
4. **Cross-month tracking view — ELICIT SPEC-FIRST before building** (it reads MonthlyClose's
   frozen totals; the deferred "Phase 3" of monthly-close).

## Gotchas for the next session

- All `reconcile-statement-period` callers must pass `:polarity` explicitly or accept the
  `:as-signed` default — grep before adding call sites.
- Quiet-set derivation runs `reported-deltas` over ALL accounts each panel rebuild — fine
  single-user; revisit if account count grows.
- e2e discipline unchanged: eid-agnostic assertions, `v2-reconcile.ts` sorts after the specs that
  must precede the first `/e2e/reset`, port 8099 shared with concurrent sessions.
- The elicitation decisions above are LOCKED — don't re-litigate them in the next session; the
  two open questions are the live ones.

## Run & verify

```bash
jabba use zulu@25.0.3                                    # once per shell (or JAVA_HOME fallback above)
cd backend && clojure -M:test -m kaocha.runner           # 555 tests green
bb lint                                                  # clj-kondo + tsc, from repo root
bb e2e                                                   # 23 specs; bb e2e v2-reconcile for just the close flow
allium check spec/monthly-close.allium                   # 0 errors
allium analyse spec/monthly-close.allium                 # 0 findings
```

## Resume prompt (copy-paste for the clean context)

> Read doc/plans/allium-handoff.md, then use the allium distill skill to extract the
> transactions-overlay model into spec/transactions.allium (imports immutable, additive overlays,
> splits as first-class rows, transfer links, effective-posted-date), cross-referencing
> spec/monthly-close.allium so shared entities keep one name each.
