# Resilient Sync, Balances & Reconciliation

Plan for the next large chunk of work: make account syncing "real" and resilient, then build
what falls out of it — balance tracking → reconciliation → manual fixing of bad data.

> Working on `main` directly (fast-forwarded to latest). **Do not push.** Commit in logical
> chunks with `gitp`, brief descriptive messages. Follow
> [`architecture-and-conventions.md`](../architecture-and-conventions.md) throughout
> (4-layer Data→Transformation→View→Handler, multimethods over OO, TDD: kaocha + `bb e2e` + `bb lint`).

> **This doc is the design/why (stable reference). For current state + the exact next step, read
> [`sync-reconciliation-handoff.md`](sync-reconciliation-handoff.md).**

## Status (updated 2026-07-02)

**Phases 1, 2, 4, and most of 5 are COMPLETE; Phase 3 (dedup/merge) is the one not started.** All
green, nothing pushed. See the handoffs for current state, commit logs, and file maps:
[`sync-reconciliation-handoff.md`](sync-reconciliation-handoff.md) (sync engine + setup UI) and
[`monthly-close-handoff.md`](monthly-close-handoff.md) (Phase 4, delivered as the monthly-close
workflow). **Phase 4 shipped as a per-month close ritual** (verify-it-matches-the-bank → lock it in →
roll totals up), not the generic workbench sketched below — its Phase 1 (readout) + Phase 2 (close/lock)
are done; the close is a month-level event and reconciled status is derived (no `:transaction/reconciled`
flag). This doc keeps the stable design/why; the sections below predate that reframing where noted.

- **Phase 1 done:** `:connection/*` entity + `db/connections.clj`; one canonical amount-normalization
  point (`provider.normalize`, applied via `provider.sync/persist-transactions!`); account balances +
  reported-balance snapshots (`db/snapshots`); overlay-safety guard (`provider.contract`); CSV routed
  through the shared ingest point.
- **Phase 2 done:** trigger-decoupled `resync` core, per-page cursor persistence, backoff/error
  classification, Plaid connection cutover, `bb resync` / `Sync now`.
- **Phase 5 mostly done:** `/setup` at the 4-layer standard — embedded Plaid Link island, Lunchflow
  account selection, per-connection + bulk resync, live Datastar SSE.
- **Rescope:** Phase 1d's *Plaid cursor/status cutover* onto `:connection/*` MOVED into Phase 2 — it
  is consumed by the resync engine and the historical-poll it touches is deleted there, so doing it in
  Phase 1 would rewrite the same code twice. Phase 1 delivered the rest of the seam hygiene.
- **Removed:** the per-month `/transactions/get` backfill path (`sync-month-transactions!` et al. +
  the legacy `client/fetch-transactions`). Initial link fetches as far back as the provider allows
  (Plaid `days_requested: 730`); `/transactions/sync` covers the rest.
- **Added:** the researched retry/backoff policy (`provider.retry`) — base 1m ×2, cap 15m, ≤8 retries,
  2h ceiling, equal jitter, transient/terminal classification. Wired into the engine in Phase 2.
- **New locked decisions:** trigger via a **`bb resync`** task → `clojure -M:resync` (JVM needed for
  Datalevin + Plaid SDK); the link UI uses the **embedded Plaid Link.js island** (DECIDED 2026-06-26,
  superseding the earlier Hosted Link plan — see the handoff doc decision 8) — shipped in Phase 5;
  re-auth via **Link update mode** (token from existing access-token, `products` omitted) — backend
  lands in Phase 2.

## Context

Sync today is a thin initial fetch plus a fragile in-memory historical-backfill future
(`poll-for-historical-transactions!`, 30s × 240 = 2h, lost on restart, silent if it dies). There is
**no auto-resync, no error classification or re-auth, no persisted balances, no reconciliation, and
the manual-management UI is stubbed off**.

Reality check: the codebase is **further along than it looks**. A cursor-based `/transactions/sync`
loop already handles `added`/`modified`/`removed`; there's a persisted sync-status state machine, a
cursor-after-persist invariant, and append-only overlay attributes. This is **harden + complete +
extend**, not greenfield.

## Decisions (locked)

1. **Trigger-decoupled resync.** Build the resync core fully decoupled from any trigger. Manual
   now (REPL / CLI main / UI button); cron-able later with **zero new code**. No in-process scheduler.
2. **Drop pending.** Posted-only. No `pending_transaction_id`, no pending→posted handling.
3. **Reconciliation = visible adjustment entry** (additive overlay transaction), never a silent
   balance overwrite.
4. **Full provider-seam refactor first**, as a dedicated foundation phase.
5. **Provider-agnostic linking layer.** The institution-linking abstraction must survive swapping the
   *whole* provider. Plaid is the unusual one; do not bake its assumptions (item-ids, opaque cursors,
   update-mode, `transactions_update_status`) into the generic layer. Validate the seam against
   Lunchflow, the historical SimpleFIN pattern, manual accounts, and a future Open-Banking OAuth
   provider (the target once it lands in-country).
6. **Two providers, one real-world account.** Supporting the same account from two providers should,
   to the backend, look like *importing a batch of transactions that can then be deduped*. The
   dedup-on-import work itself is deferred to Phase 3.
7. **Reconciliation UI is a workbench.** It must feel spreadsheet-seamless, like the existing
   "categorize transactions" workbench mode — integrated into the transactions workflow, not a
   bolted-on page. **Pause and design it intentionally when Phase 4 reaches the UI.**

### Verified facts that shape the plan
- `db.clj/insert!` is a plain additive `d/transact!`; upsert-by-`:transaction/external-id` asserts
  only keys present and **never retracts overlay attrs** — so `modified` re-imports are overlay-safe
  *today*, but only implicitly. Make it explicit + tested; **never** refactor `modified` into
  retract-then-insert.
- `:account/invert-amount` is **written but read nowhere** (dead on read). Canonical signs come only
  from per-provider flips (Plaid negates in `plaid/data.clj`).
- Datalevin merges schema additively at every `get-conn` → **adding attrs needs no migration**
  (data backfill for moved fields is a separate, explicit step).
- Plaid `client/fetch-accounts` already returns balances `parse-account` discards — free to persist
  (no paid Balance product).
- CSV bypasses the seam (`csv/service.clj` calls `d/transact!` directly); manual is correctly off
  the sync seam (user input, not sync).

## Provider abstraction (the seam must survive a provider swap)

How the known providers actually differ — the generic orchestrator must not assume any one shape:

| Axis | Plaid | Lunchflow | SimpleFIN (was) | Manual | Open Banking (future) |
|---|---|---|---|---|---|
| Auth | link→public→access_token + item_id | static API key in **secrets** | setup-token → access URL (creds in URL) | none | OAuth redirect + refresh token |
| Stored creds | encrypted access_token in DB | none (secrets.edn) | access URL | none | access+refresh tokens |
| Sync model | opaque **cursor** (`/transactions/sync`) | **date window** (`from`=max tx date) | **month window** | none | date range |
| Pagination | `:more? true` loop | single-pass `:more? false` | single-pass | n/a | single-pass |
| Multi-connection | per item-id | one shared fetch | per token | many accounts | per connection |
| Status machine | pending/syncing/**syncing-historical**/synced/failed | n/a | n/a | n/a | pending/syncing/synced/failed |
| Re-auth | Link **update mode** | rotate secret | re-claim | n/a | refresh-token flow |
| Removed | from sync response | retract prior window | retract prior window | user delete | retract prior window |

**Seam leaks to seal** (currently Plaid-isms in generic code):
- `:status-key` in `sync-provider!` exposes Plaid's per-item-id model → derive the status key from the
  provider/connection, don't thread item-id through generic deps.
- `:syncing-historical` keyword interpreted generically → keep Plaid's intermediate states behind the
  `:plaid` methods; generic code treats `:status` as opaque.
- Plaid-shaped `:credential/*` (item-id, sync-cursor, selected-account-ids) → move sync-state to a
  generic `:connection/*` entity with an **opaque `:connection/sync-state`** string each provider
  interprets itself (cursor for Plaid; unused for date-window providers that derive `from` from data).

**Already abstracted well, keep:** `:more?`/`:next-opts` pagination, terminal `:status`/`:status-opts`,
the `:on-complete` post-persist hook, `:removed` external-ids.

---

## Phase 1 — Provider-seam refactor (foundation)

One clean seam every provider goes through; sync-state lifted off the Plaid-shaped credential; amounts
normalized in one place; balances persisted.

- **1a. `:connection/*` entity + `db/connections.clj`** (new Data ns, mirrors `db/credentials.clj`):
  generic attrs — `id`, `user`, `provider`, `external-id`, `institution-name`, **`sync-state`** (opaque),
  `status`, `last-attempt-at`, `last-success-at`, `error-code`, `error-message`, `retry-count`,
  `next-retry-at`, `transaction-count`. Encrypted **token stays on `:credential/*`** (different lifecycle;
  Lunchflow has a connection but no credential; manual has neither). Fns: `ensure-connection!`,
  `get/list-connections`, `get-sync-state`/`set-sync-state!`, `set-status!`, `record-attempt!`/
  `record-success!`, `clear-error!`.
- **1b. One canonical amount point** (`provider/normalize.clj`, new): provider parsers do only
  native→canonical sign translation; `normalize-amounts` applies account-scoped `:account/invert-amount`
  **exactly once at import**, killing the dead-on-read state. New `db/accounts.clj/inverted-account-ids`.
- **1c. Persist balances**: add `:account/reported-balance`, `:account/available-balance`,
  `:account/balance-as-of`; `plaid/data.clj/parse-account` stops discarding `:balance`; wire unused
  `:snapshot/*` via `db/snapshots.clj/record-reported-balance!` (reported-balance history).
- **1d. Route Plaid + CSV + Lunchflow uniformly**; pin each multimethod's contract in `provider.clj`
  docstrings; move Plaid sync-state reads/writes to `:connection/*` (backfill existing rows); give
  Lunchflow a connection row for status/last-sync; CSV onto `db/insert!`; seal the seam leaks above.
- **1e. Explicit overlay safety**: `provider/contract.clj/assert-no-overlay-keys!` before `db/insert!`;
  `removed` stays the only `:db/retractEntity`; regression test for modified-not-clobbering-overlays.

**Tests:** `db/connections_test.clj`, `provider/normalize_test.clj`, balance assertions in
`plaid/data_persistence_test.clj`, overlay-clobber regression. Keep kaocha green.

## Phase 2 — Decoupled resumable resync engine

Idempotent, resumable across restarts, error-aware, trigger-agnostic.

- **`resync.clj`**: `resync-connection!` (one resumable pass; re-run resumes from persisted cursor) and
  `resync-all!` (iterates due connections, try/catch-isolated). The single core every trigger calls.
- **Per-page persistence** (`provider/sync.clj`): `db/insert!` then `set-sync-state!` after each page
  (insert-then-cursor). Crash loses ≤1 page; idempotent upsert re-pulls it. Replaces all-in-memory paging.
- **Backfill = resumable status, delete the future.** Add `:backfilling`/`:stale`/`:needs-reconnect`.
  `:initial-update-complete` → `:backfilling`; each `resync-all!` advances it until
  `:historical-update-complete` → `:synced`. Call `resync-connection!` once at link time. **Delete
  `poll-for-historical-transactions!`.**
- **Error classification**: on failure call `/item/get` for the structured Item error
  (`plaid/client.clj/fetch-item-error`). Transient (INSTITUTION_DOWN, RATE_LIMIT_EXCEEDED, API_ERROR,
  PRODUCT_NOT_READY) → `:stale` + exponential backoff+jitter via persisted `next-retry-at`/`retry-count`.
  Terminal (ITEM_LOGIN_REQUIRED, PENDING_EXPIRATION/PENDING_DISCONNECT) → `:needs-reconnect`, no retry.
- **Re-auth**: `create-update-link-token` (token from existing access-token, no `products`); handle
  `LOGIN_REPAIRED` → `clear-error!`.
- **Thin triggers, one core**: cron-friendly `resync/main.clj` (`:gen-class`, headless `ig/init`,
  `resync-all!`, `System/exit 0`) + `deps.edn :resync` alias (`clojure -M:resync`, no new code to cron);
  "Sync now" UI action (`POST /setup/resync`) calling the same `resync-all!`.

**Tests:** `resync_test.clj` (idempotent resume; backfill→`:synced`; crash-before-cursor re-pull);
error-classification units; headless `resync-all!` integration.

## Phase 3 — Dedup, merge & overlay-safe updates

- **Modified-vs-overlay drift**: keep additive upsert; when Plaid `modified` changes a source field the
  user also overrode, keep the user's value and **flag** the divergence ("bank changed this; your edit
  preserved — review?").
- **Manual ↔ synced match/bind** (double-entry-then-import; also the two-providers-one-account case):
  match on **exact amount AND |date| ≤ 10 days** (YNAB heuristic) → present candidates for **explicit
  confirm** (never auto-merge fuzzy). On confirm keep the synced row as source-of-truth, re-parent the
  manual edits as an overlay, mark the manual entry superseded.
- **Account dedup/merge**: detect a relinked / cross-provider duplicate account; confirm-gated merge.
- Reuse `data/cleaning.clj/find-likely-dupes` behind a review-and-confirm surface.

## Phase 4 — Balances & reconciliation

> **DELIVERED (2026-07-02) as the MONTHLY-CLOSE workflow — see [`monthly-close-handoff.md`](monthly-close-handoff.md).**
> The confidence check is the **period-delta** (bank Δ over the month vs Σ tracked txns) — no
> opening-balance anchor needed. "Closed" is a **month-level** `:reconciliation/*` event; a
> transaction's reconciled status is DERIVED (no `:transaction/cleared`/`:transaction/reconciled`
> overlay). Phase 1 (readout) + Phase 2 (close/lock) done; a visible adjustment-on-drift entry and a
> cross-month tracking view are the remaining pieces. The sketch below is the original design; the
> handoff is authoritative for what shipped.

Depends on Phase 1 balances/snapshots. **Pause for intentional workbench-UI design before building the
reconciliation surface** (Decision 7).

- **Opening-balance entry**: immutable transaction at each account's earliest date (ledger anchor).
- **Computed vs reported**: ledger balance (opening + Σ signed) vs reported (Phase 1) vs user-entered
  statement balance; surface the exact difference per account. Auto-detect, never auto-correct.
- **Review/reconcile axis**: overlay attrs `:transaction/cleared`, `:transaction/reconciled` +
  `:reconciliation/*` event entity (account, statement-date, statement-balance, computed-cleared,
  difference, adjustment-entry ref).
- **Flow**: enter statement balance + date → show difference → confirm → **emit a visible adjustment
  transaction** + record the event + mark rows reconciled (soft lock: editable but flags drift).

## Phase 5 — Manual transaction & account management UI

Depends on Phase 4 (balances). Bring `setup.clj` to the 4-layer standard (it's the doc's named next
target).

- Wire stubbed Setup write buttons (Add Manual Account, Link Bank, CSV import) + **connection management**
  (per-connection status / last-success / Sync now / Reconnect via update mode / Remove).
- Dense-table manual add/edit/delete (Lunch Money idioms: click-to-edit cells, in-table add row, bulk
  edit with field-toggle confirm). Reuse `web/commands.clj` overlay/undo.
- Manual-account balance updates over time as adjustment entries (consistent with Phase 4).
- Overlay differentiators (cheap here): "edited/overridden" indicator with original-on-hover; per-field
  "revert to bank value" (delete the overlay).

---

## Cross-cutting risks
- **Overlay clobber** — additive upsert + `assert-no-overlay-keys!` + regression test; never
  retract+insert on `modified`.
- **Cursor atomicity** — insert-then-cursor is idempotent on crash; investigate folding the cursor into
  the same `db/insert!` transaction (verify in REPL).
- **Lunchflow `from = max-date`** boundary miss + cron/UI race — overlap window (`last-success − 1d`) +
  in-flight guard.
- **Removing the future safely** — backfill only finishes when resync re-runs; call `resync-connection!`
  at link time; surface progress before deleting.
- **invert-amount becomes import-time-only** — existing rows aren't retroactively flipped; decide if a
  one-off renormalize is needed.
- **Credit-balance sign** — store raw; defer net-worth sign to the View layer.

## Verification
- `cd backend && clojure -M:test -m kaocha.runner` green after each phase (baseline ~326); `bb e2e`, `bb lint`.
- Resync: drive `resync-all!` from the REPL against a sandbox item — idempotent re-run, backfill
  `:backfilling`→`:synced`, `:needs-reconnect` on a forced ITEM_LOGIN_REQUIRED.
- Cron-readiness: `clojure -M:resync` runs one pass, exits 0, no extra wiring.
- UI: Sync now / Reconnect / reconciliation / manual add-edit via the running app; reconcile emits an
  adjustment entry equal to the difference.

Sequencing: 1 → 2 → 3 → 4 → 5 (4 and 5 interleave around balances). Each phase ships green and is
committed in logical chunks.
