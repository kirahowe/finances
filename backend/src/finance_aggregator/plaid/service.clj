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
   [finance-aggregator.db :as db]
   [finance-aggregator.db.credentials :as creds]
   [finance-aggregator.lib.log :as log]
   [finance-aggregator.plaid.client :as client]
   [finance-aggregator.plaid.provider :as plaid-provider]
   [finance-aggregator.provider :as provider]
   [finance-aggregator.provider.sync :as sync]
   [finance-aggregator.utils :as utils]
   [finance-aggregator.ws.state :as ws-state])
  (:import
   [java.time YearMonth]
   [java.time.format DateTimeFormatter]))

;;; Constants

(def ^:private hardcoded-user-id "test-user")
(def ^:private date-formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd"))

;;; Public API

(defn- item-deps
  "Augment the base deps with the per-item context the `:plaid` provider
   methods expect (item-id, access token, institution name, ws status key)."
  [deps {:keys [item-id access-token institution-name]}]
  (assoc deps
         :status-key item-id
         :item-id item-id
         :access-token access-token
         :institution-name institution-name))

(defn sync-item-accounts!
  "Sync accounts for a single Plaid Item to database.

   Thin adapter over the `:plaid` provider seam: fetches the canonical
   institution/account maps via `provider/fetch-accounts` and persists them.

   deps: {:db-conn datalevin-connection
          :plaid-config plaid-configuration-map}
   item-credential: {:item-id string
                     :institution-name string
                     :access-token string}

   Returns: {:success {:institutions int, :accounts int}
            :failed {:institutions int, :accounts int}
            :errors [{:type keyword, :message string} ...]}"
  [{:keys [db-conn] :as deps} {:keys [item-id institution-name] :as item-credential}]
  (try
    (let [{:keys [institutions accounts]}
          (provider/fetch-accounts :plaid (item-deps deps item-credential))]
      (db/insert! {:institutions (set institutions)
                   :accounts (set accounts)
                   :transactions []}
                  db-conn)
      (log/info "Synced Plaid Item accounts"
                {:item-id item-id
                 :institution institution-name
                 :accounts-count (count accounts)})
      {:success {:institutions (count institutions)
                 :accounts (count accounts)}
       :failed {:institutions 0 :accounts 0}
       :errors []})

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

(defn sync-item-transactions!
  "Sync transactions for a single Plaid Item via cursor-based /transactions/sync.

   Thin adapter over the `:plaid` provider seam. `provider/fetch-transactions`
   pages Plaid's cursor, returns canonical (sign-normalized) transactions, the
   external-ids to retract, the terminal status, and an :on-complete finalizer
   (cursor store + credential status + historical-poll trigger). This fn
   persists the batch, retracts removed transactions, runs the finalizer, and
   publishes the terminal ws status - then reports the legacy result shape.

   deps: {:db-conn datalevin-connection
          :plaid-config plaid-configuration-map (must include :days-requested)}
   item-credential: {:item-id string
                     :institution-name string
                     :access-token string}

   Returns: {:success {:added int :modified int :removed int :transactions int}
            :failed {:transactions int}
            :cursor string (the cursor stored for next sync)
            :errors [{:transaction-id string, :message string} ...]}"
  [{:keys [db-conn] :as deps} {:keys [item-id institution-name] :as item-credential}]
  (try
    (let [{:keys [transactions removed status status-opts on-complete cursor errors]}
          (provider/fetch-transactions :plaid (item-deps deps item-credential))
          progress (:progress status-opts)]
      ;; Persist this batch (upserts via unique constraint), then retract removed.
      (db/insert! {:institutions #{} :accounts #{} :transactions transactions} db-conn)
      (sync/retract-removed! db-conn removed)
      ;; Finalize: store cursor, set credential status, maybe trigger historical poll.
      (when on-complete (on-complete))
      ;; Publish the terminal ws status for this item.
      (ws-state/update-sync-status! item-id status
                                    :institution-name institution-name
                                    :transaction-count (:transaction-count status-opts)
                                    :progress progress)
      {:success {:added (:added progress)
                 :modified (:modified progress)
                 :removed (:removed progress)
                 :transactions (count transactions)}
       :failed {:transactions (count errors)}
       :cursor cursor
       :errors errors})

    (catch Exception e
      (log/error "Failed to sync Plaid Item transactions"
                 {:item-id item-id :error (.getMessage e)})
      (creds/update-sync-status! db-conn item-id :failed)
      (ws-state/update-sync-status! item-id :failed
                                    :institution-name institution-name
                                    :error (.getMessage e))
      {:success {:added 0 :modified 0 :removed 0 :transactions 0}
       :failed {:transactions 0}
       :errors [{:type :sync-error
                 :item-id item-id
                 :message (.getMessage e)}]})))

(defn sync-item!
  "Full per-item Plaid sync (accounts then transactions) through the generic
   provider orchestrator (`provider.sync/sync-provider!`).

   This is the single runtime entry point for syncing one linked Plaid Item.
   The orchestrator persists accounts before transactions, pages the cursor,
   retracts removed transactions, runs the `:plaid` :on-complete finalizer
   (cursor store + credential status + historical-poll trigger), and publishes
   ws status keyed by item-id. Returns the terminal sync status keyword
   (:synced / :syncing-historical / :pending). Marks the credential :failed and
   re-throws on error.

   deps: {:db-conn datalevin-connection
          :plaid-config plaid-configuration-map (must include :days-requested)}
   item-credential: {:item-id string
                     :institution-name string
                     :access-token string}"
  [{:keys [db-conn] :as deps} {:keys [item-id] :as item-credential}]
  (try
    (sync/sync-provider! (item-deps deps item-credential) :plaid)
    (catch Exception e
      (log/error "Failed to sync Plaid Item"
                 {:item-id item-id :error (.getMessage e)})
      (creds/update-sync-status! db-conn item-id :failed)
      (throw e))))

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
          tx-results (plaid-provider/safe-parse-transactions transactions hardcoded-user-id)
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
