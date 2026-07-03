# Monthly close — resume-here handoff

**Read this first** to resume the monthly-close workflow: current state, the file map, the locked
decisions, the exact next step (Phase 3 — the cross-month tracking view), gotchas, and run/verify.
This is the concrete shape **Phase 4 of the sync-reconciliation plan** took — a per-month *close
ritual*, not generic statement reconciliation. The sync engine's own handoff is
[`sync-reconciliation-handoff.md`](sync-reconciliation-handoff.md); the design/why for balances is in
[`sync-reconciliation.md`](sync-reconciliation.md). Cross-session memory key: `project_monthly_close_goal`.

**Branch:** `main` (working directly on it). **Do not push.** Commit in logical chunks with `gitp`
(the `git commit` wrapper) + brief messages; test-drive before each commit
(`cd backend && clojure -M:test -m kaocha.runner`, `bb lint` from the repo root; `bb e2e` for UI).
Java is jabba-managed — each shell: `jabba use zulu@25.0.3`.

## The goal (why this exists)

For a given **month**: double-check that imported + categorized transactions match the banks (the
source of truth), gain confidence nothing's missing, **lock it in**, and roll the month's totals up
into higher-level ongoing tracking. The confidence check is the **period-delta**:

```
reported-delta = bank-reported balance(month-end) − balance(month-start)     [from :snapshot/* history]
computed-delta = Σ signed :transaction/amount over the month                 [tracked activity]
reconciled?    = |reported-delta − computed-delta| ≤ tolerance
```

When they agree, the month's transactions fully explain the bank's balance change — nothing missing,
nothing extra. **No opening-balance anchor is needed** (only the change over the period is compared).

## Where we are (2026-07-02)

**Phase 1 (read-only delta readout) and Phase 2 (the close/lock) are COMPLETE.** The `/` transactions
workspace now has a working monthly-close panel beside the category rollup: per-account "matches /
off by $X / no statement", inline statement-balance entry, a completeness gate, and Close / Reopen.

Suite: **all green** — `clojure -M:test -m kaocha.runner` (339 tests), `bb lint` clean, `bb e2e` all
17 specs green (incl. `v2-reconcile.ts`). Nothing pushed.

**NEXT — Phase 3: the cross-month tracking view** (the tracking payoff). Read `db.reconciliations/
list-closes` (already built, newest-first) into a month-over-month view: net + income/expense per
month, category trends. It reads the **frozen** close totals, so it's immutable and cheap — never
recomputed from raw rows. Locate it as a new workspace surface (a `/tracking` page, or a section) —
**pause to design it** the way the reconciliation panel was designed (integrate, don't bolt on).

### Commit trail (monthly-close, on `main`, unpushed)
```
9815f9f  Phase 1  pure period-delta ledger math (data/ledger.clj)
0a9f9ca  Phase 1  read reported balance deltas from snapshot history (db/snapshots.clj)
0da4a07  Phase 1  per-account reconciliation readout on the transactions page
5f2a418           docs: reframe Phase 4 as the monthly-close workflow
2adb0ba  (hardening) bound reported-delta start to the prior month
77e4505  (hardening) accurate docstring; drop abs-bd (use core abs)
ddbcf15  (hardening) fix summary-column scroll + card padding regression
9d4f270  Phase 2a close-event data layer + manual statement balances
96b21e2           e2e: make the funnel/grid specs eid-agnostic (see gotcha below)
23e73c5  Phase 2b the close panel — statement entry, gate, close/reopen
```

## What's built — file map

```
backend/src/finance_aggregator/
  data/ledger.clj          NEW  Pure period-delta math. account-computed-deltas (Σ signed amount per
                                account; splits ignored — they re-attribute, don't move the total),
                                reconcile-row / reconcile (join computed vs reported → :reconciled /
                                :drift / :no-snapshot rows), all-reconciled?. default-tolerance = 0.005.
  data/schema.clj          +    :reconciliation/* (id="<user-id>:<yyyy-MM>", user, month, closed-at,
                                income, expenses, transfers, net) — the month close event.
  db/reconciliations.clj   NEW  Close events (data layer, single-user). close-month! (freeze totals,
                                idempotent per month), get-close, closed?, reopen-month! (retract),
                                list-closes (newest month first — the Phase 3 source).
  db/snapshots.clj         +    reported-delta / reported-deltas (end−start boundary; only :reported/
                                :manual sources anchor; :manual wins the tie-break; nil when a boundary
                                is missing or the start snapshot predates the prior month).
                                record-manual-balance! (user-entered statement balance → :manual
                                snapshot at the month's last instant, own id namespace so it coexists
                                with :reported). month-end-instant helper.
  web/view.clj             +    reconcile-month (per-account rows + all-reconciled?), month-close
                                (rows + completeness gate {unreviewed, uncategorized, all-reviewed?,
                                all-categorized?, balanced?, ready?} + closed?/closed-at + drift).
                                present now takes :reported + :close and adds :close to the model.
  web/view_state.clj       +    :stmt "" courier signal (statement-entry input).
  web/pages/transactions.clj +  set-statement-balance (POST .../statement → record-manual-balance! →
                                patch #reconciliation), close-month (POST /close, GATE-GUARDED — refuses
                                unless :ready?), reopen-month (POST /reopen). close-model-for +
                                patch-close-panel! helpers.
  web/pages/transactions_view.clj + close-panel (per-account rows, inline statement-entry on an open
                                month, gate checklist, Close/Reopen). Rendered in the .rollup-column
                                wrapper as its OWN #reconciliation element (outside #category-rollup).
  web/routes.clj           +    POST /transactions/reconcile/:account/statement, /transactions/close,
                                /transactions/reopen.
  resources/public/css/components/reconciliation.css  NEW  panel + statement entry + gate + closed/drift.
  resources/public/css/components/category-rollup.css ~   .rollup-column wrapper owns the sticky scroll.

backend/env/e2e/src/finance_aggregator/dev/seed.clj + 2025-01 boundary snapshots so three accounts
                                reconcile; Mortgage left Dec-only ("no statement") as the entry target.
Tests: data/ledger_test, db/reconciliations_test, db/snapshots_test (reported-delta + manual),
       web/view_test (month-close gate/drift), web/pages/transactions_view_test (panel render branches).
e2e:   v2-reconcile.ts (statement-entry morph, live).
```

## Locked decisions

1. **Period-delta confidence model** (no opening-balance anchor). Bank Δ vs Σ month txns. Manual
   statement-balance entry fills the reported side when the sync has no month-boundary snapshot.
2. **Per-account → month granularity.** Each account reconciles independently; the month is closeable
   only when **all** accounts pass. The gate also requires 0 unreviewed + 0 uncategorized for the month.
3. **"Closed" is a MONTH-LEVEL fact, not a per-transaction flag.** A `:reconciliation/*` event per
   user per month. Whether a transaction is reconciled is DERIVED from its month having a close event —
   so a row later imported/edited into a closed month surfaces as **drift** (its current net ≠ the
   frozen net), and reopening is just retracting the one event. **Do not add `:transaction/reconciled`.**
4. **Totals are frozen at close** (income/expenses/transfers/net) so the tracking view reads immutable
   figures even if a closed month is later touched.
5. **The panel is edit-invariant → renders on full-page load only** (imported amounts are immutable;
   splits/category/reviewed never move an account total). It lives OUTSIDE `#category-rollup` so the
   rollup's edit re-patches never clobber it; it's re-patched ONLY by its own statement/close/reopen
   actions (all Datastar `@post` → morph `#reconciliation`).

## Deferred / open items

- **Adjustment-on-drift (the close escape hatch).** The gate currently HARD-requires every account
  balanced, so an account that can't reconcile blocks the close entirely. The planned escape hatch —
  close-with-accepted-drift → emit a **visible adjustment transaction** for the difference (per the
  never-silently-set-a-balance decision) — is NOT built. Add it when a real unreconcilable account
  shows up (or before, if closing proves too strict in practice).
- **Absolute balances / net worth.** Only per-month deltas today. An opening-balance anchor + chaining
  from prior closes would give absolute figures. Optional, later.
- **Renormalize on invert-amount toggle** (unrelated carryover): `:account/invert-amount` is
  import-time-only; existing rows aren't retroactively flipped.

## Gotchas

- **e2e specs that hardcode seed eids are fragile.** `v2-funnels`/`v2-grid` used to assert absolute
  Datalevin entity ids (account eid 2/4, tx eid 10/13). Adding the 7 seed snapshots shifted the
  post-reset eid arithmetic and broke them (and those ids aren't even stable across environments).
  They're now **eid-agnostic** (select by account name / capture the focused row's eid at runtime) —
  keep any new assertions eid-agnostic. `v2-reconcile.ts` is named to sort AFTER them because it
  `/e2e/reset`s at its start (which bumps the eid counter), and those two must run before the first reset.
- **`/e2e/reset` bumps eids** (clear! + reseed; Datalevin doesn't reuse retracted eids): seed#1 first
  tx = eid 10, post-reset stabilizes higher. Never hardcode absolute seed eids in a spec.
- **Statement-entry courier is per-click, not data-bound.** Multiple no-statement rows would share one
  `$stmt` if data-bound; instead the Save button copies THIS row's input into `$stmt` at click time
  (`$stmt = el.previousElementSibling.value`). Keep that pattern if you add more per-row inputs.
- **The close is gate-guarded on the server** (`close-month` refuses unless `:ready?`), not just via the
  disabled button — a stray POST can't close an unready month. Keep that guard.
- **Reported-delta needs BOTH boundaries.** A month with only one snapshot (or a start snapshot older
  than the immediately prior month) returns nil → "no statement", not a spurious delta.

## Run & verify

```bash
jabba use zulu@25.0.3                                    # once per shell
cd backend && clojure -M:test -m kaocha.runner           # kaocha (339, all green)
bb lint                                                  # clj-kondo + tsc (from repo root)
bb e2e                                                   # Playwright (17 specs; v2-reconcile drives the close panel)
bb e2e v2-reconcile                                      # just the close-panel spec
```
- Manual drive: `bb dev` → open `/?month=2025-01`. Three accounts show "matches", Mortgage shows a
  statement input; enter `-98000` → it reconciles and the gate flips to "Balances match". (Closing
  still needs everything reviewed + categorized.)
```
