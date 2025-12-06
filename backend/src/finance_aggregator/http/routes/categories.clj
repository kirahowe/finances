(ns finance-aggregator.http.routes.categories
  "Category routes definition"
  (:require
   [finance-aggregator.http.handlers.categories :as handlers]))

(defn categories-routes
  "Define category CRUD routes.

   Args:
     deps - Dependencies map passed to handlers

   Returns:
     Reitit route data"
  [deps]
  ["/categories"
   ["" {:get {:handler (handlers/list-categories-handler deps)
              :name ::list}
        :post {:handler (handlers/create-category-handler deps)
               :name ::create}}]
   ;; Specific route must come before parameterized route
   ["/batch-sort" {:post {:handler (handlers/batch-update-sort-orders-handler deps)
                          :name ::batch-sort}}]
   ["/:id" {:put {:handler (handlers/update-category-handler deps)
                  :name ::update}
            :delete {:handler (handlers/delete-category-handler deps)
                     :name ::delete}}]])
