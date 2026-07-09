(ns finance-aggregator.db.stats
  "Entity-count stats shared by the JSON API and the server-rendered pages —
   one service layer, two presentations."
  (:require
   [datalevin.core :as d]))

(defn entity-counts
  "Counts of institutions, accounts, and transactions in the database.
   :transactions excludes split PART rows (those carrying :transaction/split-parent):
   the count is imported + manual rows, so splitting a transaction never changes how
   many transactions you have. (Note this is the opposite exclusion from the list
   fns, which hide the PARENT and show its parts.)"
  [db-conn]
  (let [db (d/db db-conn)]
    {:institutions (count (d/q '[:find ?e :where [?e :institution/id _]] db))
     :accounts     (count (d/q '[:find ?e :where [?e :account/external-id _]] db))
     :transactions (count (d/q '[:find ?e :where
                                 [?e :transaction/external-id _]
                                 (not [?e :transaction/split-parent _])] db))}))
