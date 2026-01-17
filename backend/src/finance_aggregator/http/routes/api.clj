(ns finance-aggregator.http.routes.api
  "API routes aggregator - combines all API route modules"
  (:require
   [finance-aggregator.http.routes.stats :as stats]
   [finance-aggregator.http.routes.categories :as categories]
   [finance-aggregator.http.routes.transactions :as transactions]
   [finance-aggregator.http.routes.entities :as entities]
   [finance-aggregator.http.routes.plaid :as plaid]
   [finance-aggregator.http.routes.csv :as csv]))

(defn api-routes
  "Combine all API routes under /api prefix.

   Args:
     deps - Dependencies map passed to handlers

   Returns:
     Reitit route data"
  [deps]
  ["/api"
   (stats/stats-routes deps)
   (categories/categories-routes deps)
   (transactions/transactions-routes deps)
   (entities/entities-routes deps)
   (plaid/plaid-routes deps)
   (csv/csv-routes deps)])
