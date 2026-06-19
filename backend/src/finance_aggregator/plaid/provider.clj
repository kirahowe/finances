(ns finance-aggregator.plaid.provider
  "Plaid's implementation of the provider seam.

   Registers the `:plaid` methods of `finance-aggregator.provider`, producing the
   canonical institution/account/transaction maps that the generic sync
   orchestrator persists. All of Plaid's idiosyncrasies live here behind that
   uniform data contract:
   - cursor-based `/transactions/sync` pagination (collected into one batch),
   - the `transactions_update_status` -> terminal status mapping
     (:synced / :syncing-historical / :pending),
   - storing the sync cursor only AFTER persistence (via the orchestrator's
     :on-complete hook, so the cursor never advances past unpersisted data),
   - the 2-hour background historical-backfill poll.

   Pure transforms live in `finance-aggregator.plaid.data`; HTTP in
   `finance-aggregator.plaid.client`."
  (:require
   [datalevin.core :as d]
   [finance-aggregator.auth :as auth]
   [finance-aggregator.db :as db]
   [finance-aggregator.db.credentials :as creds]
   [finance-aggregator.lib.log :as log]
   [finance-aggregator.plaid.client :as client]
   [finance-aggregator.plaid.data :as data]
   [finance-aggregator.provider :as provider]
   [finance-aggregator.ws.state :as ws-state]))

;;; Constants

(def ^:private historical-poll-interval-ms
  "Time to wait between polls for historical transaction data (30 seconds)"
  30000)

(def ^:private historical-poll-max-attempts
  "Maximum number of poll attempts before giving up (2 hours at 30s intervals)"
  240)

;;; Parse helpers (error-capturing, parallel)

(defn safe-parse-accounts
  "Parse accounts with error handling, returning successes and errors.
   Uses pmap for parallel transformation."
  [accounts institution-id user-id]
  (let [parse-fn (fn [account]
                   (try
                     {:success (data/parse-account account institution-id user-id)}
                     (catch Exception e
                       {:error {:account-id (:account_id account)
                                :message (.getMessage e)}})))
        results (pmap parse-fn accounts)]
    {:success (keep :success results)
     :errors (keep :error results)}))

(defn safe-parse-transactions
  "Parse transactions with error handling, returning successes and errors.
   Filters out pending transactions. Uses pmap for parallel transformation."
  [transactions user-id]
  (let [pending-by-account (frequencies (map :account_id (filter :pending transactions)))
        non-pending-by-account (frequencies (map :account_id (remove :pending transactions)))
        _ (when (seq pending-by-account)
            (log/info "Transaction pending status by account"
                      {:pending-by-account pending-by-account
                       :non-pending-by-account non-pending-by-account
                       :total-pending (count (filter :pending transactions))
                       :total-non-pending (count (remove :pending transactions))}))
        parse-fn (fn [txn]
                   (try
                     (when-let [parsed (data/parse-transaction txn user-id)]
                       {:success parsed})
                     (catch Exception e
                       {:error {:transaction-id (:transaction_id txn)
                                :message (.getMessage e)}})))
        results (pmap parse-fn transactions)]
    {:success (keep :success results)
     :errors (keep :error results)}))

;;; Historical backfill poll (background future)

(defn- poll-for-historical-transactions!
  "Background poll for historical transaction data.

   Called when initial sync completes with :initial-update-complete. Polls
   /transactions/sync periodically until :historical-update-complete or max
   attempts reached. Runs asynchronously in a future - returns immediately.

   deps: {:db-conn .. :plaid-config ..}
   item-credential: {:item-id .. :access-token .. :institution-name ..}"
  [{:keys [db-conn plaid-config]}
   {:keys [item-id access-token institution-name]}]
  (future
    (try
      (loop [attempt 1]
        (Thread/sleep historical-poll-interval-ms)

        (log/info "Polling for historical transactions"
                  {:item-id item-id
                   :institution institution-name
                   :attempt attempt
                   :max-attempts historical-poll-max-attempts})

        (let [cursor (creds/get-sync-cursor db-conn item-id)]
          (if-not cursor
            (log/warn "Item no longer exists, stopping historical poll"
                      {:item-id item-id})

            (let [days-requested (:days-requested plaid-config)
                  response (client/sync-transactions
                            plaid-config access-token cursor
                            {:count 500 :days-requested days-requested})
                  update-status (:transactions_update_status response)
                  historical-complete? (= :historical-update-complete update-status)
                  added (:added response)
                  modified (:modified response)
                  removed (:removed response)
                  next-cursor (:next_cursor response)]

              (log/info "Historical poll response - DETAILED"
                        {:item-id item-id
                         :update-status update-status
                         :added-count (count added)
                         :modified-count (count modified)
                         :removed-count (count removed)
                         :historical-complete? historical-complete?})

              (when (or (seq added) (seq modified))
                (let [upsert-txns (concat added modified)
                      tx-results (safe-parse-transactions upsert-txns auth/user-id)
                      parsed-txns (:success tx-results)]
                  (when (seq parsed-txns)
                    (db/insert! {:institutions #{}
                                 :accounts #{}
                                 :transactions parsed-txns}
                                db-conn)
                    (log/info "Persisted historical transactions"
                              {:item-id item-id :count (count parsed-txns)}))))

              (when (seq removed)
                (let [removed-tx-ids (map :transaction_id removed)
                      db-snapshot (d/db db-conn)
                      existing-eids (d/q '[:find [?e ...]
                                           :in $ [?tx-id ...]
                                           :where [?e :transaction/external-id ?tx-id]]
                                         db-snapshot removed-tx-ids)
                      retract-ops (mapv (fn [eid] [:db/retractEntity eid]) existing-eids)]
                  (when (seq retract-ops)
                    (d/transact! db-conn retract-ops))))

              (when (seq next-cursor)
                (creds/update-sync-cursor! db-conn item-id next-cursor))

              (cond
                historical-complete?
                (do
                  (log/info "Historical transactions complete"
                            {:item-id item-id :institution institution-name})
                  (creds/update-sync-status! db-conn item-id :synced {})
                  (ws-state/update-sync-status! item-id :synced
                                                :institution-name institution-name
                                                :message "Historical transactions loaded"))

                (>= attempt historical-poll-max-attempts)
                (do
                  (log/warn "Max poll attempts reached, historical data may be incomplete"
                            {:item-id item-id :attempts attempt})
                  (creds/update-sync-status! db-conn item-id :synced
                                             {:message "Historical data may be incomplete"})
                  (ws-state/update-sync-status! item-id :synced
                                                :institution-name institution-name
                                                :message "Historical data may be incomplete"))

                :else
                (recur (inc attempt)))))))

      (catch Exception e
        (log/error "Error polling for historical transactions"
                   {:item-id item-id :error (.getMessage e)})
        (creds/update-sync-status! db-conn item-id :failed)
        (ws-state/update-sync-status! item-id :failed
                                      :institution-name institution-name
                                      :error "Failed to fetch historical transactions")))))

;;; Provider seam: :plaid methods

(defmethod provider/fetch-accounts :plaid
  [_ {:keys [plaid-config access-token]}]
  ;; Fetch item + accounts in parallel; cancel accounts if item lookup fails.
  (let [item-future (future (client/fetch-item plaid-config access-token))
        accounts-future (future (client/fetch-accounts plaid-config access-token))
        item (try
               @item-future
               (catch Exception e
                 (future-cancel accounts-future)
                 (throw e)))
        accounts @accounts-future
        institution-id (:institution_id item)
        institution (client/fetch-institution plaid-config institution-id)
        parsed-institution (data/parse-institution institution)
        {parsed-accounts :success
         account-errors :errors} (safe-parse-accounts accounts institution-id auth/user-id)]
    (log/info "Fetched Plaid Item accounts"
              {:institution-id institution-id
               :accounts-count (count parsed-accounts)
               :errors (count account-errors)})
    {:institutions #{parsed-institution}
     :accounts (set parsed-accounts)}))

(defmethod provider/fetch-transactions :plaid
  [_ {:keys [db-conn plaid-config item-id access-token institution-name]}]
  (let [current-cursor (creds/get-sync-cursor db-conn item-id)
        initial-sync? (nil? current-cursor)
        days-requested (:days-requested plaid-config)]
    (when (and initial-sync? (nil? days-requested))
      (throw (ex-info "days-requested is required in plaid-config for initial sync"
                      {:hint "Add :days-requested to plaid link-config"})))

    (log/info "Starting Plaid transaction sync"
              {:item-id item-id
               :institution institution-name
               :mode (if initial-sync? "INITIAL" "INCREMENTAL")})

    (ws-state/update-sync-status! item-id :syncing
                                  :institution-name institution-name
                                  :transaction-count 0
                                  :progress {:added 0 :modified 0 :removed 0})

    ;; Collect all pages before processing (cursor sync, opaque pagination).
    (loop [cursor current-cursor
           all-added []
           all-modified []
           all-removed []]
      (let [response (client/sync-transactions plaid-config access-token cursor
                                               {:count 500 :days-requested days-requested})
            update-status (:transactions_update_status response)
            added (into all-added (:added response))
            modified (into all-modified (:modified response))
            removed (into all-removed (:removed response))
            next-cursor (:next_cursor response)
            has-more (:has_more response)]

        (if has-more
          (do
            (ws-state/update-sync-status! item-id :syncing
                                          :institution-name institution-name
                                          :transaction-count (count added)
                                          :progress {:added (count added)
                                                     :modified (count modified)
                                                     :removed (count removed)})
            (recur next-cursor added modified removed))

          ;; Last page: transform to canonical, compute terminal status, and
          ;; defer cursor/status/poll finalization to :on-complete (post-persist).
          (let [upsert-txns (concat added modified)
                {parsed-txns :success
                 tx-errors :errors} (safe-parse-transactions upsert-txns auth/user-id)
                removed-ext-ids (mapv :transaction_id removed)
                historical-complete? (= :historical-update-complete update-status)
                initial-complete? (= :initial-update-complete update-status)
                has-data? (pos? (+ (count added) (count modified) (count removed)))
                final-status (cond
                               historical-complete? :synced
                               (or initial-complete? has-data?) :syncing-historical
                               :else :pending)
                store-cursor? (and (seq next-cursor)
                                   (or historical-complete? initial-complete? has-data?))
                progress {:added (count added)
                          :modified (count modified)
                          :removed (count removed)}]
            (log/info "Completed Plaid transaction page collection"
                      {:item-id item-id
                       :institution institution-name
                       :mode (if initial-sync? "INITIAL" "INCREMENTAL")
                       :added (count added)
                       :modified (count modified)
                       :removed (count removed)
                       :persisted (count parsed-txns)
                       :update-status update-status
                       :final-status final-status
                       :errors (count tx-errors)})
            {:transactions parsed-txns
             :removed removed-ext-ids
             :more? false
             :cursor next-cursor
             :errors tx-errors
             :status final-status
             :status-opts {:institution-name institution-name
                           :transaction-count (count parsed-txns)
                           :progress progress}
             :on-complete
             (fn []
               (when store-cursor?
                 (let [updated? (creds/update-sync-cursor! db-conn item-id next-cursor)]
                   (when-not updated?
                     (log/warn "Could not update sync cursor - item may have been deleted"
                               {:item-id item-id}))))
               (creds/update-sync-status! db-conn item-id final-status
                                          {:transaction-count (count parsed-txns)})
               (when (and (= final-status :syncing-historical) initial-sync?)
                 (log/info "Starting background poll for historical transactions"
                           {:item-id item-id :institution institution-name})
                 (poll-for-historical-transactions!
                  {:db-conn db-conn :plaid-config plaid-config}
                  {:item-id item-id
                   :access-token access-token
                   :institution-name institution-name})))}))))))
