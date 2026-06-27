# Resilient sync, balances & reconciliation — resume-here handoff

**Read this first.** Single entry point to resume the resilient-sync / reconciliation work: current
state, the new-namespace map, the locked decisions + research findings, the exact next step (Phase 2
core), gotchas, and run/verify. The design/why is in
[`sync-reconciliation.md`](sync-reconciliation.md); cross-session memory keys
`project_backend_hardening` and `project_sync_reconciliation`.

**Branch:** `main` (working directly on it, fast-forwarded to latest). **Do not push.** Commit in
logical chunks with `gitp` (the `git commit` wrapper) + brief messages; test-drive before each commit
(`clojure -M:test -m kaocha.runner`, `bb lint`; `bb e2e` for UI). Java is jabba-managed — each shell:
`jabba use zulu@25.0.3` (or export `JAVA_HOME`).

## Where we are (2026-06-24)

**Phase 1 (provider-seam foundation) and the Phase 2 core (cutover + resync engine) are COMPLETE,
plus a pre-review hardening pass.** Connections are now the source of sync truth: a trigger-decoupled
`resync` core drives every connection, the 2-hour background poll is gone (backfill is the resumable
`:backfilling` status), and errors classify into capped-exponential backoff / re-auth / fail. The dead
per-item `plaid.service` and the creds sync-cursor/status fns are deleted. **The next chunk is Phase 3**
(dedup/merge + drift) — see the plan doc; Phase 4 (reconciliation workbench) and Phase 5 (setup UI /
Hosted Link) follow.

Suite: **all green** (`clojure -M:test -m kaocha.runner`, 294 tests), `bb lint` clean. Nothing pushed.

### Pre-review hardening (applied to the commits below, not bolted on)
- **Classify on the failing call's real `error_code`.** `plaid.client/execute!` now inspects the
  Retrofit response and, on a non-2xx, parses Plaid's error body so the thrown ex-info carries
  `error_code` (this also fixes a latent NPE-on-HTTP-error). `/item/get` is only a *supplement* when the
  call carried no code. Previously classification read `/item/get` alone, which never surfaces the
  transient codes (rate-limit / 5xx) the backoff exists for.
- **Provider-agnostic error handling.** The Plaid error vocabulary + `classify` moved to
  `plaid.errors`; the core dispatches `provider/classify-sync-error`. `resync` no longer names a Plaid
  code or calls a Plaid client fn.
- **Elapsed-time ceiling now wired.** `:connection/first-failure-at` is stamped on the first transient
  failure and cleared on success, so the 2h `:max-elapsed-ms` bound actually engages (was dead config).
- **Slow-retry after the budget is spent.** Exhausted backoff parks `:stale` with a slow `stale-retry-ms`
  cadence instead of nil (which made it due every pass).
- **Concurrency guard.** `due?` skips a connection already `:syncing` with a recent attempt (overlapping
  cron / Sync-now), while a long-stuck `:syncing` still recovers.
- Hoisted the invert-amount query out of the page loop; surfaced parse-skip counts; `Sync now` logs a
  background failure; dropped the now-legacy `:credential/sync-status` write.

### Commit structure (curated; see `git log` for hashes)
History was rewritten into a clean logical story (no fixup commits): docs plan → schema → connections
data layer (+ credential-seed migration) → amount normalization → overlay contract → reported-balance
history → generic sync orchestrator → CSV through the ingest point → Plaid one-page sync + structured
errors → backoff + Plaid error classification → resync engine (deletes `plaid.service`) → triggers
(`bb resync` / `Sync now`) → docs.

## What's built (the new seam) — file map

```
backend/src/finance_aggregator/
  data/schema.clj          + :connection/* (provider-agnostic sync-state), :account/{reported-balance,
                             available-balance,balance-as-of}, :snapshot/id (idempotent daily key).
                             :credential/* RETAINED (token store); its sync fields are now legacy.
  db/connections.clj  NEW   Data layer for :connection/* — the unit resync iterates. Generic, opaque
                             sync-state. fns: ensure-connection! get/list-connections get/set-sync-state!
                             set-status! record-attempt! record-success! set-error! clear-error!
                             delete-connection!. Status vocab: :pending :syncing :backfilling :synced
                             :stale :needs-reconnect :failed.
  db/snapshots.clj    NEW   record-reported-balances! — stamps :account/balance-as-of + one reported
                             snapshot per account per UTC day (idempotent via :snapshot/id). `as-of`
                             passed in (deterministic). Reconciliation (Phase 4) reads this history.
  db/accounts.clj     +     inverted-account-ids (accounts with :account/invert-amount true).
  provider/normalize.clj NEW Pure. normalize-amounts: applies per-account :account/invert-amount EXACTLY
                             ONCE at import (it was written-but-read-nowhere before). Providers' parsers
                             still do their native→canonical sign flip (Plaid negates, Lunchflow passes).
  provider/contract.clj  NEW Pure. assert-no-overlay-keys! — sync may update provenance fields but must
                             never write user overlays (user-description, reviewed, category, splits,
                             transfer-pair/-rejected). overlay-keys is the canonical set.
  provider/retry.clj  NEW   Pure backoff math: default-policy + next-retry-at + stale-retry-at +
                             exhausted? (bounded by attempt count AND elapsed wall-clock). Capped exp
                             backoff w/ jitter. No error vocabulary (that's provider-specific).
  plaid/errors.clj    NEW   Pure. Plaid's error-code vocab + classify -> :retry/:reconnect/:fail, and
                             sync-error (prefers the failing call's code; /item/get supplement only when
                             absent; LOGIN_REPAIRED -> :resolved). Registered behind
                             provider/classify-sync-error in plaid/provider.clj.
  provider/sync.clj   +     persist-transactions! = THE single ingest point: normalize → assert-no-
                             overlay-keys! → db/insert!. sync-provider! also records balances after
                             persisting accounts. Used by the orchestrator loop, the Plaid historical
                             poll, AND CSV import.
  plaid/data.clj      ~     parse-account keeps balances (current/available) instead of discarding them.
  plaid/provider.clj  ~     historical poll persists via sync/persist-transactions!.
  plaid/service.clj   ~     per-month /transactions/get cluster DELETED; plaid.provider now a load-only
                             require (registers :plaid methods).
  csv/service.clj     ~     import goes through sync/persist-transactions! (was a direct d/transact!).
```

### Phase 2 core additions (the cutover + engine)

```
backend/src/finance_aggregator/
  resync.clj          NEW   The trigger-decoupled core. resync-connection! (one resumable pass for a
                            connection; resumes from the persisted sync-state) + resync-all! (reconcile
                            credential registry -> connections via ensure-from-credential!, then drive
                            every DUE connection, isolated; skips backing-off / :needs-reconnect / a
                            still-:syncing in-flight pass). Maps provider terminal status -> connection
                            vocab (:syncing-historical -> resumable :backfilling). Error handling is
                            provider-agnostic: provider/classify-sync-error returns a generic action and
                            the core owns the backoff bookkeeping (first-failure-at + elapsed ceiling,
                            then a slow stale-retry cadence). connection-deps is the ONE provider-aware
                            spot. Plaid is the only wired provider (load-only require registers it).
  resync/main.clj     NEW   :gen-class headless entry. ig/init subset (secrets + db + plaid/config, NO
                            http server), one resync-all!, System/exit 0. `clojure -M:resync` / cron.
  db/connections.clj  +     ensure-from-credential! (lazy, idempotent cursor-seed migration) +
                            legacy-status->status map.
  db/credentials.clj  +/-   + list-plaid-item-credential-entities (raw [*] pull for the migration);
                            REMOVED get/update-sync-cursor, get/update-sync-status, reset-sync-cursor!.
  provider/sync.clj   ~     per-page set-sync-state! after each page persists (cursor-after-persist),
                            when deps carry a :connection-id.
  plaid/provider.clj  ~     fetch-transactions yields ONE page/call (cursor in via opts, out via
                            :sync-state); poll-for-historical-transactions! DELETED; no DB/ws access here.
  plaid/client.clj    +     execute! chokepoint: every call inspects the Retrofit response and, on a
                            non-2xx, throws an ex-info carrying Plaid's structured error_code (fixes a
                            latent NPE-on-error). + fetch-item-error (/item/get health supplement) +
                            create-update-link-token (update-mode re-auth, no products).
  web/{routes,pages/setup}.clj +  POST /setup/sync "Sync now" -> background resync-all! -> 303.
  deps.edn :resync alias; bb.edn `bb resync` task.

DELETED: plaid/service.clj (+ service_test) — the per-item orchestration the engine supersedes.
Tests: resync_test (initial/resume/backfill/crash-cursor/error-classification/skip), connections_test
(ensure-from-credential), provider/sync_test (per-page set-sync-state); credentials_test cursor section
removed.
```

Tests mirror each: `db/{connections,accounts,snapshots}_test`, `provider/{normalize,contract,retry}_test`,
plus an overlay-preservation regression in `provider/sync_test` (proves a Plaid `modified` re-import
keeps user edits).

**Why `db/insert!` is overlay-safe (load-bearing, don't break):** `db.clj/insert!` is a plain additive
`d/transact!`; upsert-by-`:transaction/external-id` asserts only the keys present and never retracts
omitted attrs. So a `modified` re-import updates provenance and leaves overlays intact. **Never refactor
`modified` into retract-then-insert.** `provider.contract` enforces the other half (parsers never emit
overlay keys).

## Locked decisions

1. **Trigger-decoupled resync.** A pure resync core; trigger is separate. **`bb resync`** task →
   `clojure -M:resync` (JVM needed — Datalevin + Plaid SDK can't run under babashka SCI). Cron-able
   later with zero new code. No in-process scheduler yet.
2. **Drop pending transactions** (posted-only). No `pending_transaction_id`, no pending→posted.
3. **Reconciliation = visible adjustment entry** (additive overlay txn), never a silent balance set.
4. **Provider-agnostic seam** — must survive swapping the whole provider (Lunchflow ≠ Plaid; SimpleFIN
   was a third shape; Open-Banking OAuth is the future once it lands in-country; manual has no sync).
   Generic `:connection/*` + opaque sync-state; no Plaid-isms (item-id/cursor/update-mode) in generic code.
5. **Same account from two providers** = to the backend just importing dedupable transactions (dedup =
   Phase 3).
6. **Reconciliation UI is a workbench** — spreadsheet-seamless, integrated with the categorize/
   transactions workflow. PAUSE and design it intentionally when Phase 4 reaches the UI.
7. **Backoff:** capped exponential + jitter, bounded by attempt count AND elapsed wall-clock
   (`:connection/first-failure-at` makes the time bound real). Default `provider.retry/default-policy`:
   base 1m, ×2, cap 15m, ≤8 retries, 2h ceiling, equal jitter → retries at 1,2,4,8,15,15,15,15 min.
   Once the budget is spent it does NOT give up: it parks `:stale` and falls back to a slow steady
   `stale-retry-ms` (1h) cadence so a long outage self-heals. Only transient errors retry; terminal go
   to `:needs-reconnect`; any success clears the streak (first-failure-at + retry-count).
8. **Link surface = Plaid Hosted Link** (Phase 5 UI): Plaid-hosted URL, zero frontend JS, handles OAuth
   server-side; retrieve `public_token` by polling `/link/token/get` (no public webhook endpoint).
   **Re-auth = Link update mode** (token from existing access-token, `products` omitted) — backend in
   Phase 2.

## Phase 2 core — DONE; THE NEXT STEP is Phase 3

The cutover + resync engine landed (see the curated commit structure above; file map below).
All four sub-goals shipped: (a) connections ensured-from-credentials at sync time with a lazy cursor
seed; (b) `resync-connection!`/`resync-all!` over connections with per-page cursor persistence; (c) the
2h poll deleted, backfill now the resumable `:backfilling`; (d) `/item/get` error classification +
update-mode re-auth token; (e) `bb resync` / `clojure -M:resync` / "Sync now".

**Realized differently from the (a)-(e) sketch (intentional, cleaner):** rather than repointing the dead
per-item `plaid.service`, it was **deleted** — `resync.clj` is the single sync entry. The cursor moved
out of Plaid's `:on-complete` into the generic per-page `provider/sync.clj` advance, so cursor-after-
persist holds per page. ws status stays Plaid-flavored (`:syncing-historical`, keyed by item-id) while
the connection vocab is generic (`:backfilling`); the orchestrator never names a Plaid-ism.

**NEXT — Phase 3** (see the Deferred section below and the plan doc): dedup/merge across providers;
surface drift when a Plaid `modified` changes a user-overridden field; manual↔synced match (exact amount
+ ±10 days, confirm); account merge. Then Phase 4 (reconciliation workbench — **pause to design the
UI**) and Phase 5 (wire the stubbed setup UI: Hosted Link, CSV, manual accounts, connection management —
the "Sync now" button is the first live action there; bring `setup.clj` to the 4-layer standard).

### To exercise the engine before Phase 3
REPL: `clojure -M:repl -m nrepl.cmdline` → `(dev)`/`(go)`; build deps `{:db-conn .. :secrets ..
:plaid-config ..}` and call `(resync/resync-all! deps)` against a sandbox item — confirm idempotent
re-run resumes from the stored cursor, backfill `:backfilling`→`:synced`, and `:needs-reconnect` on a
forced `ITEM_LOGIN_REQUIRED`. Cron-readiness: `clojure -M:resync` runs one pass and exits 0; `bb resync`
wraps it. (Drive it headless before trusting a cron — it makes real Plaid calls.)

## Research findings to apply (condensed)

**Plaid connections — current vs legacy** (verified against live docs 2026-06-23):
- Max history on initial link: `transactions.days_requested: 730` (default 90; best-effort, institution-
  capped — e.g. Capital One only 90d). Set once; not extendable later.
- **Hosted Link** (GA, self-serve) is the modern fit for a low-JS SSR app: create link_token with a
  `hosted_link` object → redirect to `hosted_link_url` → poll `/link/token/get` (6h window) for the
  `public_token` (or `SESSION_FINISHED` webhook if we ever expose one). Handles OAuth server-side.
- Update mode for re-auth: link_token from existing access_token, **omit `products`**; nested
  `update.account_selection_enabled` to add/remove accounts. `LOGIN_REPAIRED` = self-heal.
- Health w/o webhooks: a broken Item makes `/transactions/sync` itself return `400 ITEM_LOGIN_REQUIRED`
  (free signal); supplement with a low-frequency `/item/get` poll for `item.error` +
  `consent_expiration_time`.
- **Dead/avoid:** `public_key` init (sunset Feb 2025), Link without a `link_token`, manual `oauth_nonce`
  re-entry. (`/transactions/get` is "not recommended", not removed — but we deleted our use of it.)

**Backoff:** AWS "Exponential Backoff And Jitter" (capped exponential + jitter; full or equal jitter),
bound retries by attempts AND elapsed time (AWS Well-Architected), classify on `error_code` not HTTP
status (Plaid). Numbers encoded in `provider.retry/default-policy`.

## Gotchas

- **`resync` requires `plaid.provider` as a load-only require** (no `:as`) — it registers the `:plaid`
  multimethods (fetch-accounts / fetch-transactions / classify-sync-error). Don't "clean up" that
  require. (`lunchflow.provider` is wired nowhere yet — consistent with the disabled setup page.)
- **Classification reads the failing call's `error_code` first** (carried in the ex-info by
  `plaid.client/execute!`); `/item/get` is only consulted when the call carried no code. Don't invert
  that back to "/item/get only" — the transient codes (rate-limit / 5xx) never appear on `item.error`.
- **Backoff bookkeeping lives in `resync`, not `db.connections`:** the data layer just persists what
  it's handed (retry-count / first-failure-at / next-retry-at); `resync.handle-error!` decides them.
  `record-success!`/`clear-error!` retract the whole streak — keep them the single reset point.
- **Overlapping passes:** `Sync now` and a cron `resync` can run concurrently. `due?` skips a connection
  already `:syncing` within `stuck-syncing-ms` (10m); past that it's presumed crashed and runs again.
  There's no cross-process lock — fine for single-user; revisit if a scheduler is added.
- **Cursor-on-loop-completion invariant (CORRECTED 2026-06-26):** the durable sync-state advances ONLY
  when the pagination loop completes (the terminal `has_more=false` page), never mid-loop. The earlier
  per-page "cursor-after-persist" was a bug: Plaid invalidates a mid-pagination cursor once the
  underlying data changes, so resuming from a persisted mid-pagination cursor fails permanently with
  `TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION` (must restart the loop from its start cursor, not re-run
  from where it failed). A crash mid-loop now leaves the durable cursor at the loop start; the next pass
  restarts and idempotently re-pulls (replay safe; skipping still prevented). The mutation error
  classifies to the generic `:reset` action → discard the cursor + re-sync from scratch (bounded), which
  self-heals any cursor the old behavior corrupted.
- **WebSocket status** (`ws/state.clj`) is keyed per item-id for Plaid (the `:status-key` dep). When the
  engine moves to connections, derive the status key from the connection; keep `:syncing-historical`
  semantics behind Plaid (don't let it leak into generic code) — this seals the last seam leaks.
- **Datalevin schema is additive** at every `get-conn` — new attrs need no migration; moved *data* (the
  cursor seed) does, handled lazily in (a).
- **No `Date.now()`/`rand` purity issues in app code** (only workflow scripts forbid them) — but keep
  clock/`rand` injectable where tests need determinism (`db/snapshots` and `provider/retry` already do).
- **`:account/invert-amount` is now import-time-only.** Existing rows aren't retroactively flipped if the
  flag is toggled later — a one-off renormalize action is a known TODO (Phase 5 settings UI).

## Run & verify

```bash
jabba use zulu@25.0.3                                   # once per shell
cd backend && clojure -M:test -m kaocha.runner          # kaocha (all green)
bb lint                                                 # clj-kondo + tsc (run from repo root)
bb e2e                                                  # Playwright (UI changes)
```
- REPL drive of the engine (once built): `clojure -M:repl -m nrepl.cmdline` → `(dev)`/`(go)`; run
  `resync-all!` against a sandbox Plaid item; confirm idempotent re-run, backfill `:backfilling`→
  `:synced`, and `:needs-reconnect` on a forced `ITEM_LOGIN_REQUIRED`.
- Cron-readiness target: `clojure -M:resync` runs one pass and exits 0; `bb resync` wraps it.

## Deferred (later phases — see the plan doc)

- **Phase 3:** dedup/merge + surface drift when a Plaid `modified` changes a field the user overrode;
  manual↔synced match (exact amount + ±10 days, confirm); account merge.
- **Phase 4:** opening-balance entry; computed-vs-reported drift banner; cleared/reconciled states +
  reconciliation events; confirm → visible adjustment entry. **Pause to design the workbench UI.**
- **Phase 5:** wire the stubbed setup UI (Hosted Link, CSV, manual accounts) + connection management
  (Sync now / Reconnect / Remove); dense-table manual CRUD; revert-to-bank + edited indicator. Bring
  `setup.clj` to the 4-layer standard.
