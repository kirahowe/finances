(ns finance-aggregator.http.handlers.stats
  "Stats handler for database statistics endpoint.

   GET /api/stats - Returns counts of institutions, accounts, transactions"
  (:require
   [finance-aggregator.db.stats :as db-stats]
   [finance-aggregator.http.responses :as responses]))

(defn stats-handler
  "Factory function: creates a handler for GET /api/stats.

   Returns counts of:
   - institutions
   - accounts
   - transactions

   Args:
     deps - Map with :db-conn (Datalevin connection)

   Returns:
     Ring handler function"
  [{:keys [db-conn]}]
  (fn [_request]
    (responses/success-response (db-stats/entity-counts db-conn))))
