(ns finance-aggregator.http.handlers.categories
  "Category CRUD handlers.

   Endpoints:
   - GET /api/categories - List all categories
   - POST /api/categories - Create category
   - PUT /api/categories/:id - Update category
   - DELETE /api/categories/:id - Delete category
   - POST /api/categories/batch-sort - Batch update sort orders"
  (:require
   [finance-aggregator.db.categories :as db-categories]
   [finance-aggregator.http.responses :as responses]))

(defn list-categories-handler
  "Factory: creates handler for GET /api/categories.

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [_request]
    (let [categories (db-categories/list-all db-conn)]
      (responses/success-response categories))))

(defn create-category-handler
  "Factory: creates handler for POST /api/categories.

   Expected body-params:
   - :name (string)
   - :type (keyword)
   - :ident (keyword)
   - :sortOrder (optional, number)

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [request]
    (let [params (:body-params request)
          category-data (cond-> {:category/name (:name params)
                                 :category/type (keyword (:type params))
                                 :category/ident (keyword (:ident params))}
                          (:sortOrder params) (assoc :category/sort-order (:sortOrder params)))
          result (db-categories/create! db-conn category-data)]
      (responses/success-response result 201))))

(defn update-category-handler
  "Factory: creates handler for PUT /api/categories/:id.

   Expected body-params (all optional):
   - :name (string)
   - :type (keyword)
   - :ident (keyword)
   - :sortOrder (number)

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [request]
    (let [db-id (-> request :path-params :id parse-long)
          params (:body-params request)
          updates (cond-> {}
                    (:name params) (assoc :category/name (:name params))
                    (:type params) (assoc :category/type (keyword (:type params)))
                    (:ident params) (assoc :category/ident (keyword (:ident params)))
                    (:sortOrder params) (assoc :category/sort-order (:sortOrder params)))
          result (db-categories/update! db-conn db-id updates)]
      (responses/success-response result))))

(defn delete-category-handler
  "Factory: creates handler for DELETE /api/categories/:id.

   Checks if category has transactions before deleting.
   Returns 400 error if category has assigned transactions.

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [request]
    (let [db-id (-> request :path-params :id parse-long)]
      (if (db-categories/has-transactions? db-conn db-id)
        (throw (ex-info "Cannot delete category with assigned transactions"
                        {:type :bad-request}))
        (do
          (db-categories/delete! db-conn db-id)
          (responses/success-response {:deleted true}))))))

(defn batch-update-sort-orders-handler
  "Factory: creates handler for POST /api/categories/batch-sort.

   Expected body-params:
   - :updates (vector of maps with :id and :sortOrder)

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [request]
    (let [params (:body-params request)
          updates (mapv (fn [update]
                          {:db/id (:id update)
                           :category/sort-order (:sortOrder update)})
                        (:updates params))
          result (db-categories/batch-update-sort-orders! db-conn updates)]
      (responses/success-response result))))
