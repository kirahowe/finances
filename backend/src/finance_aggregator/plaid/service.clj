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
   [finance-aggregator.plaid.data :as data])
  (:import
   [java.time LocalDate]
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

(defn sync-accounts!
  "Sync Plaid accounts and institution to database.

   Fetches:
   - Item metadata (to get institution_id)
   - Institution details (name, URL, etc.)
   - All accounts for the access token

   Uses parallel fetching for item and institution data where possible.

   deps: {:db-conn datalevin-connection
          :secrets secrets-map
          :plaid-config plaid-configuration-map}

   Returns: {:success {:institutions int, :accounts int}
            :failed {:institutions int, :accounts int}
            :errors [{:type keyword, :message string} ...]}"
  [{:keys [db-conn secrets plaid-config]}]
  (try
    ;; 1. Get access token
    (let [access-token (creds/get-credential db-conn secrets :plaid)]
      (when-not access-token
        (throw (ex-info "No Plaid credential found"
                        {:type :no-credential
                         :hint "Run Plaid OAuth flow first"})))

      ;; 2. Fetch item and accounts in parallel
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

        ;; 3. Fetch institution details
        (let [institution (client/fetch-institution plaid-config institution-id)

              ;; 4. Parse data (parallel transformation)
              parsed-institution (data/parse-institution institution)
              account-results (safe-parse-accounts accounts institution-id hardcoded-user-id)
              parsed-accounts (:success account-results)
              account-errors (:errors account-results)]

          ;; 5. Persist to database
          (db/insert! {:institutions #{parsed-institution}
                       :accounts (set parsed-accounts)
                       :transactions []}
                      db-conn)

          ;; 6. Log and return results
          (log/info "Synced Plaid accounts"
                    {:institution institution-id
                     :accounts (count parsed-accounts)
                     :errors (count account-errors)})

          {:success {:institutions 1
                     :accounts (count parsed-accounts)}
           :failed {:institutions 0
                    :accounts (count account-errors)}
           :errors account-errors})))

    (catch Exception e
      (log/error "Failed to sync Plaid accounts" {:error (.getMessage e)})
      {:success {:institutions 0 :accounts 0}
       :failed {:institutions 1 :accounts 0}
       :errors [{:type :sync-error
                :message (.getMessage e)}]})))

(defn sync-transactions!
  "Sync Plaid transactions to database.

   Fetches transactions for the specified date range (default: 6 months).
   Filters out pending transactions.
   Uses parallel transformation for performance.

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
  ([{:keys [db-conn secrets plaid-config]} opts]
   (try
     ;; 1. Get access token
     (let [access-token (creds/get-credential db-conn secrets :plaid)]
       (when-not access-token
         (throw (ex-info "No Plaid credential found"
                         {:type :no-credential
                          :hint "Run Plaid OAuth flow first"})))

       ;; 2. Calculate date range
       (let [months (or (:months opts) default-sync-months)
             end-date (:end-date opts)
             date-range (calculate-date-range months end-date)
             start-date (:start-date date-range)
             end-date-final (:end-date date-range)]

         ;; 3. Fetch transactions
         (let [transactions (client/fetch-transactions plaid-config
                                                       access-token
                                                       start-date
                                                       end-date-final)

               ;; 4. Parse transactions (parallel, filters pending)
               tx-results (safe-parse-transactions transactions hardcoded-user-id)
               parsed-txns (:success tx-results)
               tx-errors (:errors tx-results)]

           ;; 5. Persist to database
           (db/insert! {:institutions #{}
                        :accounts #{}
                        :transactions parsed-txns}
                       db-conn)

           ;; 6. Log and return results
           (log/info "Synced Plaid transactions"
                     {:start-date start-date
                      :end-date end-date-final
                      :transactions (count parsed-txns)
                      :errors (count tx-errors)})

           {:success {:transactions (count parsed-txns)}
            :failed {:transactions (count tx-errors)}
            :errors tx-errors})))

     (catch Exception e
       (log/error "Failed to sync Plaid transactions" {:error (.getMessage e)})
       {:success {:transactions 0}
        :failed {:transactions 0}
        :errors [{:type :sync-error
                 :message (.getMessage e)}]}))))

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
