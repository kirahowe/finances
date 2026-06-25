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
   [finance-aggregator.provider.normalize :as normalize]
   [finance-aggregator.ws.state :as ws])
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
   opts to fetch-accounts and as the initial opts to fetch-transactions.
   An optional :status-key in deps overrides the WebSocket status key (Plaid
   keys status per item-id rather than per provider). An optional :connection-id
   in deps enables per-page sync-state persistence (the resumable cursor).

   Each `fetch-transactions` page may carry:
   - :sync-state  the new opaque per-provider sync-state (Plaid's next cursor),
                  persisted to the connection AFTER this page is inserted -
                  cursor-after-persist, so a crash loses <=1 page (re-pulled by
                  the idempotent upsert). Generic: the provider supplies it, the
                  orchestrator never interprets it. Only advanced when deps carry
                  a :connection-id.

   The final page may additionally carry provider-specific finalization:
   - :status      terminal ws status to publish (default :synced). Lets Plaid
                  report :syncing-historical / :pending instead of :synced.
   - :status-opts extra kvs (:institution-name :transaction-count :progress)
                  merged into the terminal ws push.
   - :on-complete a thunk run AFTER the final persist + retract.

   Each page may also carry :errors (items the provider couldn't parse); the
   counts are summed across pages and warned once at the end, so a silently
   skipped transaction still leaves a trail.

   Pushes :syncing -> <terminal> (or :failed) to the WebSocket status state.
   Re-throws after marking :failed."
  [{:keys [db-conn connection-id] :as deps} provider-key]
  (let [status-key (or (:status-key deps) (name provider-key))]
    (ws/update-sync-status! status-key :syncing)
    (try
      (let [{:keys [institutions accounts]} (provider/fetch-accounts provider-key deps)]
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
          (let [{:keys [transactions removed more? next-opts sync-state status status-opts on-complete errors]}
                (provider/fetch-transactions provider-key opts)]
            (persist-transactions! db-conn transactions inverted-ids)
            (retract-removed! db-conn removed)
            ;; Advance the resumable cursor only after the page is persisted.
            (when (and connection-id sync-state)
              (connections/set-sync-state! db-conn connection-id sync-state))
            (let [skipped (+ skipped (count errors))]
              (if more?
                (recur next-opts skipped)
                (let [terminal (or status :synced)]
                  (when (pos? skipped)
                    (log/warn "Provider transactions skipped on parse"
                              {:provider provider-key :status-key status-key :skipped skipped}))
                  (when on-complete (on-complete))
                  (apply ws/update-sync-status! status-key terminal
                         (mapcat identity status-opts))
                  terminal))))))
      (catch Exception e
        (ws/update-sync-status! status-key :failed :error (.getMessage e))
        (throw e)))))
