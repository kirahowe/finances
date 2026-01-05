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
   ;; OAuth flow
   ["/create-link-token" {:post {:handler (handlers/create-link-token-handler deps)
                                 :name ::create-link-token}}]
   ["/exchange-token" {:post {:handler (handlers/exchange-token-handler deps)
                              :name ::exchange-token}}]

   ;; Item management (multi-bank support)
   ["/items" {:get {:handler (handlers/list-items-handler deps)
                    :name ::list-items}}]
   ["/items/:item-id" {:delete {:handler (handlers/delete-item-handler deps)
                                :name ::delete-item}}]

   ;; Legacy credential endpoint (for backward compatibility)
   ["/credential" {:delete {:handler (handlers/delete-credential-handler deps)
                            :name ::delete-credential}}]

   ;; Read-only Plaid API endpoints (for testing/debugging)
   ["/accounts" {:get {:handler (handlers/get-accounts-handler deps)
                       :name ::get-accounts}}]
   ["/transactions" {:post {:handler (handlers/get-transactions-handler deps)
                            :name ::get-transactions}}]

   ;; Sync endpoints (persist to database)
   ["/sync-accounts" {:post {:handler (handlers/sync-accounts-handler deps)
                             :name ::sync-accounts}}]
   ["/sync-transactions" {:post {:handler (handlers/sync-transactions-handler deps)
                                 :name ::sync-transactions}}]
   ["/sync-month-transactions" {:post {:handler (handlers/sync-month-transactions-handler deps)
                                       :name ::sync-month-transactions}}]])
