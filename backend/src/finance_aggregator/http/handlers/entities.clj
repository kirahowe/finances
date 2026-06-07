(ns finance-aggregator.http.handlers.entities
  "Entity listing and query handlers.

   Endpoints:
   - GET /api/institutions - List all institutions
   - GET /api/accounts - List all accounts
   - POST /api/accounts - Create manual account
   - GET /api/accounts/:id - Get single account
   - DELETE /api/accounts/:id - Delete manual account
   - PUT /api/accounts/:id/settings - Update account settings
   - GET /api/transactions - List all transactions (optionally filtered by month)
   - POST /api/query - Execute custom Datalog query"
  (:require
   [datalevin.core :as d]
   [finance-aggregator.db.transactions :as db-transactions]
   [finance-aggregator.http.responses :as responses]
   [finance-aggregator.manual.service :as manual-service]
   [finance-aggregator.utils :as utils]))

(defn list-institutions-handler
  "Factory: creates handler for GET /api/institutions.

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [_request]
    (let [db (d/db db-conn)
          query '[:find [(pull ?e [*]) ...]
                  :where [?e :institution/id _]]
          results (d/q query db)]
      (responses/success-response results))))

(defn list-accounts-handler
  "Factory: creates handler for GET /api/accounts.

   Includes institution information in pull pattern.

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [_request]
    (let [db (d/db db-conn)
          query '[:find [(pull ?e [* {:account/institution [:db/id :institution/name]}]) ...]
                  :where [?e :account/external-id _]]
          results (d/q query db)]
      (responses/success-response results))))

(defn create-account-handler
  "Factory: creates handler for POST /api/accounts.

   Creates a manual account with user-specified institution.

   Expected body-params:
   - :name (string) - Account name
   - :currency (string, optional) - Currency code (defaults to USD)
   - :institutionName (string) - Institution name

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [request]
    (let [params (:body-params request)
          account-data {:name (:name params)
                       :currency (:currency params)
                       :institution-name (:institutionName params)}
          result (manual-service/create-account! db-conn account-data)]
      (if (:success result)
        (responses/success-response (:data result))
        (responses/json-response {:error (:error result)} 400)))))

(defn get-account-handler
  "Factory: creates handler for GET /api/accounts/:id.

   Gets a single account by db/id.

   Path params:
   - :id - Account db/id

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [request]
    (let [id (parse-long (get-in request [:path-params :id]))
          db (d/db db-conn)
          account (d/pull db '[* {:account/institution [:db/id :institution/name]}] id)]
      (if (:db/id account)
        (responses/success-response account)
        (responses/json-response {:error "Account not found"} 404)))))

(defn delete-account-handler
  "Factory: creates handler for DELETE /api/accounts/:id.

   Deletes a manual account (cascade deletes transactions).

   Path params:
   - :id - Account external-id (not db/id)

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [request]
    (let [account-external-id (get-in request [:path-params :id])
          result (manual-service/delete-account! db-conn account-external-id)]
      (if (:success result)
        (responses/success-response {:deleted-transactions (:deleted-transactions result)})
        (responses/json-response {:error (:error result)} 400)))))

(defn update-account-settings-handler
  "Factory: creates handler for PUT /api/accounts/:id/settings.

   Updates account settings (works for all account types).

   Path params:
   - :id - Account db/id

   Expected body-params:
   - :invertAmount (boolean, optional) - Whether to invert transaction amounts

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [request]
    (let [id (parse-long (get-in request [:path-params :id]))
          params (:body-params request)
          settings (cond-> {}
                     (contains? params :invertAmount)
                     (assoc :invert-amount (:invertAmount params)))
          result (manual-service/update-account-settings! db-conn id settings)]
      (if (:success result)
        (responses/success-response {:success true})
        (responses/json-response {:error (:error result)} 400)))))

(def ^:private transactions-pull-pattern
  ['* {:transaction/category [:db/id :category/name :category/type]
       :transaction/account [:db/id :account/external-name
                             {:account/institution [:db/id :institution/name]}]
       :transaction/splits db-transactions/split-pull
       ;; Self-contained partner snapshot so the frontend can apply the hide rule
       ;; and render partner info without the partner being in the current view.
       :transaction/transfer-pair [:db/id :transaction/amount :transaction/posted-date
                                   {:transaction/category [:db/id :category/name :category/type]}
                                   {:transaction/account [:db/id :account/external-name]}]}])

(defn list-transactions-handler
  "Factory: creates handler for GET /api/transactions.

   Optional query params:
   - month: YYYY-MM format (e.g., '2025-01') - filters transactions by month

   Includes category and account information in pull pattern.

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [request]
    (let [month (get-in request [:query-params "month"])
          db (d/db db-conn)]
      (if month
        ;; Filter by month
        ;; Note: Datalevin has a limitation combining >= and < on dates in the same query,
        ;; so we query with end-date only and post-filter by start-date
        (let [{:keys [start-date end-date]} (utils/month-date-range month)
              query '[:find [(pull ?e pattern) ...]
                      :in $ pattern ?end
                      :where
                      [?e :transaction/external-id _]
                      [?e :transaction/posted-date ?date]
                      [(< ?date ?end)]]
              raw-results (d/q query db transactions-pull-pattern end-date)
              ;; Post-filter by start date
              results (filter #(not (.before (:transaction/posted-date %) start-date))
                              raw-results)]
          (responses/success-response (mapv db-transactions/with-split-balance results)))
        ;; No filter - return all transactions
        (let [query '[:find [(pull ?e pattern) ...]
                      :in $ pattern
                      :where [?e :transaction/external-id _]]
              results (d/q query db transactions-pull-pattern)]
          (responses/success-response (mapv db-transactions/with-split-balance results)))))))

(defn query-handler
  "Factory: creates handler for POST /api/query.

   Executes custom Datalog query from request body.

   Expected body-params:
   - :query (string) - Datalog query as string

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [request]
    (let [params (:body-params request)
          query-str (:query params)]
      (when-not query-str
        (throw (ex-info "query parameter is required"
                        {:type :bad-request})))
      (let [query (read-string query-str)
            db (d/db db-conn)
            results (d/q query db)]
        (responses/success-response results)))))
