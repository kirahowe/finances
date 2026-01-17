(ns finance-aggregator.manual.service
  "Service orchestration for manual account CRUD operations.

   Handles:
   - Account creation with automatic institution management
   - Account deletion with cascade transaction removal
   - Account settings updates"
  (:require
   [datalevin.core :as d]
   [finance-aggregator.lib.log :as log]
   [finance-aggregator.manual.data :as data]))

;;; Constants

(def ^:private hardcoded-user-id "test-user")

;;; Helper Functions

(defn- get-or-create-institution!
  "Get existing institution or create if it doesn't exist.

   db-conn: Datalevin connection
   institution-name: Human-readable name like 'TD Bank' or 'Cash'

   Returns: institution entity map with db/id"
  [db-conn institution-name]
  (let [institution-entity (data/create-manual-institution institution-name)
        institution-id (:institution/id institution-entity)
        db (d/db db-conn)
        existing (d/pull db '[:db/id] [:institution/id institution-id])]
    (if (:db/id existing)
      existing
      (do
        (d/transact! db-conn [institution-entity])
        (d/pull (d/db db-conn) '[:db/id] [:institution/id institution-id])))))

(defn- get-transactions-for-account
  "Query all transactions for a given account.

   db-conn: Datalevin connection
   account-db-id: db/id of the account

   Returns: vector of transaction db/ids"
  [db-conn account-db-id]
  (let [db (d/db db-conn)
        query '[:find [?tx ...]
                :in $ ?acct
                :where
                [?tx :transaction/account ?acct]]
        tx-ids (d/q query db account-db-id)]
    tx-ids))

;;; Public API

(defn create-account!
  "Create a manual account with automatic institution creation.

   db-conn: Datalevin connection
   account-data: {:name string
                  :type string (optional)
                  :currency string (optional, defaults to 'USD')
                  :institution-name string}

   Returns: {:success boolean
            :data account-entity-map (with db/id)
            :error string (if failure)}"
  [db-conn account-data]
  (try
    (let [user-id hardcoded-user-id
          ;; Ensure institution exists
          _ (get-or-create-institution! db-conn (:institution-name account-data))
          ;; Transform account data
          account-entity (data/parse-manual-account account-data user-id)
          ;; Insert account
          _ (d/transact! db-conn [account-entity])
          ;; Retrieve with db/id and pull institution reference
          db (d/db db-conn)
          created-account (d/pull db '[* {:account/institution [:db/id :institution/name]}]
                                  [:account/external-id (:account/external-id account-entity)])]
      (log/info "Created manual account"
                {:account-id (:account/external-id account-entity)
                 :name (:name account-data)})
      {:success true
       :data created-account})
    (catch Exception e
      (log/error "Failed to create manual account"
                 {:account-data account-data
                  :error (.getMessage e)})
      {:success false
       :error (.getMessage e)})))

(defn delete-account!
  "Delete a manual account and all its transactions (cascade).

   db-conn: Datalevin connection
   account-external-id: Account's external-id string

   Returns: {:success boolean
            :deleted-transactions integer (count)
            :error string (if failure)}"
  [db-conn account-external-id]
  (try
    (let [db (d/db db-conn)
          ;; Get account db/id
          account (d/pull db '[:db/id :account/source] [:account/external-id account-external-id])]
      (if-not (:db/id account)
        {:success false
         :error "Account not found"}
        (if (not= :manual (:account/source account))
          {:success false
           :error "Cannot delete non-manual account"}
          (let [account-db-id (:db/id account)
                ;; Get all transaction ids for this account
                tx-ids (get-transactions-for-account db-conn account-db-id)
                tx-count (count tx-ids)]
            ;; Delete transactions first (referential integrity)
            (when (seq tx-ids)
              (d/transact! db-conn (mapv (fn [tx-id] [:db/retractEntity tx-id]) tx-ids)))
            ;; Delete account
            (d/transact! db-conn [[:db/retractEntity account-db-id]])
            (log/info "Deleted manual account with transactions"
                      {:account-id account-external-id
                       :transaction-count tx-count})
            {:success true
             :deleted-transactions tx-count}))))
    (catch Exception e
      (log/error "Failed to delete manual account"
                 {:account-id account-external-id
                  :error (.getMessage e)})
      {:success false
       :error (.getMessage e)})))

(defn update-account-settings!
  "Update account settings (works for all account types, not just manual).

   db-conn: Datalevin connection
   account-db-id: db/id of the account
   settings: {:invert-amount boolean}

   Returns: {:success boolean
            :error string (if failure)}"
  [db-conn account-db-id settings]
  (try
    (let [db (d/db db-conn)
          ;; Check if entity exists by querying for it
          account-exists? (d/q '[:find ?e .
                                :in $ ?e
                                :where [?e :account/external-id]]
                              db
                              account-db-id)]
      (if-not account-exists?
        {:success false
         :error "Account not found"}
        (let [tx-data (cond-> {:db/id account-db-id}
                        (contains? settings :invert-amount)
                        (assoc :account/invert-amount (:invert-amount settings)))]
          (d/transact! db-conn [tx-data])
          (log/info "Updated account settings"
                    {:account-db-id account-db-id
                     :settings settings})
          {:success true})))
    (catch Exception e
      (log/error "Failed to update account settings"
                 {:account-db-id account-db-id
                  :settings settings
                  :error (.getMessage e)})
      {:success false
       :error (.getMessage e)})))
