(ns finance-aggregator.http.routes.entities
  "Entity listing and query routes definition"
  (:require
   [finance-aggregator.http.handlers.entities :as handlers]))

(defn entities-routes
  "Define entity listing and query routes.

   Args:
     deps - Dependencies map passed to handlers

   Returns:
     Reitit route data"
  [deps]
  [""
   ["/institutions" {:get {:handler (handlers/list-institutions-handler deps)
                           :name ::list-institutions}}]
   ["/accounts" {:get {:handler (handlers/list-accounts-handler deps)
                       :name ::list-accounts}}]
   ["/transactions" {:get {:handler (handlers/list-transactions-handler deps)
                           :name ::list-transactions}}]
   ["/query" {:post {:handler (handlers/query-handler deps)
                     :name ::query}}]])
