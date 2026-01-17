(ns finance-aggregator.http.routes.csv
  "CSV import and mapping routes definition"
  (:require
   [finance-aggregator.http.handlers.csv :as handlers]))

(defn csv-routes
  "Define CSV import and mapping routes.

   Args:
     deps - Dependencies map passed to handlers

   Returns:
     Reitit route data"
  [deps]
  ["/csv"
   ["/mapping/:account-id" {:get {:handler (handlers/get-mapping-handler deps)
                                  :name ::get-mapping}
                            :post {:handler (handlers/save-mapping-handler deps)
                                   :name ::save-mapping}}]
   ["/preview/:account-id" {:post {:handler (handlers/preview-csv-handler deps)
                                   :name ::preview-csv}}]
   ["/import/:account-id" {:post {:handler (handlers/import-csv-handler deps)
                                  :name ::import-csv}}]])
