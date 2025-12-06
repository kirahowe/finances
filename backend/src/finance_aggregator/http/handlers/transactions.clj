(ns finance-aggregator.http.handlers.transactions
  "Transaction handlers.

   Endpoints:
   - PUT /api/transactions/:id/category - Assign category to transaction"
  (:require
   [finance-aggregator.db.transactions :as db-transactions]
   [finance-aggregator.http.responses :as responses]))

(defn update-transaction-category-handler
  "Factory: creates handler for PUT /api/transactions/:id/category.

   Expected body-params:
   - :categoryId (number or nil)

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [request]
    (let [tx-id (-> request :path-params :id parse-long)
          params (:body-params request)
          category-id (:categoryId params)
          result (db-transactions/update-category! db-conn tx-id category-id)]
      (responses/success-response result))))
