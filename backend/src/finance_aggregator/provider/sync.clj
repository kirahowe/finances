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
   [finance-aggregator.provider :as provider]
   [finance-aggregator.ws.state :as ws]))

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
   keys status per item-id rather than per provider).

   The final `fetch-transactions` return may carry provider-specific finalization:
   - :status      terminal ws status to publish (default :synced). Lets Plaid
                  report :syncing-historical / :pending instead of :synced.
   - :status-opts extra kvs (:institution-name :transaction-count :progress)
                  merged into the terminal ws push.
   - :on-complete a thunk run AFTER the final persist + retract (Plaid stores its
                  cursor, sets credential status, and triggers historical polling
                  here, so the cursor never advances past unpersisted data).

   Pushes :syncing -> <terminal> (or :failed) to the WebSocket status state.
   Re-throws after marking :failed."
  [{:keys [db-conn] :as deps} provider-key]
  (let [status-key (or (:status-key deps) (name provider-key))]
    (ws/update-sync-status! status-key :syncing)
    (try
      (let [{:keys [institutions accounts]} (provider/fetch-accounts provider-key deps)]
        (db/insert! {:institutions (set institutions)
                     :accounts (set accounts)
                     :transactions []}
                    db-conn))
      (loop [opts deps]
        (let [{:keys [transactions removed more? next-opts status status-opts on-complete]}
              (provider/fetch-transactions provider-key opts)]
          (db/insert! {:institutions #{} :accounts #{} :transactions (vec transactions)}
                      db-conn)
          (retract-removed! db-conn removed)
          (if more?
            (recur next-opts)
            (let [terminal (or status :synced)]
              (when on-complete (on-complete))
              (apply ws/update-sync-status! status-key terminal
                     (mapcat identity status-opts))
              terminal))))
      (catch Exception e
        (ws/update-sync-status! status-key :failed :error (.getMessage e))
        (throw e)))))
