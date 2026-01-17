(ns finance-aggregator.http.handlers.plaid
  "Plaid integration handlers.

   Endpoints:
   - POST /api/plaid/create-link-token - Create link token for Plaid Link
   - POST /api/plaid/exchange-token - Exchange public token for access token
   - GET /api/plaid/accounts - Fetch accounts from Plaid
   - POST /api/plaid/transactions - Fetch transactions from Plaid"
  (:require
   [finance-aggregator.plaid.client :as plaid]
   [finance-aggregator.plaid.service :as plaid-svc]
   [finance-aggregator.db.credentials :as credentials]
   [finance-aggregator.http.responses :as responses]))

(def ^:private hardcoded-user-id
  "Hardcoded user ID for Phase 2/3 testing. Will be removed in Phase 7."
  "test-user")

(defn create-link-token-handler
  "Factory: creates handler for POST /api/plaid/create-link-token.

   Generates link_token for Plaid Link frontend initialization.
   Uses hardcoded user-id for Phase 2/3.

   Args:
     deps - Map with :plaid-config

   Returns:
     Ring handler function"
  [{:keys [plaid-config]}]
  (fn [_request]
    (let [link-token (plaid/create-link-token plaid-config hardcoded-user-id)]
      (responses/success-response {:linkToken link-token}))))

(defn exchange-token-handler
  "Factory: creates handler for POST /api/plaid/exchange-token.

   Exchanges public_token for access_token, fetches institution info,
   and stores encrypted credential with item metadata.

   Expected body-params:
   - :publicToken (string)
   - :accountIds (vector of strings) - Selected account IDs from Plaid Link

   Args:
     deps - Map with :db-conn, :secrets, :plaid-config

   Returns:
     Ring handler function

   Response includes:
   - :access_token (stored encrypted in DB, returned for debugging)
   - :item_id (Plaid Item identifier)
   - :institution_name (Human-readable institution name)
   - :selected_accounts (number of accounts selected)"
  [{:keys [db-conn secrets plaid-config]}]
  (fn [request]
    (let [params (:body-params request)
          public-token (:publicToken params)
          account-ids (:accountIds params)]
      (when-not public-token
        (throw (ex-info "publicToken is required"
                        {:type :bad-request})))
      ;; 1. Exchange public token for access token
      (let [result (plaid/exchange-public-token plaid-config public-token)
            access-token (:access_token result)
            item-id (:item_id result)]

        ;; 2. Fetch item info to get institution_id
        (let [item-info (plaid/fetch-item plaid-config access-token)
              institution-id (:institution_id item-info)
              ;; 3. Fetch institution details for human-readable name
              institution (plaid/fetch-institution plaid-config institution-id)
              institution-name (:name institution)]

          ;; 4. Store encrypted credential with item metadata and selected accounts
          (credentials/store-plaid-item-credential!
           db-conn secrets access-token item-id institution-name account-ids)

          ;; Return response with item info
          ;; Include item_id for frontend polling
          (responses/success-response
           {:access_token access-token
            :item_id item-id
            :institution_name institution-name
            :selected_accounts (when account-ids (count account-ids))
            :sync_status :pending}))))))

(defn get-accounts-handler
  "Factory: creates handler for GET /api/plaid/accounts.

   Fetches accounts using stored credential.

   Args:
     deps - Map with :db-conn, :secrets, :plaid-config

   Returns:
     Ring handler function"
  [{:keys [db-conn secrets plaid-config]}]
  (fn [_request]
    (let [access-token (credentials/get-credential db-conn secrets :plaid)]
      (when-not access-token
        (throw (ex-info "No Plaid credential found. Please connect your bank account first."
                        {:type :not-found
                         :hint "Use POST /api/plaid/exchange-token to link your account"})))
      ;; Fetch accounts from Plaid
      (let [accounts (plaid/fetch-accounts plaid-config access-token)]
        (responses/success-response accounts)))))

(defn get-transactions-handler
  "Factory: creates handler for POST /api/plaid/transactions.

   Fetches transactions using stored credential.

   Expected body-params:
   - :startDate (string, YYYY-MM-DD format)
   - :endDate (string, YYYY-MM-DD format)

   Args:
     deps - Map with :db-conn, :secrets, :plaid-config

   Returns:
     Ring handler function"
  [{:keys [db-conn secrets plaid-config]}]
  (fn [request]
    (let [params (:body-params request)
          start-date (:startDate params)
          end-date (:endDate params)
          access-token (credentials/get-credential db-conn secrets :plaid)]
      (when-not access-token
        (throw (ex-info "No Plaid credential found. Please connect your bank account first."
                        {:type :not-found
                         :hint "Use POST /api/plaid/exchange-token to link your account"})))
      (when-not (and start-date end-date)
        (throw (ex-info "startDate and endDate are required"
                        {:type :bad-request
                         :hint "Format: YYYY-MM-DD"})))
      ;; Fetch transactions from Plaid
      (let [transactions (plaid/fetch-transactions plaid-config access-token start-date end-date)]
        (responses/success-response transactions)))))

(defn sync-accounts-handler
  "Factory: creates handler for POST /api/plaid/sync-accounts.

   Syncs Plaid accounts and institution to database.
   Fetches data from Plaid API, transforms, and persists.

   Args:
     deps - Map with :db-conn, :secrets, :plaid-config

   Returns:
     Ring handler function"
  [{:keys [db-conn secrets plaid-config]}]
  (fn [_request]
    (let [result (plaid-svc/sync-accounts! {:db-conn db-conn
                                            :secrets secrets
                                            :plaid-config plaid-config})]
      (responses/success-response result))))

(defn sync-transactions-handler
  "Factory: creates handler for POST /api/plaid/sync-transactions.

   Syncs Plaid transactions to database for specified date range.
   Defaults to 6 months if not specified.

   Expected body-params (optional):
   - :months (integer, default 6)
   - :endDate (string, YYYY-MM-DD format)

   Args:
     deps - Map with :db-conn, :secrets, :plaid-config

   Returns:
     Ring handler function"
  [{:keys [db-conn secrets plaid-config]}]
  (fn [request]
    (let [params (:body-params request)
          opts {:months (or (:months params) 6)
                :end-date (:endDate params)}
          result (plaid-svc/sync-transactions! {:db-conn db-conn
                                                :secrets secrets
                                                :plaid-config plaid-config}
                                               opts)]
      (responses/success-response result))))

(defn delete-credential-handler
  "Factory: creates handler for DELETE /api/plaid/credential.

   Deletes stored Plaid credential. Use this when you need to
   re-link accounts (e.g., switching from sandbox to production).

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [_request]
    (let [deleted? (credentials/delete-credential! db-conn :plaid)]
      (if deleted?
        (responses/success-response {:deleted true
                                     :message "Plaid credential deleted. Please re-link your accounts."})
        (responses/success-response {:deleted false
                                     :message "No Plaid credential found to delete."})))))

(defn list-items-handler
  "Factory: creates handler for GET /api/plaid/items.

   Lists all linked Plaid Items (bank connections) for the user.
   Returns item metadata without decrypted access tokens.

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function

   Response:
     [{:item-id string
       :institution-name string
       :created-at instant} ...]"
  [{:keys [db-conn]}]
  (fn [_request]
    (let [items (credentials/list-plaid-items db-conn)]
      (responses/success-response items))))

(defn sync-month-transactions-handler
  "Factory: creates handler for POST /api/plaid/sync-month-transactions.

   Syncs Plaid transactions to database for a specific month.
   Useful for refreshing transactions when viewing a specific month.

   Expected body-params:
   - :month (string, YYYY-MM format, required)

   Args:
     deps - Map with :db-conn, :secrets, :plaid-config

   Returns:
     Ring handler function"
  [{:keys [db-conn secrets plaid-config]}]
  (fn [request]
    (let [params (:body-params request)
          month (:month params)]
      (when-not month
        (throw (ex-info "month is required"
                        {:type :bad-request
                         :hint "Format: YYYY-MM (e.g., 2025-01)"})))
      (let [result (plaid-svc/sync-month-transactions! {:db-conn db-conn
                                                        :secrets secrets
                                                        :plaid-config plaid-config}
                                                       month)]
        (responses/success-response result)))))

(defn delete-item-handler
  "Factory: creates handler for DELETE /api/plaid/items/:item-id.

   Deletes a specific Plaid Item credential by item-id.

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [request]
    (let [item-id (get-in request [:path-params :item-id])]
      (when-not item-id
        (throw (ex-info "item-id is required"
                        {:type :bad-request})))
      (let [deleted? (credentials/delete-plaid-item-credential! db-conn item-id)]
        (if deleted?
          (responses/success-response {:deleted true
                                       :item-id item-id
                                       :message "Plaid Item deleted."})
          (responses/success-response {:deleted false
                                       :item-id item-id
                                       :message "Plaid Item not found."}))))))

;;; Sync Status and Control Handlers (for frontend polling)

(defn get-sync-status-handler
  "Factory: creates handler for GET /api/plaid/items/:item-id/sync-status.

   Returns sync status for a Plaid Item. Used by frontend for polling
   during initial sync to determine when transactions are ready.

   Args:
     deps - Map with :db-conn, :secrets, :plaid-config

   Returns:
     Ring handler function

   Response:
     {:item-id string
      :institution-name string
      :sync-status keyword (:pending :syncing :synced :failed)
      :has-cursor boolean
      :transaction-count int
      :last-sync-at instant or nil
      :ready-for-display boolean}"
  [{:keys [db-conn secrets plaid-config] :as deps}]
  (fn [request]
    (let [item-id (get-in request [:path-params :item-id])]
      (when-not item-id
        (throw (ex-info "item-id is required"
                        {:type :bad-request})))
      (let [status (plaid-svc/get-item-sync-status {:db-conn db-conn} item-id)]
        (responses/success-response status)))))

(defn trigger-sync-handler
  "Factory: creates handler for POST /api/plaid/items/:item-id/sync.

   Triggers a transaction sync for a specific Plaid Item.
   Sets sync status to :syncing before starting.

   Args:
     deps - Map with :db-conn, :secrets, :plaid-config

   Returns:
     Ring handler function"
  [{:keys [db-conn secrets plaid-config] :as deps}]
  (fn [request]
    (let [item-id (get-in request [:path-params :item-id])]
      (when-not item-id
        (throw (ex-info "item-id is required"
                        {:type :bad-request})))
      (let [access-token (credentials/get-plaid-item-credential db-conn secrets item-id)]
        (when-not access-token
          (throw (ex-info "Item not found"
                          {:type :not-found
                           :item-id item-id})))

        ;; Set status to syncing
        (credentials/update-sync-status! db-conn item-id :syncing)

        ;; Trigger sync
        (let [institution-name (credentials/get-institution-name db-conn item-id)
              result (plaid-svc/sync-item-transactions!
                      {:db-conn db-conn :plaid-config plaid-config}
                      {:item-id item-id
                       :access-token access-token
                       :institution-name institution-name})]
          (responses/success-response result))))))

(defn reset-sync-handler
  "Factory: creates handler for POST /api/plaid/items/:item-id/reset-sync.

   Resets the sync cursor for a Plaid Item, enabling a full re-sync.
   The next sync will fetch up to 730 days of transaction history.

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [request]
    (let [item-id (get-in request [:path-params :item-id])]
      (when-not item-id
        (throw (ex-info "item-id is required"
                        {:type :bad-request})))
      (let [reset? (credentials/reset-sync-cursor! db-conn item-id)]
        (if reset?
          (responses/success-response {:reset true
                                       :item-id item-id
                                       :message "Sync cursor reset. Next sync will fetch full history."})
          (responses/success-response {:reset false
                                       :item-id item-id
                                       :message "Plaid Item not found."}))))))
