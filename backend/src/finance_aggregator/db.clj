(ns finance-aggregator.db
  (:require
   [datalevin.core :as d]
   [finance-aggregator.data.schema :as schema]))

(def db-path "data/finance.db")

(defonce conn
  (d/get-conn db-path schema/schema))

(defn insert!
  "Insert entities into the datalevin database.
   Entities must be a map with :institutions, :accounts, and :transactions keys.
   All entities should already be transformed into database-ready format.
   Inserts in order to satisfy referential integrity.
   Accepts an optional connection parameter (defaults to global conn)."
  ([entities] (insert! entities conn))
  ([{:keys [institutions accounts transactions]} db-conn]
   ;; Insert in order: institutions first, then accounts, then transactions
   (d/transact! db-conn institutions)
   (d/transact! db-conn accounts)
   (d/transact! db-conn transactions)
   ;; Return the connection for convenience
   db-conn))
