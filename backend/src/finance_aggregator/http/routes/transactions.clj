(ns finance-aggregator.http.routes.transactions
  "Transaction routes definition"
  (:require
   [finance-aggregator.http.handlers.transactions :as handlers]))

(defn transactions-routes
  "Define transaction routes.

   Args:
     deps - Dependencies map passed to handlers

   Returns:
     Reitit route data"
  [deps]
  ["/transactions"
   ["/:id/category" {:put {:handler (handlers/update-transaction-category-handler deps)
                           :name ::update-category}}]
   ["/:id/splits" {:put {:handler (handlers/set-transaction-splits-handler deps)
                         :name ::set-splits}}]
   ["/:id/reviewed" {:put {:handler (handlers/set-transaction-reviewed-handler deps)
                           :name ::set-reviewed}}]
   ["/:id/splits/:splitId/reviewed" {:put {:handler (handlers/set-split-reviewed-handler deps)
                                           :name ::set-split-reviewed}}]])
