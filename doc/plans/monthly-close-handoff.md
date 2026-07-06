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

## Where we are (2026-07-03)

**Phase 1 (read-only delta readout) and Phase 2 (the close/lock) are COMPLETE, plus a manual-entry
pass (2026-07-03) that tightened the panel for the real close workflow.** The `/` transactions
workspace has a working monthly-close panel beside the category rollup: per-account "matches / off by
$X / no statement", a completeness gate, and Close / Reopen.

**Manual-entry pass (2026-07-03):**
- **Statement balances on an explicit date.** `record-manual-balance!` now takes a `java.util.Date`
  (keyed per account-day), so a statement that closes mid-month lands on its real closing date. Entry
  moved from the inline per-row input to a **modal** ("Set balance" per unreconciled row, or a
  panel-level "+ Add statement balance") that lists **all** accounts — so an account with no activity
  this month still reconciles — and the panel now **lists every recorded balance with its applied
  date** (+ a × to remove). `list-manual-balances` / `delete-manual-balance!` back it.
- **Manual transactions.** An "Add transaction" toolbar button opens a modal (account picker + a
  money-out/-in toggle that derives the canonical sign + amount/date/payee/optional category). The
  created row is a first-class `:transaction/provider :manual` row (`db.transactions/create-manual!`),
  inserted directly (bypassing the provider invert-amount flip — the user supplies the sign) with an
  optional category applied as a post-create overlay. Deletable via the row-actions menu → confirm
  dialog (`delete-manual!`, guarded to `:manual`). Both create + delete re-patch `#reconciliation`
  (a new/removed row moves the computed deltas + gate).

**Reconciliation redesign (2026-07-05) — per-account DRILL + month-boundary AND arbitrary-span
statements. Step 1 of 2 DONE.** The panel is no longer a flat all-accounts list; it's a drill:

- **Overview ⟷ focus, driven by the account funnel.** Overview = every active account (a drill
  BUTTON showing matches / off by $X / needs balances) + the completeness gate + Close/Reopen. Drilling
  (click a row, or filter the table to one account) opens the FOCUSED single-account view; Back returns.
  Drill/back flow through `/transactions/rows`, so the panel and the table stay in sync.
- **Month-boundary card — the app owns the dates.** In the focused view you enter the account's opening
  (end of the prior month's last day) + closing (end of this month's last day); the app supplies those
  dates (end-of-day semantics), you type two figures. Backed by `:manual` snapshots; `boundary-balances`
  prefills; `set-reconcile-balances` writes both at `opening-date`/`month-end-date`. The old free-date
  "Set balance" modal + cross-account balance list are GONE.
- **Statements (NEW) — arbitrary spans.** A first-class `:statement/*` entity (account, start/end date +
  balance) for accounts whose balance can't be read on a chosen day (credit cards). The focused view
  lists the account's statements overlapping the month, each with its own period-delta verdict
  (`ledger/reconcile-period` over `db.transactions/list-for-account-range`); add/edit/delete via a modal
  (no account picker — lands on the drilled-into account). Clicking a statement NARROWS the table to its
  span (may cross a month boundary; a 2nd click un-narrows). `$reconFrom`/`$reconTo` drive the narrow.
- **The narrow is a LENS, not a filter** (`transactions/table-and-facets`): the faceted funnels / counts
  / chips / rollup stay month-wide; only the table :result is the span slice. (Computing facets over the
  slice collapsed the account funnel and cleared `$filter.account`, which drives the focus — do not
  reintroduce that.)

Suite: **all green** — kaocha 355, `bb lint` clean, `bb e2e` all specs pass (`v2-reconcile` 37/37 covers
the whole statement flow). Nothing pushed.

**NEXT — Step 2: coverage-strict closing.** Today the OVERVIEW status + the close gate still use only the
month-boundary period, so a credit card reconciled by statements reads "needs balances" and can't close.
Make the per-account status + the gate **coverage-based**: a month is reconciled for an account when
every one of its month transactions falls inside a **reconciled** period (month-boundary OR statement).
A straddling statement's tail that isn't yet covered blocks the close with a clear reason; the escape is
a readable month-end balance point. Coverage is a pure fn (gather the account's reconciled spans, check
every month txn is inside one). Design agreed with the user 2026-07-05 (coverage-strict; no-mode; a list
of statements). **The cross-month tracking view (the old Phase 3) is deferred behind Step 2.**

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
5eda884  manual   statement balances on an explicit date + list/delete (data layer)
34b3940  manual   create-manual! / delete-manual! transaction data layer
8b282c2  manual   statement-balance modal + dated balances list (replaces inline entry)
827d4ad  manual   the Add-transaction modal
a6c43ba  manual   delete a manual transaction via the row-actions menu
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
                                snapshot at an EXPLICIT Date, keyed per account-day, own id namespace so
                                it coexists with :reported). list-manual-balances (newest-first, with
                                account name) + delete-manual-balance! (guarded to :manual).
                                month-end-date helper (the modal's default date seed).
  db/transactions.clj      +    create-manual! (a first-class :manual row inserted DIRECTLY — no invert
                                flip; optional category applied as a post-create overlay; local
                                ensure-user! since a :transaction/user ref value must resolve) and
                                delete-manual! (guarded to :manual; unlinks any transfer pair first).
  web/view.clj             +    reconcile-month (per-account rows + all-reconciled?), month-close
                                (rows + completeness gate {unreviewed, uncategorized, all-reviewed?,
                                all-categorized?, balanced?, ready?} + closed?/closed-at + drift +
                                :manual-balances passthrough). present takes :reported/:close/
                                :manual-balances and adds :close to the model.
  web/view_state.clj       +    stmt{Account,Date,Balance,Del} couriers (statement modal) and
                                tx{Account,Dir,Amount,Date,Payee,Desc,Category} couriers (add-txn modal)
                                + ephemeral :_rowMenuManual (drives the row-menu Delete item).
  web/pages/transactions.clj +  statement-editor (GET modal, seeds signals), set-statement-balance
                                (POST /statement → record-manual-balance! → patch panel + close modal),
                                delete-statement-balance (POST /statement/delete). add-transaction-editor
                                (GET modal, default-txn-date), create-manual (POST /manual),
                                delete-transaction-editor (GET confirm) + delete-manual (POST
                                /:id/manual/delete). close-month (GATE-GUARDED), reopen-month.
                                close-model-for (now includes :manual-balances) + patch-close-panel!.
  web/pages/transactions_view.clj + close-panel (per-account rows w/ "Set balance", the recorded-balance
                                list w/ dates + "Add statement balance", gate, Close/Reopen); statement-
                                modal, add-transaction-modal (money-out/-in toggle), delete-transaction-
                                modal; account-select helper (shared). row-actions-menu gained a manual-
                                only Delete item ($_rowMenuManual). Panel is its OWN #reconciliation
                                element in .rollup-column (outside #category-rollup).
  web/routes.clj           +    manual/new (GET modal), manual (POST create), :id/manual/delete
                                (GET confirm + POST delete); statement-modal (GET), statement (POST),
                                statement/delete (POST) — replaced /reconcile/:account/statement.
                                /transactions/close, /transactions/reopen.
  resources/public/css/components/reconciliation.css  NEW  panel + Set balance + statement list + gate +
                                closed/drift.
  resources/public/css/components/form-modal.css        NEW  shared form-modal frame (statement + add-txn).
  resources/public/css/components/add-transaction-modal.css NEW  money-out/-in segmented toggle.
  resources/public/css/components/category-rollup.css ~   .rollup-column wrapper owns the sticky scroll.

backend/env/e2e/src/finance_aggregator/dev/seed.clj + 2025-01 boundary snapshots so three accounts
                                reconcile; Mortgage left Dec-only ("no statement") as the entry target.
Tests: data/ledger_test, db/reconciliations_test, db/snapshots_test (reported-delta + manual dated +
       list/delete), db/transactions_test (create-manual/delete-manual), web/view_test (month-close
       gate/drift), web/pages/transactions_view_test (panel + statement-list render branches).
e2e:   v2-reconcile.ts (statement-modal morph, live), v2-add-transaction.ts (add + sign + delete).
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
5. **The panel is edit-invariant for the ordinary edits → renders on full-page load only** (imported
   amounts are immutable; splits/category/reviewed never move an account total). It lives OUTSIDE
   `#category-rollup` so the rollup's edit re-patches never clobber it. It IS re-patched by its own
   statement/close/reopen actions **and by manual create/delete** — those add or remove a row, which
   moves the computed deltas + gate, so `create-manual` / `delete-manual` re-patch `#reconciliation`
   via `edit-response`'s `:after-patch` alongside the table. (An ordinary field edit still does not.)

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
- **Statement + add-txn modals seed their signals on open, then read them on save.** The GET modal
  handler `patch-signals!`s the initial values (account/date defaults, blank amount) AND the fragment
  carries matching HTML defaults, so the fields are consistent regardless of data-bind's init direction
  and a reopened modal never shows stale values. Save is gated client-side via a `data-attr` disabled
  expression AND validated server-side (missing field → error bar). (This replaced the old per-click
  `$stmt` inline courier when statement entry moved to a modal.)
- **A manual transaction stores its sign as given — do NOT route create-manual! through the provider
  seam.** The invert-amount flip (`provider.normalize`) is import-only; the modal already resolves the
  sign from the money-out/-in toggle, so a direct insert is correct and the seam would double-flip an
  inverted account. Category can't ride the insert (overlay contract) → it's a separate post-create
  `update-category!`.
- **The close is gate-guarded on the server** (`close-month` refuses unless `:ready?`), not just via the
  disabled button — a stray POST can't close an unready month. Keep that guard.
- **Reported-delta needs BOTH boundaries.** A month with only one snapshot (or a start snapshot older
  than the immediately prior month) returns nil → "no statement", not a spurious delta.

## Run & verify

```bash
jabba use zulu@25.0.3                                    # once per shell
cd backend && clojure -M:test -m kaocha.runner           # kaocha (347, all green)
bb lint                                                  # clj-kondo + tsc (from repo root)
bb e2e                                                   # Playwright (18 specs; v2-reconcile + v2-add-transaction)
bb e2e v2-reconcile v2-add-transaction                   # just the monthly-close + manual-entry specs
```
- Manual drive: `bb dev` → open `/?month=2025-01`. Three accounts show "matches"; Mortgage shows "no
  statement" → click **Set balance**, enter `-98000`, Save → it reconciles and the gate flips to
  "Balances match" (the recorded balance now lists with its date, removable via ×). **Add transaction**
  in the toolbar opens the add modal; a manual row is deletable from its row-actions caret → Delete.
  (Closing still needs everything reviewed + categorized.)
```
