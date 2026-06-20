(ns finance-aggregator.db.stats
  "Entity-count stats shared by the JSON API and the server-rendered pages —
   one service layer, two presentations."
  (:require
   [datalevin.core :as d]))

(defn entity-counts
  "Counts of institutions, accounts, and transactions in the database."
  [db-conn]
  (let [db (d/db db-conn)]
    {:institutions (count (d/q '[:find ?e :where [?e :institution/id _]] db))
     :accounts     (count (d/q '[:find ?e :where [?e :account/external-id _]] db))
     :transactions (count (d/q '[:find ?e :where [?e :transaction/external-id _]] db))}))
