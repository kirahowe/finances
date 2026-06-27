(ns finance-aggregator.provider.sync
  "Generic, provider-agnostic sync orchestrator.

   Drives any provider through a uniform loop: fetch accounts, persist them,
   then page through transactions (insert + retract removed) until the provider
   signals it's done. Provider-specific quirks (Plaid's opaque cursor paging and
   historical backfill) live behind :more?/:next-opts inside the provider's
   `fetch-transactions` method - this loop stays uniform."
  (:require
   [datalevin.core :as d]
   [finance-aggregator.db :as db]
   [finance-aggregator.db.accounts :as db-accounts]
   [finance-aggregator.db.connections :as connections]
   [finance-aggregator.db.snapshots :as snapshots]
   [finance-aggregator.lib.log :as log]
   [finance-aggregator.provider :as provider]
   [finance-aggregator.provider.contract :as contract]
   [finance-aggregator.provider.normalize :as normalize])
  (:import
   [java.util Date]))

(defn persist-transactions!
  "The single ingest point every provider's transactions pass through: apply the
   per-account :account/invert-amount flip exactly once (provider.normalize),
   assert no user-overlay keys leak in (provider.contract), then insert. Accounts
   are already persisted (sync-provider! inserts them first), so the inverted-id
   query sees them.

   The 3-arity takes a precomputed inverted-id set (so the paging loop queries it
   once, not per page); the 2-arity computes it - the one-shot path (CSV import)."
  ([db-conn transactions]
   (persist-transactions! db-conn transactions (db-accounts/inverted-account-ids db-conn)))
  ([db-conn transactions inverted-ids]
   (db/insert! {:institutions #{}
                :accounts #{}
                :transactions (-> transactions
                                  (normalize/normalize-amounts inverted-ids)
                                  contract/assert-no-overlay-keys!)}
               db-conn)))

(defn retract-removed!
  "Retract transactions whose canonical external-ids are in `external-ids`.
   Resolves each external-id to an entity in a single db snapshot, then
   retracts in one batch. No-op when nothing was removed."
  [db-conn external-ids]
  (when (seq external-ids)
    (let [db (d/db db-conn)
          eids (d/q '[:find [?e ...]
                      :in $ [?ext-id ...]
                      :where [?e :transaction/external-id ?ext-id]]
                    db external-ids)]
      (when (seq eids)
        (d/transact! db-conn (mapv (fn [eid] [:db/retractEntity eid]) eids))))))

(defn sync-provider!
  "Sync a single provider end-to-end. Returns the terminal sync status.

   deps: {:db-conn .. :secrets ..} plus anything a provider needs; passed as the
   opts to fetch-accounts and as the initial opts to fetch-transactions. An
   optional :connection-id in deps enables resumable sync-state persistence (the
   cursor, on loop completion) and stamps each account with its owning connection.

   Each `fetch-transactions` page may carry:
   - :sync-state  the new opaque per-provider sync-state (Plaid's next cursor),
                  persisted to the connection only once the pagination loop
                  COMPLETES (the terminal page) - never mid-loop. A mid-pagination
                  cursor must not be durably stored: Plaid invalidates it if the
                  underlying data mutates, and resuming from it then fails
                  permanently (TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION). On a
                  crash mid-loop the durable cursor stays at the loop's start; the
                  next pass restarts the whole loop and idempotently re-pulls
                  (replay is safe; skipping is prevented). Generic: the provider
                  supplies it, the orchestrator never interprets it. Only advanced
                  when deps carry a :connection-id.

   The final page may additionally carry provider-specific finalization:
   - :status      terminal status to return (default :synced). Lets Plaid report
                  :syncing-historical / :pending instead of :synced.
   - :on-complete a thunk run AFTER the final persist + retract.

   Each page may also carry :errors (items the provider couldn't parse); the
   counts are summed across pages and warned once at the end, so a silently
   skipped transaction still leaves a trail.

   Exceptions propagate to the caller; resync's per-connection isolation records
   them (status side-channels live there, not here)."
  [{:keys [db-conn connection-id] :as deps} provider-key]
  (let [{:keys [institutions accounts]} (provider/fetch-accounts provider-key deps)
        ;; Stamp the owning connection onto each account (when this is a
        ;; connection-driven sync), so the setup view can group accounts by
        ;; connection and show per-connection sync freshness. Generic: the
        ;; orchestrator knows the connection-id; the provider stays unaware.
        accounts (cond->> accounts
                   connection-id
                   (map #(assoc % :account/connection [:connection/id connection-id])))]
    (db/insert! {:institutions (set institutions)
                 :accounts (set accounts)
                 :transactions []}
                db-conn)
    ;; Capture institution-reported balances now that accounts exist.
    (snapshots/record-reported-balances! db-conn accounts (Date.)))
  ;; Accounts are persisted - compute the invert-amount set once, not per page.
  (let [inverted-ids (db-accounts/inverted-account-ids db-conn)]
    (loop [opts deps
           skipped 0]
      (let [{:keys [transactions removed more? next-opts sync-state status on-complete errors]}
            (provider/fetch-transactions provider-key opts)]
        (persist-transactions! db-conn transactions inverted-ids)
        (retract-removed! db-conn removed)
        ;; Advance the resumable cursor only when the pagination loop COMPLETES
        ;; (terminal page). Storing a mid-pagination cursor is what Plaid then
        ;; rejects with TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION on resume;
        ;; a crash mid-loop instead leaves the durable cursor at the loop start
        ;; and the next pass restarts cleanly (idempotent re-pull).
        (when (and connection-id sync-state (not more?))
          (connections/set-sync-state! db-conn connection-id sync-state))
        (let [skipped (+ skipped (count errors))]
          (if more?
            (recur next-opts skipped)
            (let [terminal (or status :synced)]
              (when (pos? skipped)
                (log/warn "Provider transactions skipped on parse"
                          {:provider provider-key :skipped skipped}))
              (when on-complete (on-complete))
              terminal)))))))
