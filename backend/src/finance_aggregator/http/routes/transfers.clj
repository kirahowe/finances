(ns finance-aggregator.http.routes.transfers
  "Transfer-matching routes definition"
  (:require
   [finance-aggregator.http.handlers.transfers :as handlers]))

(defn transfers-routes
  "Define transfer routes.

   Args:
     deps - Dependencies map passed to handlers

   Returns:
     Reitit route data"
  [deps]
  ["/transfers"
   ["" {:post {:handler (handlers/confirm-handler deps)
               :name ::confirm}}]
   ["/suggestions" {:get {:handler (handlers/suggestions-handler deps)
                          :name ::suggestions}}]
   ["/candidates" {:get {:handler (handlers/candidates-handler deps)
                         :name ::candidates}}]
   ["/reject" {:post {:handler (handlers/reject-handler deps)
                      :name ::reject}}]
   ["/:id" {:delete {:handler (handlers/unmatch-handler deps)
                     :name ::unmatch}}]])
