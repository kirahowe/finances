(ns finance-aggregator.http.handlers.stats
  "Stats handler for database statistics endpoint.

   GET /api/stats - Returns counts of institutions, accounts, transactions"
  (:require
   [datalevin.core :as d]
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
    (let [db (d/db db-conn)
          institution-count (count (d/q '[:find ?e
                                          :where [?e :institution/id _]]
                                        db))
          account-count (count (d/q '[:find ?e
                                      :where [?e :account/external-id _]]
                                    db))
          transaction-count (count (d/q '[:find ?e
                                          :where [?e :transaction/external-id _]]
                                        db))]
      (responses/success-response
       {:institutions institution-count
        :accounts account-count
        :transactions transaction-count}))))
