(ns finance-aggregator.http.handlers.transactions
  "Transaction handlers.

   Endpoints:
   - PUT /api/transactions/:id/category - Assign category to transaction
   - PUT /api/transactions/:id/splits   - Replace a transaction's splits"
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

(defn set-transaction-splits-handler
  "Factory: creates handler for PUT /api/transactions/:id/splits.

   Replaces the transaction's splits with the provided set (an empty vector
   clears them). Amounts arrive as strings to preserve bigdec precision.

   Expected body-params:
   - :splits (vector of {:amount string :categoryId number :memo string?})

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [request]
    (let [tx-id (-> request :path-params :id parse-long)
          splits (mapv (fn [s] (cond-> {:amount (:amount s)
                                        :category-id (:categoryId s)}
                                 (:memo s) (assoc :memo (:memo s))))
                       (-> request :body-params :splits))]
      (responses/success-response (db-transactions/set-splits! db-conn tx-id splits)))))
