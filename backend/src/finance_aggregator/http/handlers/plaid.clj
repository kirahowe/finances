(ns finance-aggregator.http.handlers.plaid
  "Plaid integration handlers.

   Endpoints:
   - POST /api/plaid/create-link-token - Create link token for Plaid Link
   - POST /api/plaid/exchange-token - Exchange public token for access token
   - GET /api/plaid/accounts - Fetch accounts from Plaid
   - POST /api/plaid/transactions - Fetch transactions from Plaid"
  (:require
   [finance-aggregator.plaid.client :as plaid]
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

   Exchanges public_token for access_token and stores encrypted credential.

   Expected body-params:
   - :publicToken (string)

   Args:
     deps - Map with :db-conn, :secrets, :plaid-config

   Returns:
     Ring handler function"
  [{:keys [db-conn secrets plaid-config]}]
  (fn [request]
    (let [params (:body-params request)
          public-token (:publicToken params)]
      (when-not public-token
        (throw (ex-info "publicToken is required"
                        {:type :bad-request})))
      ;; Exchange public token for access token
      (let [result (plaid/exchange-public-token plaid-config public-token)
            access-token (:access_token result)]
        ;; Store encrypted credential in database
        (credentials/store-credential! db-conn secrets :plaid access-token)
        ;; Return raw Plaid response
        (responses/success-response result)))))

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
