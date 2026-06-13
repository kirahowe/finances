(ns finance-aggregator.http.handlers.transactions
  "Transaction handlers.

   Endpoints:
   - PUT /api/transactions/:id/category               - Assign category to transaction
   - PUT /api/transactions/:id/description            - Set/clear a transaction's user description
   - PUT /api/transactions/:id/splits                 - Replace a transaction's splits
   - PUT /api/transactions/:id/splits/:splitId/memo   - Set/clear one split's memo (description)
   - PUT /api/transactions/:id/reviewed               - Mark a transaction reviewed
   - PUT /api/transactions/:id/splits/:splitId/reviewed - Mark one split reviewed"
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

(defn set-transaction-description-handler
  "Factory: creates handler for PUT /api/transactions/:id/description.

   Sets the user's description override (or clears it when blank/empty), leaving the
   imported :transaction/description untouched.

   Expected body-params:
   - :description (string or nil) — the override text; blank/nil clears it

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [request]
    (let [tx-id (-> request :path-params :id parse-long)
          description (-> request :body-params :description)]
      (responses/success-response
       (db-transactions/set-user-description! db-conn tx-id description)))))

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

(defn set-split-memo-handler
  "Factory: creates handler for PUT /api/transactions/:id/splits/:splitId/memo.

   Sets one split part's memo (its description) independently of the parent and its
   siblings, clearing it when blank, and returns the refreshed parent transaction.

   Expected body-params:
   - :memo (string or nil) — the memo text; blank/nil clears it

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [request]
    (let [tx-id (-> request :path-params :id parse-long)
          split-id (-> request :path-params :splitId parse-long)
          memo (-> request :body-params :memo)]
      (responses/success-response
       (db-transactions/set-split-memo! db-conn tx-id split-id memo)))))

(defn set-transaction-reviewed-handler
  "Factory: creates handler for PUT /api/transactions/:id/reviewed.

   Expected body-params:
   - :reviewed (boolean) — true marks reviewed, false clears it

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [request]
    (let [tx-id (-> request :path-params :id parse-long)
          reviewed? (-> request :body-params :reviewed boolean)]
      (responses/success-response
       (db-transactions/set-reviewed! db-conn tx-id reviewed?)))))

(defn set-split-reviewed-handler
  "Factory: creates handler for PUT /api/transactions/:id/splits/:splitId/reviewed.

   Marks one split part reviewed independently of the parent and its siblings, and
   returns the refreshed parent transaction.

   Expected body-params:
   - :reviewed (boolean) — true marks reviewed, false clears it

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [request]
    (let [tx-id (-> request :path-params :id parse-long)
          split-id (-> request :path-params :splitId parse-long)
          reviewed? (-> request :body-params :reviewed boolean)]
      (responses/success-response
       (db-transactions/set-split-reviewed! db-conn tx-id split-id reviewed?)))))
