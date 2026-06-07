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
   - :parentId (optional, db/id of parent category)

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
                          (:sortOrder params) (assoc :category/sort-order (:sortOrder params))
                          (:parentId params) (assoc :category/parent (:parentId params)))
          result (db-categories/create! db-conn category-data)]
      (responses/success-response result 201))))

(defn bulk-create-categories-handler
  "Factory: creates handler for POST /api/categories/bulk.

   Creates many categories in one atomic transaction. Within-batch parent
   references let a parent and its children be created together.

   Expected body-params:
   - :categories (vector of maps, each with):
     - :name (string)
     - :type (string, e.g. \"expense\")
     - :ident (string, e.g. \"category/groceries\")
     - :tempId (string, unique within the batch)
     - :parentTempId (optional, references another item's :tempId)

   Args:
     deps - Map with :db-conn

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [request]
    (let [items (-> request :body-params :categories)
          prepared (mapv (fn [item]
                           (cond-> {:category/name (:name item)
                                    :category/type (keyword (:type item))
                                    :category/ident (keyword (:ident item))
                                    :tempid (:tempId item)}
                             (:parentTempId item) (assoc :parent-tempid (:parentTempId item))))
                         items)
          result (db-categories/create-many! db-conn prepared)]
      (responses/success-response result 201))))

(defn update-category-handler
  "Factory: creates handler for PUT /api/categories/:id.

   Expected body-params (all optional):
   - :name (string)
   - :type (keyword)
   - :ident (keyword)
   - :sortOrder (number)
   - :parentId (db/id of parent, or null to clear the parent)

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
                    (:sortOrder params) (assoc :category/sort-order (:sortOrder params))
                    (contains? params :parentId) (assoc :category/parent (:parentId params)))
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
