(ns finance-aggregator.http.handlers.entities
  "Entity listing and query handlers.

   Endpoints:
   - GET /api/institutions - List all institutions
   - GET /api/accounts - List all accounts
   - GET /api/transactions - List all transactions (optionally filtered by month)
   - POST /api/query - Execute custom Datalog query"
  (:require
   [datalevin.core :as d]
   [finance-aggregator.http.responses :as responses]
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

(def ^:private transactions-pull-pattern
  '[* {:transaction/category [:db/id :category/name]
       :transaction/account [:db/id :account/external-name]}])

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
          (responses/success-response (vec results)))
        ;; No filter - return all transactions
        (let [query '[:find [(pull ?e pattern) ...]
                      :in $ pattern
                      :where [?e :transaction/external-id _]]
              results (d/q query db transactions-pull-pattern)]
          (responses/success-response results))))))

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
