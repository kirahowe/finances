(ns finance-aggregator.plaid.service
  "Service orchestration for Plaid account and transaction syncing.

   Handles the complete flow:
   - Fetch data from Plaid API (parallel where possible)
   - Transform to database schema
   - Persist to database
   - Error handling with partial success
   - Push real-time status updates via WebSocket

   All functions are pure at the transformation level, with side effects
   isolated to API calls and database operations."
  (:require
   [datalevin.core :as d]
   [finance-aggregator.db :as db]
   [finance-aggregator.db.credentials :as creds]
   [finance-aggregator.lib.log :as log]
   [finance-aggregator.plaid.client :as client]
   [finance-aggregator.plaid.data :as data]
   [finance-aggregator.utils :as utils]
   [finance-aggregator.ws.state :as ws-state])
  (:import
   [java.time YearMonth]
   [java.time.format DateTimeFormatter]))

;;; Constants

(def ^:private hardcoded-user-id "test-user")
(def ^:private date-formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd"))

;; Background polling configuration for historical transactions
(def ^:private historical-poll-interval-ms
  "Time to wait between polls for historical transaction data (30 seconds)"
  30000)

(def ^:private historical-poll-max-attempts
  "Maximum number of poll attempts before giving up (2 hours at 30s intervals)"
  240)

;;; Helper Functions

(defn- safe-parse-accounts
  "Parse accounts with error handling, returning successes and errors.
   Uses pmap for parallel transformation."
  [accounts institution-id user-id item-id]
  (let [parse-fn (fn [account]
                   (try
                     {:success (data/parse-account account institution-id user-id item-id)}
                     (catch Exception e
                       {:error {:account-id (:account_id account)
                               :message (.getMessage e)}})))
        results (pmap parse-fn accounts)]
    {:success (keep :success results)
     :errors (keep :error results)}))

(defn- safe-parse-transactions
  "Parse transactions with error handling, returning successes and errors.
   Filters out pending transactions. Uses pmap for parallel transformation."
  [transactions user-id]
  (let [;; Count pending transactions per account before filtering
        pending-by-account (frequencies (map :account_id (filter :pending transactions)))
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

;;; Public API

(defn sync-item-accounts!
  "Sync accounts for a single Plaid Item to database.

   Fetches:
   - Item metadata (to get institution_id)
   - Institution details (name, URL, etc.)
   - All accounts for the access token

   Uses parallel fetching for item and institution data where possible.

   deps: {:db-conn datalevin-connection
          :plaid-config plaid-configuration-map}
   item-credential: {:item-id string
                     :institution-name string
                     :access-token string}

   Returns: {:success {:institutions int, :accounts int}
            :failed {:institutions int, :accounts int}
            :errors [{:type keyword, :message string} ...]}"
  [{:keys [db-conn plaid-config]} {:keys [item-id access-token institution-name]}]
  (try
    ;; 1. Fetch item and accounts in parallel
    (let [item-future (future (client/fetch-item plaid-config access-token))
          accounts-future (future (client/fetch-accounts plaid-config access-token))
          ;; Deref item first, cancel accounts if it fails
          item (try
                 @item-future
                 (catch Exception e
                   (future-cancel accounts-future)
                   (throw e)))
          accounts @accounts-future
          institution-id (:institution_id item)]

      ;; 2. Fetch institution details
      (let [institution (client/fetch-institution plaid-config institution-id)

            ;; 3. Parse data (parallel transformation)
            parsed-institution (data/parse-institution institution)
            account-results (safe-parse-accounts accounts institution-id hardcoded-user-id item-id)
            parsed-accounts (:success account-results)
            account-errors (:errors account-results)]

        ;; 4. Persist to database
        (db/insert! {:institutions #{parsed-institution}
                     :accounts (set parsed-accounts)
                     :transactions []}
                    db-conn)

        ;; 5. Log and return results
        (log/info "Synced Plaid Item accounts - DETAILED"
                  {:item-id item-id
                   :institution institution-name
                   :accounts-count (count parsed-accounts)
                   :account-ids (map :account/external-id parsed-accounts)
                   :account-types (map (fn [a] {:id (:account/external-id a)
                                                :type (:account/plaid-type a)
                                                :subtype (:account/plaid-subtype a)
                                                :name (:account/external-name a)})
                                       parsed-accounts)
                   :errors (count account-errors)})

        {:success {:institutions 1
                   :accounts (count parsed-accounts)}
         :failed {:institutions 0
                  :accounts (count account-errors)}
         :errors account-errors}))

    (catch Exception e
      (log/error "Failed to sync Plaid Item accounts"
                 {:item-id item-id :error (.getMessage e)})
      {:success {:institutions 0 :accounts 0}
       :failed {:institutions 1 :accounts 0}
       :errors [{:type :sync-error
                 :item-id item-id
                 :message (.getMessage e)}]})))

(defn sync-all-items-accounts!
  "Sync accounts across ALL Plaid Items in parallel.

   Fetches all Plaid credentials and syncs each item's accounts.
   Uses pmap for parallel processing across items.

   deps: {:db-conn datalevin-connection
          :secrets secrets-map
          :plaid-config plaid-configuration-map}

   Returns: {:items [{:item-id string :institution-name string :result map} ...]
            :summary {:total-institutions int :total-accounts int :errors int}}"
  [{:keys [db-conn secrets plaid-config] :as deps}]
  (let [credentials (creds/get-all-plaid-credentials db-conn secrets)]
    (if (empty? credentials)
      {:items []
       :summary {:total-institutions 0 :total-accounts 0 :errors 0}
       :message "No Plaid Items found. Please link a bank account first."}

      (let [;; Sync each item in parallel
            sync-fn (fn [cred]
                      {:item-id (:item-id cred)
                       :institution-name (:institution-name cred)
                       :result (sync-item-accounts! deps cred)})
            results (pmap sync-fn credentials)

            ;; Aggregate results
            total-institutions (reduce + (map #(get-in % [:result :success :institutions] 0) results))
            total-accounts (reduce + (map #(get-in % [:result :success :accounts] 0) results))
            total-errors (reduce + (map #(count (get-in % [:result :errors] [])) results))]

        (log/info "Synced all Plaid Items"
                  {:items (count results)
                   :institutions total-institutions
                   :accounts total-accounts
                   :errors total-errors})

        {:items (vec results)
         :summary {:total-institutions total-institutions
                   :total-accounts total-accounts
                   :errors total-errors}}))))

(defn sync-accounts!
  "Sync Plaid accounts from all Items to database.

   This is the main entry point for account syncing. It syncs
   all linked Plaid Items in parallel.

   deps: {:db-conn datalevin-connection
          :secrets secrets-map
          :plaid-config plaid-configuration-map}

   Returns: {:success {:institutions int, :accounts int}
            :failed {:institutions int, :accounts int}
            :errors [{:type keyword, :message string} ...]}"
  [deps]
  (let [result (sync-all-items-accounts! deps)]
    ;; Convert to legacy format for backward compatibility
    {:success {:institutions (get-in result [:summary :total-institutions] 0)
               :accounts (get-in result [:summary :total-accounts] 0)}
     :failed {:institutions 0
              :accounts (get-in result [:summary :errors] 0)}
     :errors (mapcat #(get-in % [:result :errors] []) (:items result))}))

(declare sync-item-transactions-incremental!)

(defn- poll-for-historical-transactions!
  "Background poll for historical transaction data.

   Called when initial sync completes with :initial-update-complete.
   Polls /transactions/sync periodically until :historical-update-complete
   or max attempts reached.

   Runs asynchronously in a future - returns immediately.

   deps: {:db-conn datalevin-connection
          :plaid-config plaid-configuration-map}
   item-credential: {:item-id string
                     :access-token string
                     :institution-name string}"
  [{:keys [db-conn plaid-config] :as deps}
   {:keys [item-id access-token institution-name] :as item-credential}]
  (future
    (try
      (loop [attempt 1]
        (Thread/sleep historical-poll-interval-ms)

        (log/info "Polling for historical transactions"
                  {:item-id item-id
                   :institution institution-name
                   :attempt attempt
                   :max-attempts historical-poll-max-attempts})

        ;; Check if item still exists (user might have deleted it)
        (let [cursor (creds/get-sync-cursor db-conn item-id)]
          (if-not cursor
            (log/warn "Item no longer exists, stopping historical poll"
                      {:item-id item-id})

            ;; Perform incremental sync to get more historical data
            (let [days-requested (:days-requested plaid-config)
                  response (client/sync-transactions
                            plaid-config
                            access-token
                            cursor
                            {:count 500
                             :days-requested days-requested})
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
                         :historical-complete? historical-complete?
                         :accounts-in-response (count (distinct (map :account_id added)))
                         :account-ids (distinct (map :account_id added))
                         :transactions-per-account (frequencies (map :account_id added))})

              ;; Process any new transactions
              (when (or (seq added) (seq modified))
                (let [upsert-txns (concat added modified)
                      tx-results (safe-parse-transactions upsert-txns hardcoded-user-id)
                      parsed-txns (:success tx-results)]
                  (when (seq parsed-txns)
                    (db/insert! {:institutions #{}
                                 :accounts #{}
                                 :transactions parsed-txns}
                                db-conn)
                    (log/info "Persisted historical transactions"
                              {:item-id item-id
                               :count (count parsed-txns)}))))

              ;; Handle removed transactions
              (when (seq removed)
                (let [removed-tx-ids (map :transaction_id removed)
                      db-snapshot (d/db db-conn)
                      existing-eids (d/q '[:find [?e ...]
                                           :in $ [?tx-id ...]
                                           :where
                                           [?e :transaction/external-id ?tx-id]]
                                         db-snapshot
                                         removed-tx-ids)
                      retract-ops (mapv (fn [eid] [:db/retractEntity eid]) existing-eids)]
                  (when (seq retract-ops)
                    (d/transact! db-conn retract-ops))))

              ;; Update cursor
              (when (seq next-cursor)
                (creds/update-sync-cursor! db-conn item-id next-cursor))

              ;; Check completion status
              (cond
                ;; Historical data complete - mark as synced
                historical-complete?
                (do
                  (log/info "Historical transactions complete"
                            {:item-id item-id :institution institution-name})
                  (creds/update-sync-status! db-conn item-id :synced {})
                  (ws-state/update-sync-status! item-id :synced
                                                :institution-name institution-name
                                                :message "Historical transactions loaded"))

                ;; Max attempts reached - mark as partial
                (>= attempt historical-poll-max-attempts)
                (do
                  (log/warn "Max poll attempts reached, historical data may be incomplete"
                            {:item-id item-id :attempts attempt})
                  (creds/update-sync-status! db-conn item-id :synced
                                             {:message "Historical data may be incomplete"})
                  (ws-state/update-sync-status! item-id :synced
                                                :institution-name institution-name
                                                :message "Historical data may be incomplete"))

                ;; Still waiting - continue polling
                :else
                (recur (inc attempt)))))))

      (catch Exception e
        (log/error "Error polling for historical transactions"
                   {:item-id item-id :error (.getMessage e)})
        (creds/update-sync-status! db-conn item-id :failed)
        (ws-state/update-sync-status! item-id :failed
                                      :institution-name institution-name
                                      :error "Failed to fetch historical transactions")))))

(defn sync-item-transactions!
  "Sync transactions for a single Plaid Item using /transactions/sync.

   Uses cursor-based incremental sync per Plaid's recommendation:
   - Initial sync (no cursor): fetches history based on :days-requested from plaid-config
   - Incremental sync (has cursor): fetches only changes since last sync

   Handles pagination automatically when has_more=true.
   Filters out pending transactions.
   Uses parallel transformation for performance.
   Stores cursor after successful sync for future incremental syncs.
   Pushes real-time status updates via WebSocket.

   deps: {:db-conn datalevin-connection
          :plaid-config plaid-configuration-map (must include :days-requested)}
   item-credential: {:item-id string
                     :institution-name string
                     :access-token string}

   Returns: {:success {:added int :modified int :removed int :transactions int}
            :failed {:transactions int}
            :cursor string (the cursor stored for next sync)
            :errors [{:transaction-id string, :message string} ...]}"
  [{:keys [db-conn plaid-config]} {:keys [item-id access-token institution-name]}]
  (try
    ;; 1. Get current cursor from database (nil = initial sync)
    (let [current-cursor (creds/get-sync-cursor db-conn item-id)
          initial-sync? (nil? current-cursor)
          days-requested (:days-requested plaid-config)]
      (when (and initial-sync? (nil? days-requested))
        (throw (ex-info "days-requested is required in plaid-config for initial sync"
                        {:hint "Add :days-requested to plaid link-config"})))

      (log/info "Starting Plaid transaction sync"
                {:item-id item-id
                 :institution institution-name
                 :mode (if initial-sync? "INITIAL" "INCREMENTAL")
                 :cursor (when current-cursor (subs current-cursor 0 (min 20 (count current-cursor))))})

      ;; Push initial syncing status via WebSocket
      (ws-state/update-sync-status! item-id :syncing
                                    :institution-name institution-name
                                    :transaction-count 0
                                    :progress {:added 0 :modified 0 :removed 0})

      ;; 2. Pagination loop - collect all pages before processing
      (loop [cursor current-cursor
             all-added []
             all-modified []
             all-removed []]

        (let [;; Fetch one page from Plaid
              response (client/sync-transactions
                        plaid-config
                        access-token
                        cursor
                        {:count 500
                         :days-requested days-requested})

              ;; Log raw response for debugging
              update-status (:transactions_update_status response)
              ;; Track which accounts are represented in the response
              added-account-ids (distinct (map :account_id (:added response)))
              ;; Group transactions by account to see distribution
              txns-per-account (frequencies (map :account_id (:added response)))
              _ (log/info "Plaid sync response - DETAILED"
                          {:item-id item-id
                           :accounts-in-response (count added-account-ids)
                           :account-ids added-account-ids
                           :transactions-per-account txns-per-account
                           :total-added (count (:added response))
                           :added-count (count (:added response))
                           :modified-count (count (:modified response))
                           :removed-count (count (:removed response))
                           :has-more (:has_more response)
                           :transactions-update-status update-status
                           :next-cursor-preview (when-let [c (:next_cursor response)]
                                                  (if (> (count c) 20)
                                                    (str (subs c 0 20) "...")
                                                    c))})

              ;; Accumulate results from this page
              added (into all-added (:added response))
              modified (into all-modified (:modified response))
              removed (into all-removed (:removed response))
              next-cursor (:next_cursor response)
              has-more (:has_more response)]

           (if has-more
             ;; More pages available - push progress update and continue loop
             (do
               (log/debug "Fetching next page of transactions"
                          {:item-id item-id
                           :added-so-far (count added)
                           :modified-so-far (count modified)
                           :removed-so-far (count removed)})
               ;; Push progress update via WebSocket
               (ws-state/update-sync-status! item-id :syncing
                                             :institution-name institution-name
                                             :transaction-count (count added)
                                             :progress {:added (count added)
                                                        :modified (count modified)
                                                        :removed (count removed)})
               (recur next-cursor added modified removed))

             ;; No more pages - process all accumulated transactions
             (let [;; Combine added + modified for upsert (both get parsed and inserted)
                   upsert-txns (concat added modified)

                   ;; Parse transactions (parallel, filters pending)
                   tx-results (safe-parse-transactions upsert-txns hardcoded-user-id)
                   parsed-txns (:success tx-results)
                   tx-errors (:errors tx-results)

                   ;; Extract transaction IDs for removal
                   removed-tx-ids (map :transaction_id removed)

                   ;; Build batch of retraction operations using a single db snapshot
                   retract-ops (when (seq removed-tx-ids)
                                 (let [db (d/db db-conn)  ;; Single snapshot
                                       existing-eids (d/q '[:find [?e ...]
                                                            :in $ [?tx-id ...]
                                                            :where
                                                            [?e :transaction/external-id ?tx-id]]
                                                          db
                                                          removed-tx-ids)]
                                   (mapv (fn [eid] [:db/retractEntity eid]) existing-eids)))]

               ;; 3. Persist transactions to database (upserts via unique constraint)
               (db/insert! {:institutions #{}
                            :accounts #{}
                            :transactions parsed-txns}
                           db-conn)

               ;; 4. Handle removed transactions in a single batch transaction
               (when (seq retract-ops)
                 (log/info "Removing transactions" {:item-id item-id :count (count retract-ops)})
                 (d/transact! db-conn (vec retract-ops)))

               ;; 5. Store cursor and update sync status based on Plaid's update status
               ;;
               ;; transactions_update_status tells us if Plaid has finished fetching:
               ;; - :not-ready → Plaid is still fetching, keep polling
               ;; - :initial-update-complete → ~30 days ready, historical still being fetched
               ;; - :historical-update-complete → All requested historical data is ready
               ;;
               ;; We store cursor when we have data, and set status based on what Plaid reports:
               ;; - :historical-update-complete → :synced (all data ready)
               ;; - :initial-update-complete → :syncing-historical (need to poll for more)
               ;; - anything else with data → :syncing-historical
               ;; - no data → :pending
               (let [historical-complete? (= :historical-update-complete update-status)
                     initial-complete? (= :initial-update-complete update-status)
                     total-changes (+ (count added) (count modified) (count removed))
                     has-data? (pos? total-changes)
                     final-status (cond
                                    historical-complete? :synced
                                    (or initial-complete? has-data?) :syncing-historical
                                    :else :pending)]

                 ;; Store cursor if we have one (for incremental sync later)
                 (when (and (seq next-cursor) (or historical-complete? initial-complete? has-data?))
                   (let [cursor-updated? (creds/update-sync-cursor! db-conn item-id next-cursor)]
                     (when-not cursor-updated?
                       (log/warn "Could not update sync cursor - item credential may have been deleted"
                                 {:item-id item-id}))))

                 ;; Update DB status
                 (creds/update-sync-status! db-conn item-id final-status
                                            {:transaction-count (count parsed-txns)})

                 ;; Log status
                 (log/info "Sync status update"
                           {:item-id item-id
                            :institution institution-name
                            :update-status update-status
                            :final-status final-status
                            :historical-complete? historical-complete?
                            :transaction-count (count parsed-txns)})

                 ;; Push status via WebSocket
                 (ws-state/update-sync-status! item-id final-status
                                               :institution-name institution-name
                                               :transaction-count (count parsed-txns)
                                               :progress {:added (count added)
                                                          :modified (count modified)
                                                          :removed (count removed)})

                 ;; If historical data still pending, start background polling
                 (when (and (= final-status :syncing-historical) initial-sync?)
                   (log/info "Starting background poll for historical transactions"
                             {:item-id item-id :institution institution-name})
                   (poll-for-historical-transactions!
                    {:db-conn db-conn :plaid-config plaid-config}
                    {:item-id item-id
                     :access-token access-token
                     :institution-name institution-name})))

               ;; 6. Log and return results
               (log/info "Completed Plaid transaction sync"
                         {:item-id item-id
                          :institution institution-name
                          :mode (if initial-sync? "INITIAL" "INCREMENTAL")
                          :added (count added)
                          :modified (count modified)
                          :removed (count removed)
                          :persisted (count parsed-txns)
                          :errors (count tx-errors)})

               {:success {:added (count added)
                          :modified (count modified)
                          :removed (count removed)
                          :transactions (count parsed-txns)}
                :failed {:transactions (count tx-errors)}
                :cursor next-cursor
                :errors tx-errors})))))

     (catch Exception e
       (log/error "Failed to sync Plaid Item transactions"
                  {:item-id item-id :error (.getMessage e)})
       ;; Update sync status to failed (both DB and WebSocket)
       (creds/update-sync-status! db-conn item-id :failed)
       (ws-state/update-sync-status! item-id :failed
                                     :institution-name institution-name
                                     :error (.getMessage e))
       {:success {:added 0 :modified 0 :removed 0 :transactions 0}
        :failed {:transactions 0}
        :errors [{:type :sync-error
                  :item-id item-id
                  :message (.getMessage e)}]})))

(defn get-item-sync-status
  "Get comprehensive sync status for a Plaid Item.

   Checks in-memory WebSocket state first (fast path for active syncs),
   falls back to database for persistent state.
   Used by frontend REST fallback and for initial status checks.

   deps: {:db-conn datalevin-connection}
   item-id: Plaid item_id string

   Returns:
   {:item-id string
    :institution-name string
    :sync-status keyword (:pending :syncing :synced :failed)
    :has-cursor boolean (true if cursor stored, meaning initial sync completed with data)
    :transaction-count int
    :last-sync-at instant or nil
    :ready-for-display boolean (true if synced with transactions)}"
  [{:keys [db-conn]} item-id]
  ;; Check in-memory state first (updated in real-time during sync)
  (if-let [ws-status (ws-state/get-sync-status item-id)]
    ;; Use WebSocket state (active sync in progress or recent)
    {:item-id item-id
     :institution-name (:institution-name ws-status)
     :sync-status (:status ws-status)
     :has-cursor false ;; Don't hit DB for this during active sync
     :transaction-count (or (:transaction-count ws-status) 0)
     :last-sync-at (:updated-at ws-status)
     :ready-for-display (and (= :synced (:status ws-status))
                             (pos? (or (:transaction-count ws-status) 0)))
     :progress (:progress ws-status)}
    ;; Fall back to database state
    (let [cursor (creds/get-sync-cursor db-conn item-id)
          status (creds/get-sync-status db-conn item-id)]
      (if status
        {:item-id item-id
         :institution-name (:institution-name status)
         :sync-status (:sync-status status)
         :has-cursor (some? cursor)
         :transaction-count (:transaction-count status)
         :last-sync-at (:last-sync-at status)
         :ready-for-display (and (= :synced (:sync-status status))
                                 (pos? (:transaction-count status)))}
        ;; Item not found
        {:item-id item-id
         :sync-status :not-found
         :ready-for-display false}))))

(defn sync-all-items-transactions!
  "Sync transactions across ALL Plaid Items in parallel.

   Fetches all Plaid credentials and syncs each item's transactions.
   Uses pmap for parallel processing across items.

   deps: {:db-conn datalevin-connection
          :secrets secrets-map
          :plaid-config plaid-configuration-map (must include :days-requested)}

   Returns: {:items [{:item-id string :institution-name string :result map} ...]
            :summary {:total-transactions int :errors int}}"
  [{:keys [db-conn secrets plaid-config] :as deps}]
  (let [credentials (creds/get-all-plaid-credentials db-conn secrets)]
    (if (empty? credentials)
      {:items []
       :summary {:total-transactions 0 :errors 0}
       :message "No Plaid Items found. Please link a bank account first."}

      (let [;; Sync each item in parallel
            sync-fn (fn [cred]
                      {:item-id (:item-id cred)
                       :institution-name (:institution-name cred)
                       :result (sync-item-transactions! deps cred)})
             results (pmap sync-fn credentials)

             ;; Aggregate results
             total-transactions (reduce + (map #(get-in % [:result :success :transactions] 0) results))
             total-errors (reduce + (map #(count (get-in % [:result :errors] [])) results))]

         (log/info "Synced all Plaid Items transactions"
                   {:items (count results)
                    :transactions total-transactions
                    :errors total-errors})

         {:items (vec results)
          :summary {:total-transactions total-transactions
                    :errors total-errors}}))))

(defn sync-transactions!
  "Sync Plaid transactions from all Items to database.

   This is the main entry point for transaction syncing. It syncs
   all linked Plaid Items in parallel.

   deps: {:db-conn datalevin-connection
          :secrets secrets-map
          :plaid-config plaid-configuration-map (must include :days-requested)}

   Returns: {:success {:transactions int}
            :failed {:transactions int}
            :errors [{:transaction-id string, :message string} ...]}"
  [deps]
  (let [result (sync-all-items-transactions! deps)]
    ;; Convert to legacy format for backward compatibility
    {:success {:transactions (get-in result [:summary :total-transactions] 0)}
     :failed {:transactions (get-in result [:summary :errors] 0)}
     :errors (mapcat #(get-in % [:result :errors] []) (:items result))}))

(defn sync-all!
  "Sync both accounts and transactions.
   Accounts are synced first (required for transaction references).

   deps: {:db-conn datalevin-connection
          :secrets secrets-map
          :plaid-config plaid-configuration-map (must include :days-requested)}

   Returns: {:accounts {...result from sync-accounts!...}
            :transactions {...result from sync-transactions!...}}"
  [deps]
  (let [accounts-result (sync-accounts! deps)
        transactions-result (sync-transactions! deps)]
    {:accounts accounts-result
     :transactions transactions-result}))

(defn- parse-month-to-date-range
  "Parse YYYY-MM string into start-date and end-date strings for Plaid API.
   Returns {:start-date 'YYYY-MM-DD', :end-date 'YYYY-MM-DD'}"
  [month-str]
  (let [{:keys [year month]} (utils/parse-month-string month-str)
        year-month (YearMonth/of year month)
        start-date (.format (.atDay year-month 1) date-formatter)
        end-date (.format (.atDay (.plusMonths year-month 1) 1) date-formatter)]
    {:start-date start-date
     :end-date end-date}))

(defn sync-item-transactions-for-range!
  "Sync transactions for a single Plaid Item using /transactions/get (date-range based).

   Unlike sync-item-transactions! which uses cursor-based /transactions/sync,
   this function uses /transactions/get which supports specific date ranges.
   Use this when you need to fetch/refresh transactions for a specific time period.

   Note: /transactions/get does not handle pagination automatically - for large
   date ranges, consider using sync-item-transactions! which handles all pagination.

   deps: {:db-conn datalevin-connection
          :plaid-config plaid-configuration-map}
   item-credential: {:item-id string
                     :institution-name string
                     :access-token string}
   start-date: string 'YYYY-MM-DD'
   end-date: string 'YYYY-MM-DD'

   Returns: {:success {:transactions int}
            :failed {:transactions int}
            :errors [{:transaction-id string, :message string} ...]}"
  [{:keys [db-conn plaid-config]} {:keys [item-id access-token institution-name]} start-date end-date]
  (try
    (log/info "Fetching transactions for date range"
              {:item-id item-id
               :institution institution-name
               :start-date start-date
               :end-date end-date})

    ;; 1. Fetch transactions from Plaid using date-range endpoint
    (let [transactions (client/fetch-transactions plaid-config access-token start-date end-date)

          ;; 2. Parse transactions (parallel, filters pending)
          tx-results (safe-parse-transactions transactions hardcoded-user-id)
          parsed-txns (:success tx-results)
          tx-errors (:errors tx-results)]

      ;; 3. Persist transactions to database (upserts via unique constraint)
      (db/insert! {:institutions #{}
                   :accounts #{}
                   :transactions parsed-txns}
                  db-conn)

      ;; 4. Log and return results
      (log/info "Completed date-range transaction sync"
                {:item-id item-id
                 :institution institution-name
                 :start-date start-date
                 :end-date end-date
                 :fetched (count transactions)
                 :persisted (count parsed-txns)
                 :errors (count tx-errors)})

      {:success {:transactions (count parsed-txns)}
       :failed {:transactions (count tx-errors)}
       :errors tx-errors})

    (catch Exception e
      (log/error "Failed to sync transactions for date range"
                 {:item-id item-id
                  :start-date start-date
                  :end-date end-date
                  :error (.getMessage e)})
      {:success {:transactions 0}
       :failed {:transactions 0}
       :errors [{:type :sync-error
                 :item-id item-id
                 :message (.getMessage e)}]})))

(defn sync-all-items-transactions-for-range!
  "Sync transactions across ALL Plaid Items for a specific date range.

   Uses /transactions/get (date-range based) instead of /transactions/sync.
   Use this for fetching transactions for a specific month or time period.

   deps: {:db-conn datalevin-connection
          :secrets secrets-map
          :plaid-config plaid-configuration-map}
   start-date: string 'YYYY-MM-DD'
   end-date: string 'YYYY-MM-DD'

   Returns: {:items [{:item-id string :institution-name string :result map} ...]
            :summary {:total-transactions int :errors int}}"
  [{:keys [db-conn secrets plaid-config] :as deps} start-date end-date]
  (let [credentials (creds/get-all-plaid-credentials db-conn secrets)]
    (if (empty? credentials)
      {:items []
       :summary {:total-transactions 0 :errors 0}
       :message "No Plaid Items found. Please link a bank account first."}

      (let [;; Sync each item in parallel
            sync-fn (fn [cred]
                      {:item-id (:item-id cred)
                       :institution-name (:institution-name cred)
                       :result (sync-item-transactions-for-range! deps cred start-date end-date)})
            results (pmap sync-fn credentials)

            ;; Aggregate results
            total-transactions (reduce + (map #(get-in % [:result :success :transactions] 0) results))
            total-errors (reduce + (map #(count (get-in % [:result :errors] [])) results))]

        (log/info "Synced all Plaid Items transactions for date range"
                  {:items (count results)
                   :start-date start-date
                   :end-date end-date
                   :transactions total-transactions
                   :errors total-errors})

        {:items (vec results)
         :summary {:total-transactions total-transactions
                   :errors total-errors}}))))

(defn sync-month-transactions!
  "Sync Plaid transactions for a specific month.

   Uses /transactions/get (date-range based) to fetch transactions for the
   exact month requested. This is the correct API for month-specific requests
   since /transactions/sync is cursor-based and doesn't support date filtering.

   deps: {:db-conn datalevin-connection
          :secrets secrets-map
          :plaid-config plaid-configuration-map}
   month: String in YYYY-MM format (e.g., '2025-01')

   Returns: {:success {:transactions int}
            :failed {:transactions int}
            :errors [{:transaction-id string, :message string} ...]}"
  [deps month-str]
  (let [{:keys [start-date end-date]} (parse-month-to-date-range month-str)]
    (log/info "Syncing transactions for month" {:month month-str
                                                 :start-date start-date
                                                 :end-date end-date})
    (let [result (sync-all-items-transactions-for-range! deps start-date end-date)]
      ;; Convert to legacy format for backward compatibility
      {:success {:transactions (get-in result [:summary :total-transactions] 0)}
       :failed {:transactions (get-in result [:summary :errors] 0)}
       :errors (mapcat #(get-in % [:result :errors] []) (:items result))})))
