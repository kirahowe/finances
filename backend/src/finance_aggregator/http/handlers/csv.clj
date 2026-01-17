(ns finance-aggregator.http.handlers.csv
  "CSV import and mapping handlers.

   Endpoints:
   - GET /api/csv/mapping/:account-id - Get CSV mapping config
   - POST /api/csv/mapping/:account-id - Save CSV mapping config
   - POST /api/csv/preview/:account-id - Preview CSV import
   - POST /api/csv/import/:account-id - Execute CSV import"
  (:require
   [datalevin.core :as d]
   [finance-aggregator.csv.service :as csv-service]
   [finance-aggregator.http.responses :as responses]))

(defn get-mapping-handler
  "Factory: creates handler for GET /api/csv/mapping/:account-id.

   Gets stored CSV mapping configuration for an account.

   Path params:
   - :account-id - Account db/id

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [request]
    (let [account-id (parse-long (get-in request [:path-params :account-id]))
          result (csv-service/get-csv-mapping db-conn account-id)]
      (if (:success result)
        (responses/success-response (:data result))
        (responses/json-response {:error (:error result)} 400)))))

(defn save-mapping-handler
  "Factory: creates handler for POST /api/csv/mapping/:account-id.

   Saves CSV mapping configuration for an account.

   Path params:
   - :account-id - Account db/id

   Expected body-params:
   - :columns {:date string :amount string :payee string :description string}
   - :dateFormat string

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [request]
    (let [account-id (parse-long (get-in request [:path-params :account-id]))
          params (:body-params request)
          mapping {:columns (:columns params)
                   :date-format (:dateFormat params)}
          result (csv-service/save-csv-mapping! db-conn account-id mapping)]
      (if (:success result)
        (responses/success-response {:success true})
        (responses/json-response {:error (:error result)} 400)))))

(defn preview-csv-handler
  "Factory: creates handler for POST /api/csv/preview/:account-id.

   Previews CSV file with automatic column detection.

   Path params:
   - :account-id - Account db/id

   Expected body-params:
   - :csvContent string - CSV file content

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [request]
    (let [account-id (parse-long (get-in request [:path-params :account-id]))
          params (:body-params request)
          csv-content (:csvContent params)]
      (when-not csv-content
        (throw (ex-info "csvContent is required"
                        {:type :bad-request})))
      (let [result (csv-service/preview-csv-import db-conn account-id csv-content)]
        (if (:success result)
          (responses/success-response (:data result))
          (responses/json-response {:error (:error result)} 400))))))

(defn import-csv-handler
  "Factory: creates handler for POST /api/csv/import/:account-id.

   Imports transactions from CSV file.

   Path params:
   - :account-id - Account db/id

   Expected body-params:
   - :csvContent string - CSV file content
   - :mapping {:columns {:date :amount :payee :description} :dateFormat string}

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [request]
    (let [account-db-id (parse-long (get-in request [:path-params :account-id]))
          params (:body-params request)
          csv-content (:csvContent params)
          mapping (:mapping params)]
      (when-not csv-content
        (throw (ex-info "csvContent is required"
                        {:type :bad-request})))
      (when-not mapping
        (throw (ex-info "mapping is required"
                        {:type :bad-request})))
      ;; Look up account to get external-id
      (let [db (d/db db-conn)
            account (d/pull db '[:account/external-id] account-db-id)
            account-external-id (:account/external-id account)]
        (when-not account-external-id
          (throw (ex-info "Account not found"
                          {:type :not-found
                           :account-id account-db-id})))
        (let [result (csv-service/import-csv-transactions!
                      db-conn
                      account-external-id
                      csv-content
                      mapping)]
          (if (:success result)
            (responses/success-response (:data result))
            (responses/json-response {:error (:error result)} 400)))))))
