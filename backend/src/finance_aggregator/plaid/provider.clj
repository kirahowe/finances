(ns finance-aggregator.plaid.provider
  "Plaid's implementation of the provider seam.

   Registers the `:plaid` methods of `finance-aggregator.provider`, producing the
   canonical institution/account/transaction maps that the generic sync
   orchestrator persists. All of Plaid's idiosyncrasies live here behind that
   uniform data contract:
   - cursor-based `/transactions/sync` pagination - ONE page per
     `fetch-transactions` call; the orchestrator loops via :more?/:next-opts and
     advances the connection's opaque sync-state (the cursor) after each page is
     persisted (cursor-after-persist),
   - the `transactions_update_status` -> terminal status mapping
     (:synced / :syncing-historical / :pending). A `:syncing-historical` terminal
     means the backfill is incomplete; the engine maps that to the connection's
     resumable :backfilling status and the next resync pass advances it from the
     stored cursor until Plaid reports :historical-update-complete.

   Cursor IN comes from opts (:cursor, seeded by the engine from the connection's
   sync-state); cursor OUT is returned as :sync-state for the orchestrator to
   persist. No DB access here - this namespace is the canonical-data transform.

   Pure transforms live in `finance-aggregator.plaid.data`; HTTP in
   `finance-aggregator.plaid.client`."
  (:require
   [finance-aggregator.auth :as auth]
   [finance-aggregator.lib.log :as log]
   [finance-aggregator.plaid.client :as client]
   [finance-aggregator.plaid.data :as data]
   [finance-aggregator.plaid.errors :as errors]
   [finance-aggregator.provider :as provider]))

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

;;; Provider seam: :plaid methods

(defmethod provider/classify-sync-error :plaid
  [_ {:keys [plaid-config access-token]} ^Exception e]
  ;; Primary signal: the error_code the failing call surfaced via the client's
  ;; execute! (parsed from Plaid's error body). Supplement with /item/get only
  ;; when the call carried no code - item-level problems are reported there.
  (let [primary (ex-data e)
        item-error (when (and (nil? (:error-code primary)) access-token)
                     (client/fetch-item-error plaid-config access-token))]
    (errors/sync-error primary item-error (.getMessage e))))

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
  [_ {:keys [plaid-config cursor item-id access-token institution-name] :as opts}]
  (let [initial-sync? (nil? cursor)
        days-requested (:days-requested plaid-config)]
    (when (and initial-sync? (nil? days-requested))
      (throw (ex-info "days-requested is required in plaid-config for initial sync"
                      {:hint "Add :days-requested to plaid link-config"})))

    (let [response (client/sync-transactions plaid-config access-token cursor
                                             {:count 500 :days-requested days-requested})
          update-status (:transactions_update_status response)
          added (:added response)
          modified (:modified response)
          removed (:removed response)
          next-cursor (:next_cursor response)
          has-more (:has_more response)
          upsert-txns (concat added modified)
          {parsed-txns :success
           tx-errors :errors} (safe-parse-transactions upsert-txns auth/user-id)
          removed-ext-ids (mapv :transaction_id removed)
          progress {:added (count added)
                    :modified (count modified)
                    :removed (count removed)}]
      (if has-more
        ;; Mid-paging: hand the orchestrator this page, advance the cursor, and
        ;; loop. next-cursor is always valid here, so persist it unconditionally.
        {:transactions parsed-txns
         :removed removed-ext-ids
         :more? true
         :next-opts (assoc opts :cursor next-cursor)
         :sync-state next-cursor
         :errors tx-errors}

        ;; Last page: compute the terminal status from the update status, and
        ;; only advance the cursor if there was data (so an empty :not-ready
        ;; initial sync re-runs from scratch next pass instead of sticking).
        (let [historical-complete? (= :historical-update-complete update-status)
              initial-complete? (= :initial-update-complete update-status)
              has-data? (pos? (+ (count added) (count modified) (count removed)))
              final-status (cond
                             historical-complete? :synced
                             (or initial-complete? has-data?) :syncing-historical
                             :else :pending)
              store-cursor? (and (seq next-cursor)
                                 (or historical-complete? initial-complete? has-data?))]
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
           :sync-state (when store-cursor? next-cursor)
           :status final-status
           :status-opts {:institution-name institution-name
                         :transaction-count (count parsed-txns)
                         :progress progress}
           :errors tx-errors})))))
