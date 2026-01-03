(ns finance-aggregator.http.routes.plaid
  "Plaid integration routes definition"
  (:require
   [finance-aggregator.http.handlers.plaid :as handlers]))

(defn plaid-routes
  "Define Plaid integration routes.

   Args:
     deps - Dependencies map passed to handlers

   Returns:
     Reitit route data"
  [deps]
  ["/plaid"
   ["/create-link-token" {:post {:handler (handlers/create-link-token-handler deps)
                                 :name ::create-link-token}}]
   ["/exchange-token" {:post {:handler (handlers/exchange-token-handler deps)
                              :name ::exchange-token}}]
   ["/accounts" {:get {:handler (handlers/get-accounts-handler deps)
                       :name ::get-accounts}}]
   ["/transactions" {:post {:handler (handlers/get-transactions-handler deps)
                            :name ::get-transactions}}]
   ["/sync-accounts" {:post {:handler (handlers/sync-accounts-handler deps)
                             :name ::sync-accounts}}]
   ["/sync-transactions" {:post {:handler (handlers/sync-transactions-handler deps)
                                 :name ::sync-transactions}}]])
