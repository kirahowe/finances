(ns finance-aggregator.plaid.service
  "Service orchestration for Plaid account and transaction syncing.

   Handles the complete flow:
   - Fetch data from Plaid API (parallel where possible)
   - Transform to database schema
   - Persist to database
   - Error handling with partial success

   All functions are pure at the transformation level, with side effects
   isolated to API calls and database operations."
  (:require
   [datalevin.core :as d]
   [finance-aggregator.db :as db]
   [finance-aggregator.db.credentials :as creds]
   [finance-aggregator.lib.log :as log]
   [finance-aggregator.plaid.client :as client]
   [finance-aggregator.plaid.data :as data]
   [finance-aggregator.utils :as utils])
  (:import
   [java.time LocalDate YearMonth]
   [java.time.format DateTimeFormatter]))

;;; Constants

(def ^:private hardcoded-user-id "test-user")
(def ^:private default-sync-months 6)
(def ^:private date-formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd"))

;;; Helper Functions

(defn- calculate-date-range
  "Calculate start and end dates for transaction sync.

   months: number of months to go back from end-date
   end-date: YYYY-MM-DD string, or nil for today

   Returns: {:start-date string, :end-date string}"
  [months end-date]
  (let [end (if end-date
              (LocalDate/parse end-date date-formatter)
              (LocalDate/now))
        start (.minusMonths end months)]
    {:start-date (.format start date-formatter)
     :end-date (.format end date-formatter)}))

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
  (let [parse-fn (fn [txn]
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
        (log/info "Synced Plaid Item accounts"
                  {:item-id item-id
                   :institution institution-name
                   :accounts (count parsed-accounts)
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

(defn sync-item-transactions!
  "Sync transactions for a single Plaid Item to database.

   Fetches transactions for the specified date range (default: 6 months).
   Filters out pending transactions.
   Uses parallel transformation for performance.

   deps: {:db-conn datalevin-connection
          :plaid-config plaid-configuration-map}
   item-credential: {:item-id string
                     :institution-name string
                     :access-token string}
   opts: {:months int (default 6)
          :end-date string YYYY-MM-DD (default today)}

   Returns: {:success {:transactions int}
            :failed {:transactions int}
            :errors [{:transaction-id string, :message string} ...]}"
  ([deps item-credential]
   (sync-item-transactions! deps item-credential {}))
  ([{:keys [db-conn plaid-config]} {:keys [item-id access-token institution-name]} opts]
   (try
     ;; 1. Calculate date range (support explicit dates or months-based calculation)
     (let [[start-date end-date-final]
           (if (and (:start-date opts) (:end-date opts))
             ;; Use explicit dates when provided
             [(:start-date opts) (:end-date opts)]
             ;; Otherwise calculate from months
             (let [months (or (:months opts) default-sync-months)
                   date-range (calculate-date-range months (:end-date opts))]
               [(:start-date date-range) (:end-date date-range)]))]

       ;; 2. Fetch transactions
       (let [transactions (client/fetch-transactions plaid-config
                                                     access-token
                                                     start-date
                                                     end-date-final)

             ;; 3. Parse transactions (parallel, filters pending)
             tx-results (safe-parse-transactions transactions hardcoded-user-id)
             parsed-txns (:success tx-results)
             tx-errors (:errors tx-results)]

         ;; 4. Persist to database
         (db/insert! {:institutions #{}
                      :accounts #{}
                      :transactions parsed-txns}
                     db-conn)

         ;; 5. Log and return results
         (log/info "Synced Plaid Item transactions"
                   {:item-id item-id
                    :institution institution-name
                    :start-date start-date
                    :end-date end-date-final
                    :transactions (count parsed-txns)
                    :errors (count tx-errors)})

         {:success {:transactions (count parsed-txns)}
          :failed {:transactions (count tx-errors)}
          :errors tx-errors}))

     (catch Exception e
       (log/error "Failed to sync Plaid Item transactions"
                  {:item-id item-id :error (.getMessage e)})
       {:success {:transactions 0}
        :failed {:transactions 0}
        :errors [{:type :sync-error
                  :item-id item-id
                  :message (.getMessage e)}]}))))

(defn sync-all-items-transactions!
  "Sync transactions across ALL Plaid Items in parallel.

   Fetches all Plaid credentials and syncs each item's transactions.
   Uses pmap for parallel processing across items.

   deps: {:db-conn datalevin-connection
          :secrets secrets-map
          :plaid-config plaid-configuration-map}
   opts: {:months int (default 6)
          :end-date string YYYY-MM-DD (default today)}

   Returns: {:items [{:item-id string :institution-name string :result map} ...]
            :summary {:total-transactions int :errors int}}"
  ([deps]
   (sync-all-items-transactions! deps {}))
  ([{:keys [db-conn secrets plaid-config] :as deps} opts]
   (let [credentials (creds/get-all-plaid-credentials db-conn secrets)]
     (if (empty? credentials)
       {:items []
        :summary {:total-transactions 0 :errors 0}
        :message "No Plaid Items found. Please link a bank account first."}

       (let [;; Sync each item in parallel
             sync-fn (fn [cred]
                       {:item-id (:item-id cred)
                        :institution-name (:institution-name cred)
                        :result (sync-item-transactions! deps cred opts)})
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
                    :errors total-errors}})))))

(defn sync-transactions!
  "Sync Plaid transactions from all Items to database.

   This is the main entry point for transaction syncing. It syncs
   all linked Plaid Items in parallel.

   deps: {:db-conn datalevin-connection
          :secrets secrets-map
          :plaid-config plaid-configuration-map}
   opts: {:months int (default 6)
          :end-date string YYYY-MM-DD (default today)}

   Returns: {:success {:transactions int}
            :failed {:transactions int}
            :errors [{:transaction-id string, :message string} ...]}"
  ([deps]
   (sync-transactions! deps {}))
  ([deps opts]
   (let [result (sync-all-items-transactions! deps opts)]
     ;; Convert to legacy format for backward compatibility
     {:success {:transactions (get-in result [:summary :total-transactions] 0)}
      :failed {:transactions (get-in result [:summary :errors] 0)}
      :errors (mapcat #(get-in % [:result :errors] []) (:items result))})))

(defn sync-all!
  "Sync both accounts and transactions.
   Accounts are synced first (required for transaction references).

   deps: {:db-conn datalevin-connection
          :secrets secrets-map
          :plaid-config plaid-configuration-map}
   opts: {:months int (default 6) - passed to sync-transactions!
          :end-date string - passed to sync-transactions!}

   Returns: {:accounts {...result from sync-accounts!...}
            :transactions {...result from sync-transactions!...}}"
  ([deps]
   (sync-all! deps {}))
  ([deps opts]
   (let [accounts-result (sync-accounts! deps)
         transactions-result (sync-transactions! deps opts)]
     {:accounts accounts-result
      :transactions transactions-result})))

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

(defn sync-month-transactions!
  "Sync Plaid transactions for a specific month.

   This is useful for refreshing transactions for a specific month being viewed,
   rather than syncing the full 6-month range.

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
    (sync-transactions! deps {:start-date start-date
                              :end-date end-date})))
