(ns finance-aggregator.db
  (:require
   [datalevin.core :as d]))

(defn insert!
  "Insert entities into the datalevin database.
   Entities must be a map with :institutions, :accounts, and :transactions keys.
   All entities should already be transformed into database-ready format.
   Inserts in order to satisfy referential integrity.

   Connection must be provided via dependency injection (integrant system).

   entities: {:institutions set-of-institution-maps
              :accounts set-of-account-maps
              :transactions vector-of-transaction-maps}
   db-conn: Datalevin connection from integrant system

   Returns: the connection for convenience"
  [{:keys [institutions accounts transactions]} db-conn]
  ;; Insert in order: institutions first, then accounts, then transactions
  (d/transact! db-conn institutions)
  (d/transact! db-conn accounts)
  (d/transact! db-conn transactions)
  ;; Return the connection for convenience
  db-conn)
