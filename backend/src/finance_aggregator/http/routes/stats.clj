(ns finance-aggregator.http.routes.stats
  "Stats routes definition"
  (:require
   [finance-aggregator.http.handlers.stats :as handlers]))

(defn stats-routes
  "Define stats routes.

   Args:
     deps - Dependencies map passed to handlers

   Returns:
     Reitit route data"
  [deps]
  ["/stats"
   {:get {:handler (handlers/stats-handler deps)
          :name ::stats}}])
