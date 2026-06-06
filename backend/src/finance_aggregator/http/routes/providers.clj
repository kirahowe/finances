(ns finance-aggregator.http.routes.providers
  "Generic, provider-agnostic routes. Adding a secrets-based provider needs no
   new routes - just a `defmethod` on the seam."
  (:require
   [finance-aggregator.http.handlers.providers :as handlers]))

(defn providers-routes
  "Routes under /providers. deps is passed to handler factories."
  [deps]
  ["/providers"
   ["/:provider/sync" {:post {:handler (handlers/sync-handler deps)
                              :name ::sync}}]])
