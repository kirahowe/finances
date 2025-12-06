(ns finance-aggregator.http.handlers.entities
  "Entity listing and query handlers.

   Endpoints:
   - GET /api/institutions - List all institutions
   - GET /api/accounts - List all accounts
   - GET /api/transactions - List all transactions
   - POST /api/query - Execute custom Datalog query"
  (:require
   [datalevin.core :as d]
   [finance-aggregator.http.responses :as responses]))

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

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [_request]
    (let [db (d/db db-conn)
          query '[:find [(pull ?e [*]) ...]
                  :where [?e :account/external-id _]]
          results (d/q query db)]
      (responses/success-response results))))

(defn list-transactions-handler
  "Factory: creates handler for GET /api/transactions.

   Includes category and account information in pull pattern.

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [_request]
    (let [db (d/db db-conn)
          query '[:find [(pull ?e [* {:transaction/category [:db/id :category/name]
                                       :transaction/account [:db/id :account/external-name]}]) ...]
                  :where [?e :transaction/external-id _]]
          results (d/q query db)]
      (responses/success-response results))))

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
